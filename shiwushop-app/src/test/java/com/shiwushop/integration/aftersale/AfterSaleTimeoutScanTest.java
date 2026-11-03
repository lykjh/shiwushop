package com.shiwushop.integration.aftersale;

import com.shiwushop.aftersale.service.AfterSaleTimeoutService;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.sql.DriverManager;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AfterSaleTimeoutScanTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AfterSaleTimeoutService service;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SdkAlipayGateway gateway;
    @MockitoBean Clock clock;
    @Value("${spring.datasource.url}") String datasourceUrl;
    @Value("${spring.datasource.username}") String datasourceUser;
    @Value("${spring.datasource.password}") String datasourcePassword;

    @Test
    void reviewDeadlineIncludesExactBoundaryAndDeduplicates() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-03T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            LocalDateTime deadline = LocalDateTime.of(2026, 11, 1, 8, 0);
            for (var at : List.of(deadline.minusSeconds(1), deadline, deadline.plusSeconds(1))) {
                jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','REQUESTED','测试',1,?)", order, at);
                Long sale = jdbc.queryForObject("SELECT MAX(id) FROM after_sale_cases WHERE order_id=?", Long.class, order);
                new TransactionTemplate(transactions).executeWithoutResult(status -> service.onStageChanged(sale));
            }
            service.scan();
            service.scan();
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=? AND t.stage='REVIEW' AND t.status='OPEN'", Integer.class, order));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=? AND c.requested_at=? AND t.status='OPEN'", Integer.class, order, deadline.plusSeconds(1)));
        } finally {
            jdbc.update("DELETE t FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id IN (SELECT id FROM orders WHERE user_id=?)", fixture.buyer.id());
            fixture.close();
        }
    }
    @Test
    void returnReceiptAndRefundBoundariesExcludeAllExistingRefundAttempts() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-03T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long payment = jdbc.queryForObject("SELECT id FROM payment_attempts WHERE order_id=? AND status='SUCCESS'", Long.class, order);
            LocalDateTime now = LocalDateTime.of(2026, 11, 3, 8, 0);
            for (String phase : List.of("RETURN", "RECEIPT", "REFUND_ONLY", "REFUND_RETURN")) {
                LocalDateTime cutoff = phase.startsWith("REFUND") ? now.minusHours(24) : now.minusDays(7);
                for (int delta : List.of(-1, 0, 1)) {
                    LocalDateTime basis = cutoff.plusSeconds(delta);
                    String type = phase.equals("REFUND_ONLY") ? "REFUND_ONLY" : "RETURN_REFUND";
                    String status = switch (phase) {
                        case "RETURN" -> "WAITING_RETURN";
                        case "RECEIPT" -> "WAITING_RECEIPT";
                        default -> "REFUNDING";
                    };
                    String reason = phase + delta;
                    jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at,reviewed_at) VALUES (?,?,?,?,1,?,?)",
                            order, type, status, reason, now.minusDays(20), basis);
                    Long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=? AND reason=?", Long.class, order, reason);
                    if (phase.equals("RECEIPT")) {
                        jdbc.update("INSERT INTO return_shipments(case_id,carrier_name,tracking_no,sent_at) VALUES (?,?,?,?)", sale, "顺丰", "SF" + sale, basis);
                    } else if (phase.equals("REFUND_RETURN")) {
                        jdbc.update("INSERT INTO return_shipments(case_id,carrier_name,tracking_no,sent_at,received_at,received_by_user_id) VALUES (?,?,?,?,?,?)",
                                sale, "顺丰", "SF" + sale, basis.minusDays(1), basis, fixture.catalog.merchant.id());
                    }
                    new TransactionTemplate(transactions).executeWithoutResult(statusValue -> service.onStageChanged(sale));
                }
            }
            for (String attempt : List.of("PENDING", "UNKNOWN", "FAILED")) {
                String reason = "attempt-" + attempt;
                jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at,reviewed_at) VALUES (?,'REFUND_ONLY','REFUNDING',?,1,?,?)",
                        order, reason, now.minusDays(3), now.minusDays(2));
                Long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=? AND reason=?", Long.class, order, reason);
                jdbc.update("INSERT INTO refund_attempts(case_id,payment_attempt_id,out_request_no,amount,status,requested_at) VALUES (?,?,?,1,?,?)",
                        sale, payment, "timeout-attempt-" + sale, attempt, now.minusDays(1));
                new TransactionTemplate(transactions).executeWithoutResult(statusValue -> service.onStageChanged(sale));
            }
            service.scan();
            service.scan();
            for (String phase : List.of("RETURN", "RECEIPT", "REFUND_ONLY", "REFUND_RETURN")) {
                String stage = phase.startsWith("REFUND") ? "REFUND" : phase;
                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=? AND c.reason LIKE ? AND t.stage=? AND t.status='OPEN'", Integer.class, order, phase + "%", stage), phase);
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=? AND c.reason=? AND t.status='OPEN'", Integer.class, order, phase + "1"), phase);
            }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=? AND c.reason LIKE 'attempt-%'", Integer.class, order));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=? AND status='CLOSED'", Integer.class, order));
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=? AND t.rule_version=1", Integer.class, order) >= 8);
        } finally {
            jdbc.update("DELETE t FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id IN (SELECT id FROM orders WHERE user_id=?)", fixture.buyer.id());
            fixture.close();
        }
    }
    @Test
    void staleOpenTaskIsRetainedAndClosedTaskIsUnchanged() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-03T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            for (String reason : List.of("过期处理", "已关闭处理")) {
                jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','CLOSED',?,1,'2026-10-01 08:00:00')", order, reason);
            }
            jdbc.update("INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at) SELECT id,'REVIEW',requested_at,'2026-10-03 08:00:00',1,IF(reason='过期处理','OPEN','CLOSED'),NOW() FROM after_sale_cases WHERE order_id=?", order);
            for (Long sale : jdbc.queryForList("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class, order)) {
                new TransactionTemplate(transactions).executeWithoutResult(status -> service.onStageChanged(sale));
            }
            service.scan();
            assertEquals("STALE", jdbc.queryForObject("SELECT t.status FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=? AND c.reason='过期处理'", String.class, order));
            assertEquals("CLOSED", jdbc.queryForObject("SELECT t.status FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=? AND c.reason='已关闭处理'", String.class, order));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=? AND status='CLOSED'", Integer.class, order));
        } finally {
            jdbc.update("DELETE t FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id IN (SELECT id FROM orders WHERE user_id=?)", fixture.buyer.id());
            fixture.close();
        }
    }
    @Test
    void concurrentScannersActivateExactlyOnce() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-03T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','REQUESTED','并发扫描',1,'2026-10-01 08:00:00')", order);
            Long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class, order);
            new TransactionTemplate(transactions).executeWithoutResult(status -> service.onStageChanged(sale));
            try (var locker = DriverManager.getConnection(datasourceUrl, datasourceUser, datasourcePassword);
                 var pool = Executors.newFixedThreadPool(2)) {
                locker.setAutoCommit(false);
                try (var statement = locker.prepareStatement("SELECT id FROM after_sale_cases WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, sale);
                    statement.executeQuery().close();
                }
                var barrier = new CyclicBarrier(2);
                var first = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); service.scan(); return null; });
                var second = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); service.scan(); return null; });
                try {
                    assertThrows(TimeoutException.class, () -> first.get(300, TimeUnit.MILLISECONDS));
                    assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
                } finally {
                    locker.commit();
                }
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks WHERE case_id=? AND status='OPEN' AND opened_at IS NOT NULL", Integer.class, sale));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks WHERE case_id=?", Integer.class, sale));
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void scannerRechecksStatusAfterConcurrentClosure() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-03T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','REQUESTED','关闭竞态',1,'2026-10-01 08:00:00')", order);
            Long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class, order);
            new TransactionTemplate(transactions).executeWithoutResult(status -> service.onStageChanged(sale));
            try (var locker = DriverManager.getConnection(datasourceUrl, datasourceUser, datasourcePassword);
                 var pool = Executors.newSingleThreadExecutor()) {
                locker.setAutoCommit(false);
                try (var statement = locker.prepareStatement("UPDATE after_sale_cases SET status='CLOSED' WHERE id=?")) {
                    statement.setLong(1, sale);
                    statement.executeUpdate();
                }
                var pending = pool.submit(() -> { service.scan(); return null; });
                try {
                    assertThrows(TimeoutException.class, () -> pending.get(300, TimeUnit.MILLISECONDS));
                } finally {
                    locker.commit();
                }
                pending.get(10, TimeUnit.SECONDS);
                assertEquals("STALE", jdbc.queryForObject("SELECT status FROM after_sale_timeout_tasks WHERE case_id=?", String.class, sale));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks WHERE case_id=? AND opened_at IS NOT NULL", Integer.class, sale));
            }
        } finally {
            fixture.close();
        }
    }
}
