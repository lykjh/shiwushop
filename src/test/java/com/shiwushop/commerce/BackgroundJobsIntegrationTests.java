package com.shiwushop.commerce;

import com.shiwushop.mapper.order.OrderMapper;
import com.shiwushop.mapper.outbox.OutboxEventMapper;
import com.shiwushop.mapper.payment.RefundAttemptMapper;
import com.shiwushop.mapper.shipment.ShipmentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class BackgroundJobsIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderMapper orders;
    @Autowired private RefundAttemptMapper refunds;
    @Autowired private ShipmentMapper shipments;
    @Autowired private OutboxEventMapper outbox;

    @Test
    void readsBacklogAgesAndKeepsPendingShipmentEventsVisible() {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO outbox_events(event_type, aggregate_type, aggregate_id, payload,
                                          status, attempts, next_attempt_at, created_at)
                VALUES ('SHIPMENT_AUTO_CONFIRM', 'SHIPMENT', 1, '{}', 'PENDING', 0, ?, ?)
                """, now.minusMinutes(2), now.minusMinutes(2));

        assertTrue(outbox.oldestShipmentPendingSeconds(now) >= 100);
        assertDoesNotThrow(() -> orders.oldestExpiredSeconds(now));
        assertDoesNotThrow(() -> refunds.oldestUnresolvedSeconds(now.minusSeconds(10), now));
        assertDoesNotThrow(() -> shipments.oldestDueSeconds(now));
    }
}
