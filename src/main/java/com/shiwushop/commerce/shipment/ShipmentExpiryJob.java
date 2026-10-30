package com.shiwushop.commerce.shipment;

import com.shiwushop.commerce.service.ShipmentService;
import com.shiwushop.commerce.mapper.shipment.ShipmentMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentExpiryJob {
    private final ShipmentService shipmentService;
    private final ShipmentMapper shipmentMapper;
    private final Clock clock;
    @Value("${shop.shipment.scan-budget-ms:20000}")
    private long scanBudgetMillis = 20000;

    /** 在单轮时间预算内逐个确认到期运单，失败项留待下一轮。 */
    @Scheduled(initialDelayString = "${shop.shipment.scan-delay-ms}",
            fixedDelayString = "${shop.shipment.scan-delay-ms}", scheduler = "shipmentExpiryScheduler")
    public void confirmDue() {
        long started = System.nanoTime();
        int processed = 0;
        int failed = 0;
        for (Long shipmentId : shipmentService.dueShipmentIds()) {
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= scanBudgetMillis) break;
            processed++;
            try {
                shipmentService.confirmDue(shipmentId);
            } catch (RuntimeException failure) {
                failed++;
                log.warn("自动确认收货失败，shipmentId={}", shipmentId, failure);
            }
        }
        log.info("物流到期扫描 processed={} failed={} durationMs={} oldestPendingSeconds={}", processed, failed,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                shipmentMapper.oldestDueSeconds(LocalDateTime.now(clock)));
    }
}
