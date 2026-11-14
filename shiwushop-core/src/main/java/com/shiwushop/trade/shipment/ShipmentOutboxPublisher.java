package com.shiwushop.trade.shipment;

import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.entity.status.ShipmentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.apache.rocketmq.spring.support.RocketMQHeaders;


import java.time.ZoneId;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.UUID;



import org.springframework.dao.DataAccessException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${shop.shipment.rocket-enabled:false} && ${shop.shipment.rocket-publisher-enabled:true}")
public class ShipmentOutboxPublisher {
    private final OutboxEventMapper outbox;
    private final ShipmentMapper shipments;
    private final RocketMQTemplate shipmentRocketTemplate;
    private final Clock clock;
    @Value("${shop.shipment.scan-budget-ms:20000}")
    private long scanBudgetMillis = 20000;

    /** 按租约分批认领待投递的物流事件，单轮受数量和耗时预算限制。 */
    @Scheduled(initialDelayString = "${shop.shipment.scan-delay-ms}",
            fixedDelayString = "${shop.shipment.scan-delay-ms}", scheduler = "shipmentOutboxScheduler")
    public void publishPending() {
        long started = System.nanoTime();
        int claimed = 0;
        int failed = 0;
        for (OutboxEventMapper.ShipmentConfirmEvent event :
                outbox.selectPendingShipmentEvents(LocalDateTime.now(clock), 50)) {
            if (Thread.currentThread().isInterrupted())
                break;
            // Do not claim another event once this scan has spent its time budget.
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= scanBudgetMillis)
                break;
            String token = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now(clock);
            // 带租约的认领使多个应用实例不会同时投递同一事件。
            if (outbox.claimShipmentEvent(event.getId(), token, now, now.plusSeconds(30)) == 1) {
                claimed++;
                if (!publish(event, token))
                    failed++;
            }
        }
        log.info("物流 outbox 扫描 claimed={} failed={} durationMs={} eligibleWaitSeconds={} future={} eligible={}",
                claimed, failed,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                outbox.oldestShipmentPendingSeconds(LocalDateTime.now(clock)),
                outbox.futureShipmentCount(LocalDateTime.now(clock)),
                outbox.eligibleShipmentCount(LocalDateTime.now(clock)));
    }

    /** 取得 RocketMQ 回执后标记已投递；失败按次数退避重试。 */
    private boolean publish(OutboxEventMapper.ShipmentConfirmEvent event, String token) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("eventId", event.getId().toString())) {
            Shipment shipment = shipments.selectById(event.getShipmentId());
            if (shipment == null) {
                outbox.markDead(event.getId(), token);
                return false;
            }
            if (shipment.getStatus() != ShipmentStatus.SHIPPED || shipment.getCarrierSignedAt() == null
                    || shipment.getAutoConfirmAt() == null) {
                outbox.markDead(event.getId(), token);
                return true;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            // 十天期限仍保存在数据库，只在截止前 23 小时进入 Broker 定时窗口。
            LocalDateTime eligibleAt = shipment.getAutoConfirmAt().minusHours(23);
            if (eligibleAt.isAfter(now)) {
                outbox.deferShipmentEvent(event.getId(), token, eligibleAt);
                return true;
            }
            // 已过期事件也用未来时间戳；加两秒留发送余量，业务仍检查原截止时间。
            LocalDateTime delivery = (shipment.getAutoConfirmAt().isAfter(now)
                    ? shipment.getAutoConfirmAt() : now).plusSeconds(2);
            var message = MessageBuilder.withPayload(event.getId().toString())
                    .setHeader(RocketMQHeaders.KEYS, event.getId().toString()).build();
            // 超时可能只是回执丢失，重试沿用 eventId，由消费事务去重。
            var receipt = shipmentRocketTemplate.syncSendDeliverTimeMills(
                    ShipmentRocketConfig.TOPIC + ":" + ShipmentRocketConfig.TAG, message,
                    delivery.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
            if (receipt.getSendStatus() != SendStatus.SEND_OK) {
                log.warn("shipment_send_unknown transport=rocket eventId={} status={}",
                        event.getId(), receipt.getSendStatus());
                retry(event, token);
                return false;
            }
            String messageId = receipt.getMsgId();
            log.info(
                    "shipment_send transport=rocket eventId={} shipmentId={} messageId={} deadline={} delivery={} acceptedAt={}",
                    event.getId(), event.getShipmentId(), messageId, shipment.getAutoConfirmAt(), delivery,
                    LocalDateTime.now(clock));
            if (outbox.markPublished(event.getId(), token, LocalDateTime.now(clock)) == 1)
                log.info("shipment_event outcome=published transport=rocket eventId={} shipmentId={} messageId={}",
                        event.getId(), event.getShipmentId(), messageId);
            return true;
        } catch (IllegalArgumentException configurationFailure) {
            // SDK 构造或发送边界的参数拒绝不应进入暂时故障重试。
            log.error("shipment_send_configuration_error eventId={} shipmentId={}",
                    event.getId(), event.getShipmentId(), configurationFailure);
            outbox.markDead(event.getId(), token);
            return false;
        } catch (MessagingException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof InterruptedException)
                Thread.currentThread().interrupt();
            if (cause instanceof IllegalArgumentException
                    || (cause instanceof MQBrokerException brokerFailure
                    && (brokerFailure.getResponseCode() == ResponseCode.NO_PERMISSION
                    || brokerFailure.getResponseCode() == ResponseCode.TOPIC_NOT_EXIST
                    || brokerFailure.getResponseCode() == ResponseCode.MESSAGE_ILLEGAL
                    || brokerFailure.getErrorMessage().contains("accurate timer message is not enabled")
                    || brokerFailure.getErrorMessage().contains("timer message illegal")))) {
                log.error("shipment_send_configuration_error transport=rocket eventId={} shipmentId={}",
                        event.getId(), event.getShipmentId(), failure);
                outbox.markDead(event.getId(), token);
            } else {
                log.warn("shipment_send_unknown transport=rocket eventId={} shipmentId={}",
                        event.getId(), event.getShipmentId(), failure);
                retry(event, token);
            }
            return false;
        } catch (DataAccessException failure) {
            log.warn("物流 outbox 投递失败，eventId={}", event.getId(), failure);
            retry(event, token);
            return false;
        }
    }

    /** 投递故障与中断共用原有重试上限，避免停止扫描时遗留已认领事件。 */
    private void retry(OutboxEventMapper.ShipmentConfirmEvent event, String token) {
        if (event.getAttempts() >= 9)
            outbox.markDead(event.getId(), token);
        else {
            long backoffSeconds = Math.min(300, 1L << Math.min(event.getAttempts(), 8));
            outbox.markRetry(event.getId(), token, LocalDateTime.now(clock).plusSeconds(backoffSeconds));
        }
    }
}

