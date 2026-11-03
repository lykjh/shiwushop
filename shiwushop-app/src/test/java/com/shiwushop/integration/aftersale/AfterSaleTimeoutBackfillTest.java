package com.shiwushop.integration.aftersale;

import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AfterSaleTimeoutBackfillTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SdkAlipayGateway gateway;

    @Test
    void migrationBackfillsV1DeadlinesAndPreservesClosedRecord() throws Exception {
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try (var accounts = new TestAccounts(jdbc)) {
            var admin = accounts.create("ADMIN", "Shop123!");
            try {
                long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
                Long payment = jdbc.queryForObject("SELECT id FROM payment_attempts WHERE order_id=? AND status='SUCCESS'", Long.class, order);
                LocalDateTime basis = LocalDateTime.of(2026, 10, 1, 8, 0);
                for (String reason : List.of("review", "return", "receipt", "refund-only", "refund-return", "refund-failed", "missing")) {
                    String type = reason.equals("review") || reason.equals("refund-only") || reason.equals("refund-failed") ? "REFUND_ONLY" : "RETURN_REFUND";
                    String state = switch (reason) {
                        case "review" -> "REQUESTED";
                        case "return", "missing" -> "WAITING_RETURN";
                        case "receipt" -> "WAITING_RECEIPT";
                        default -> "REFUNDING";
                    };
                    jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at,reviewed_at) VALUES (?,?,?,?,1,?,?)", order, type, state, reason, basis, reason.equals("missing") ? null : basis);
                }
                Long review = id(order, "review");
                Long receipt = id(order, "receipt");
                Long refundReturn = id(order, "refund-return");
                Long failed = id(order, "refund-failed");
                jdbc.update("INSERT INTO return_shipments(case_id,carrier_name,tracking_no,sent_at) VALUES (?,?,?,?)", receipt, "顺丰", "SF-BACKFILL-1", basis);
                jdbc.update("INSERT INTO return_shipments(case_id,carrier_name,tracking_no,sent_at,received_at,received_by_user_id) VALUES (?,?,?,?,?,?)", refundReturn, "顺丰", "SF-BACKFILL-2", basis.minusDays(1), basis, fixture.catalog.merchant.id());
                jdbc.update("INSERT INTO refund_attempts(case_id,payment_attempt_id,out_request_no,amount,status,requested_at) VALUES (?,?,?,1,'FAILED',?)", failed, payment, "backfill-" + failed, basis);
                jdbc.update("INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,assignee_id,evidence,conclusion,created_at,opened_at,closed_at) VALUES (?,'REVIEW',?,?,1,'CLOSED',?,'记录','已核实',?,?,?)", review, basis, basis.plusHours(48), admin.id(), basis, basis, basis);
                try (var resource = getClass().getClassLoader().getResourceAsStream("db/migration/V25__after_sale_timeout_tasks.sql")) {
                    assertNotNull(resource);
                    String migration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                    for (String statement : migration.split(";")) {
                        int start = statement.indexOf("INSERT INTO after_sale_timeout_tasks");
                        if (start >= 0) {
                            String sql = statement.substring(start).replace("ON DUPLICATE KEY UPDATE", "AND c.order_id=? ON DUPLICATE KEY UPDATE");
                            jdbc.update(sql, order);
                        }
                    }
                }
                assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=?", Integer.class, order));
                assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM after_sale_timeout_tasks WHERE case_id=?", String.class, review));
                assertEquals(basis.plusDays(7), jdbc.queryForObject("SELECT due_at FROM after_sale_timeout_tasks WHERE case_id=?", LocalDateTime.class, id(order, "return")));
                assertEquals(basis.plusDays(7), jdbc.queryForObject("SELECT due_at FROM after_sale_timeout_tasks WHERE case_id=?", LocalDateTime.class, receipt));
                assertEquals(basis.plusHours(24), jdbc.queryForObject("SELECT due_at FROM after_sale_timeout_tasks WHERE case_id=?", LocalDateTime.class, id(order, "refund-only")));
                assertEquals(basis.plusHours(24), jdbc.queryForObject("SELECT due_at FROM after_sale_timeout_tasks WHERE case_id=?", LocalDateTime.class, refundReturn));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_timeout_tasks WHERE case_id IN (?,?)", Integer.class, failed, id(order, "missing")));
                jdbc.update("DELETE t FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id=?", order);
            } finally {
                jdbc.update("DELETE t FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id JOIN orders o ON o.id=c.order_id WHERE o.user_id=?", fixture.buyer.id());
            }
        } finally {
            fixture.close();
        }
    }

    private Long id(long order, String reason) {
        return jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=? AND reason=?", Long.class, order, reason);
    }
}
