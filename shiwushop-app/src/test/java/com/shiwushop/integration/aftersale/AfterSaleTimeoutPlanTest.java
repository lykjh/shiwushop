package com.shiwushop.integration.aftersale;

import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AfterSaleTimeoutPlanTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SdkAlipayGateway gateway;

    @Test
    void measureAlreadyRecordedReviewCandidates() throws Exception {
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            jdbc.update("""
                    INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at)
                    WITH RECURSIVE n AS (SELECT 0 AS i UNION ALL SELECT i+1 FROM n WHERE i<9)
                    SELECT ?, 'REFUND_ONLY','REQUESTED',CONCAT('timeout-plan-',a.i,b.i,c.i,d.i),1,'2020-01-01 00:00:00'
                    FROM n a CROSS JOIN n b CROSS JOIN n c CROSS JOIN n d
                    """, order);
            jdbc.update("""
                    INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at)
                    SELECT id,'REVIEW',requested_at,'2020-01-03 00:00:00',1,'OPEN',NOW()
                    FROM after_sale_cases WHERE order_id=? AND reason LIKE 'timeout-plan-%'
                    """, order);
            assertEquals(10000, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=? AND reason LIKE 'timeout-plan-%'", Integer.class, order));
            var rows = jdbc.queryForList("""
                    EXPLAIN ANALYZE SELECT c.id FROM after_sale_cases c
                    WHERE c.status='REQUESTED' AND c.requested_at <= '2026-11-03 08:00:00'
                      AND NOT EXISTS (SELECT 1 FROM after_sale_timeout_tasks t WHERE t.case_id=c.id AND t.stage='REVIEW' AND t.basis_at=c.requested_at)
                    ORDER BY c.requested_at,c.id LIMIT 20
                    """, String.class);
            rows.forEach(System.out::println);
            System.out.println("QUEUE_NO_DUE");
            jdbc.queryForList("EXPLAIN ANALYZE SELECT id FROM after_sale_timeout_tasks WHERE status='SCHEDULED' AND due_at <= '2026-11-03 08:00:00' ORDER BY due_at,id LIMIT 100", String.class).forEach(System.out::println);
            jdbc.update("INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at) SELECT id,'RETURN',requested_at,'2020-01-03 00:00:00',1,'SCHEDULED',NOW() FROM after_sale_cases WHERE order_id=? AND reason LIKE 'timeout-plan-%' LIMIT 1", order);
            System.out.println("QUEUE_ONE_DUE");
            jdbc.queryForList("EXPLAIN ANALYZE SELECT id FROM after_sale_timeout_tasks WHERE status='SCHEDULED' AND due_at <= '2026-11-03 08:00:00' ORDER BY due_at,id LIMIT 100", String.class).forEach(System.out::println);
        } finally {
            jdbc.update("DELETE t FROM after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id WHERE c.order_id IN (SELECT id FROM orders WHERE user_id=?)", fixture.buyer.id());
            jdbc.update("DELETE FROM after_sale_cases WHERE order_id IN (SELECT id FROM orders WHERE user_id=?) AND reason LIKE 'timeout-plan-%'", fixture.buyer.id());
            fixture.close();
        }
    }
}
