package com.shiwushop.commerce.shipment;

import com.shiwushop.entity.shipment.Shipment;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.mapper.shipment.ShipmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "shop.shipment.rabbit-enabled", havingValue = "true")
public class ShipmentOutboxPublisher {
    private final OutboxEventMapper outbox;
    private final ShipmentMapper shipments;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    @Value("${shop.shipment.scan-budget-ms:20000}")
    private long scanBudgetMillis = 20000;

    @Scheduled(initialDelayString = "${shop.shipment.scan-delay-ms}",
            fixedDelayString = "${shop.shipment.scan-delay-ms}", scheduler = "shipmentOutboxScheduler")
    public void publishPending() {
        long started = System.nanoTime();
        int claimed = 0;
        int failed = 0;
        for (OutboxEventMapper.ShipmentConfirmEvent event :
                outbox.selectPendingShipmentEvents(LocalDateTime.now(clock), 50)) {
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
        log.info("物流 outbox 扫描 claimed={} failed={} durationMs={} oldestPendingSeconds={}", claimed, failed,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                outbox.oldestShipmentPendingSeconds(LocalDateTime.now(clock)));
    }

    private boolean publish(OutboxEventMapper.ShipmentConfirmEvent event, String token) {
        try {
            Shipment shipment = shipments.selectById(event.getShipmentId());
            if (shipment == null) {
                outbox.markDead(event.getId(), token);
                return false;
            }
            long delay = Math.max(1, Duration.between(LocalDateTime.now(clock),
                    shipment.getAutoConfirmAt()).toMillis());
            CorrelationData correlation = new CorrelationData(event.getId().toString());
            rabbit.convertAndSend(ShipmentMessagingConfig.EXCHANGE, ShipmentMessagingConfig.DELAY_ROUTING,
                    event.getId().toString(), message -> {
                        message.getMessageProperties().setExpiration(Long.toString(delay));
                        return message;
                    }, correlation);
            // 同时确认 broker 已接收且路由成功，之后才能把 outbox 标为已发布。
            if (!correlation.getFuture().get(5, TimeUnit.SECONDS).isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ 未确认物流消息投递");
            }
            outbox.markPublished(event.getId(), token, LocalDateTime.now(clock));
            return true;
        } catch (Exception failure) {
            log.warn("物流 outbox 投递失败，eventId={}", event.getId(), failure);
            if (event.getAttempts() >= 9) {
                outbox.markDead(event.getId(), token);
            } else {
                long backoffSeconds = Math.min(300, 1L << Math.min(event.getAttempts(), 8));
                outbox.markRetry(event.getId(), token, LocalDateTime.now(clock).plusSeconds(backoffSeconds));
            }
            return false;
        }
    }
}
