package com.shiwushop.trade.shipment;

import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataAccessException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("(${shop.shipment.rabbit-enabled:false} && ${shop.shipment.rabbit-publisher-enabled:true}) || (${shop.shipment.rocket-enabled:false} && ${shop.shipment.rocket-publisher-enabled:true})")
public class ShipmentOutboxPublisher {
    private final OutboxEventMapper outbox;
    private final ShipmentMapper shipments;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    @Autowired(required = false)
    private Producer shipmentRocketProducer;
    @Value("${shop.shipment.rocket-publisher-enabled:true}")
    private boolean rocketPublisherEnabled = true;
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
            if (Thread.currentThread().isInterrupted()) break;
            // Do not claim another event once this scan has spent its time budget.
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= scanBudgetMillis) break;
            String token = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now(clock);
            // 带租约的认领使多个应用实例不会同时投递同一事件。
            if (outbox.claimShipmentEvent(event.getId(), token, now, now.plusSeconds(30)) == 1) {
                claimed++;
                if (!publish(event, token)) failed++;
            }
        }
        log.info("物流 outbox 扫描 claimed={} failed={} durationMs={} eligibleWaitSeconds={} future={} eligible={}", claimed, failed,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                outbox.oldestShipmentPendingSeconds(LocalDateTime.now(clock)),
                outbox.futureShipmentCount(LocalDateTime.now(clock)), outbox.eligibleShipmentCount(LocalDateTime.now(clock)));
    }

    /** 取得所选通道的有效回执后标记已投递；失败按次数退避重试。 */
    private boolean publish(OutboxEventMapper.ShipmentConfirmEvent event, String token) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("eventId", event.getId().toString())) {
            Shipment shipment = shipments.selectById(event.getShipmentId());
            if (shipment == null) {
                outbox.markDead(event.getId(), token);
                return false;
            }
            String messageId;
            if (shipmentRocketProducer != null && rocketPublisherEnabled) {
                LocalDateTime now = LocalDateTime.now(clock);
                LocalDateTime eligibleAt = shipment.getAutoConfirmAt().minusHours(23);
                if (eligibleAt.isAfter(now)) {
                    outbox.deferShipmentEvent(event.getId(), token, eligibleAt);
                    return true;
                }
                LocalDateTime delivery = (shipment.getAutoConfirmAt().isAfter(now)
                        ? shipment.getAutoConfirmAt() : now).plusSeconds(2);
                var message = ClientServiceProvider.loadService().newMessageBuilder()
                        .setTopic(ShipmentRocketConfig.TOPIC).setTag(ShipmentRocketConfig.TAG)
                        .setKeys(event.getId().toString())
                        .setBody(event.getId().toString().getBytes(StandardCharsets.UTF_8))
                        .setDeliveryTimestamp(delivery.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()).build();
                var receipt = shipmentRocketProducer.sendAsync(message).get(5, TimeUnit.SECONDS);
                messageId = receipt.getMessageId().toString();
                log.info("shipment_send transport=rocket eventId={} shipmentId={} messageId={} deadline={} delivery={} acceptedAt={}",
                        event.getId(), event.getShipmentId(), messageId, shipment.getAutoConfirmAt(), delivery, LocalDateTime.now(clock));
            } else {
                long delay = Math.max(1, Duration.between(LocalDateTime.now(clock),
                        shipment.getAutoConfirmAt()).toMillis());
                CorrelationData correlation = new CorrelationData(event.getId().toString());
                rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.DELAY_ROUTING,
                        event.getId().toString(), message -> {
                            // 死信转发保持原消息持久化属性，避免 broker 重启时丢失到期事件。
                            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            message.getMessageProperties().setExpiration(Long.toString(delay));
                            return message;
                        }, correlation);
                // 同时确认 broker 已接收且路由成功，之后才能把 outbox 标为已发布。
                if (!correlation.getFuture().get(5, TimeUnit.SECONDS).isAck() || correlation.getReturned() != null) {
                    throw new AmqpException("RabbitMQ 未确认物流消息投递");
                }
                messageId = correlation.getId();
            }
            if (outbox.markPublished(event.getId(), token, LocalDateTime.now(clock)) == 1)
                log.info("shipment_event outcome=published transport={} eventId={} shipmentId={} messageId={}",
                        shipmentRocketProducer == null || !rocketPublisherEnabled ? "rabbit" : "rocket", event.getId(), event.getShipmentId(), messageId);
            return true;
        } catch (IllegalArgumentException configurationFailure) {
            // SDK 构造或发送边界的参数拒绝不应进入暂时故障重试。
            log.error("shipment_send_configuration_error eventId={} shipmentId={}",
                    event.getId(), event.getShipmentId(), configurationFailure);
            outbox.markDead(event.getId(), token);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("物流 outbox 等待确认被中断，eventId={}", event.getId());
            retry(event, token);
            return false;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IllegalArgumentException
                    || cause instanceof org.apache.rocketmq.client.java.exception.BadRequestException
                    || cause instanceof org.apache.rocketmq.client.java.exception.ForbiddenException
                    || cause instanceof org.apache.rocketmq.client.java.exception.UnauthorizedException
                    || cause instanceof org.apache.rocketmq.client.java.exception.NotFoundException
                    || cause instanceof org.apache.rocketmq.client.java.exception.UnsupportedException
                    // 5.5.1 将这两种 Broker 配置错误返回为通用 INTERNAL_ERROR，不能把全部 50001 当成永久失败。
                    || (cause instanceof org.apache.rocketmq.client.java.exception.InternalErrorException
                        && (cause.getMessage().contains("accurate timer message is not enabled")
                            || cause.getMessage().contains("timer message illegal")))) {
                log.error("shipment_send_configuration_error transport=rocket eventId={} shipmentId={}",
                        event.getId(), event.getShipmentId(), cause);
                outbox.markDead(event.getId(), token);
            } else {
                log.warn("shipment_send_unknown transport=rocket eventId={} shipmentId={}",
                        event.getId(), event.getShipmentId(), cause);
                retry(event, token);
            }
            return false;
        } catch (AmqpException | TimeoutException | DataAccessException failure) {
            log.warn("物流 outbox 投递失败，eventId={}", event.getId(), failure);
            retry(event, token);
            return false;
        }
    }

    /** 投递故障与中断共用原有重试上限，避免停止扫描时遗留已认领事件。 */
    private void retry(OutboxEventMapper.ShipmentConfirmEvent event, String token) {
        if (event.getAttempts() >= 9) outbox.markDead(event.getId(), token);
        else {
            long backoffSeconds = Math.min(300, 1L << Math.min(event.getAttempts(), 8));
            outbox.markRetry(event.getId(), token, LocalDateTime.now(clock).plusSeconds(backoffSeconds));
        }
    }
}
