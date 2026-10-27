package com.shiwushop.shipment;

import com.shiwushop.mapper.outbox.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ShipmentOutboxClaimTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxEventMapper outbox;

    @Test
    void onlyOneInstanceClaimsAndExpiredClaimCanBeRecovered() throws Exception {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO outbox_events (event_type, aggregate_type, aggregate_id, payload,
                                               status, attempts, next_attempt_at)
                    VALUES ('SHIPMENT_AUTO_CONFIRM', 'CLAIM_TEST', 0, '{}', 'PENDING', 0, NOW() + INTERVAL 1 DAY)
                    """, Statement.RETURN_GENERATED_KEYS);
            return statement;
        }, key);
        long eventId = key.getKey().longValue();
        try {
            LocalDateTime now = LocalDateTime.now().plusDays(1).plusSeconds(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> first = pool.submit(() -> {
                    start.await();
                    return outbox.claimShipmentEvent(eventId, "first", now, now.plusSeconds(30));
                });
                Future<Integer> second = pool.submit(() -> {
                    start.await();
                    return outbox.claimShipmentEvent(eventId, "second", now, now.plusSeconds(30));
                });
                start.countDown();
                assertEquals(1, first.get() + second.get());
            }

            String oldToken = jdbc.queryForObject("SELECT claim_token FROM outbox_events WHERE id=?", String.class, eventId);
            assertEquals(1, outbox.claimShipmentEvent(eventId, "recovered", now.plusSeconds(31), now.plusSeconds(61)));
            assertEquals(0, outbox.markPublished(eventId, oldToken, now.plusSeconds(31)));
            assertEquals(1, outbox.markPublished(eventId, "recovered", now.plusSeconds(31)));
            assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?", String.class, eventId));
        } finally {
            jdbc.update("DELETE FROM outbox_events WHERE id=?", eventId);
        }
    }
}
