package com.shiwushop.integration.commerce;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 单独 Maven 进程运行，读取上一进程通过实际 HTTP 创建并支付的持久化样本。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "shop.payment.simulation-enabled=true",
        "server.port=18083", "server.address=127.0.0.1", "spring.autoconfigure.exclude=",
        "spring.datasource.hikari.maximum-pool-size=4", "spring.data.redis.database=9",
        "shop.member.enabled=true", "shop.member.reward-start-at=2026-11-08T08:43:34",
        "shop.marketing.enabled=true", "shop.member.scan-delay-ms=86400000",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalSimulationRestartHttpTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        LocalSimulationPaymentHttpTest.database(properties);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void paidOrderSurvivesJvmRestartAndQueryDoesNotCreditAgain() throws Exception {
        assertEquals("shiwushop_sim_payment_20261108", jdbc.queryForObject("SELECT DATABASE()", String.class));
        var row = jdbc.queryForMap("SELECT o.id,o.user_id,a.username FROM orders o "
                + "JOIN payment_attempts p ON p.order_id=o.id JOIN user_accounts a ON a.user_id=o.user_id "
                + "JOIN checkout_request_orders l ON l.order_id=o.id JOIN checkout_requests c ON c.id=l.checkout_request_id "
                + "WHERE p.channel='LOCAL_SIMULATION' AND p.status='SUCCESS' AND c.idempotency_key='sim-explicit' "
                + "ORDER BY o.id DESC LIMIT 1");
        long order = ((Number) row.get("id")).longValue();
        var account = new TestAccounts.Account(((Number) row.get("user_id")).longValue(),
                (String) row.get("username"), "Shop123!");
        try (var client = CommerceFixture.login("http://127.0.0.1:18083", account)) {
            var status = client.get("/orders/" + order + "/payment");
            assertEquals(200, status.statusCode(), status.body());
            assertTrue(status.body().contains("LOCAL_SIMULATION"), status.body());
            assertTrue(status.body().contains("SUCCESS"), status.body());
            assertEquals(200, client.get("/orders/" + order).statusCode());
            assertEquals(200, client.get("/members/me").statusCode());
            assertEquals(200, client.post("/orders/" + order + "/payment/simulate", Map.of()).statusCode());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM member_point_entries WHERE order_id=? AND event_kind='EARN'", Integer.class, order));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE target_type='ORDER' AND target_id=? AND action='LOCAL_PAYMENT_SIMULATED'", Integer.class, order));
            Path output = Path.of(System.getProperty("simulation.evidence.dir", "target/simulation-evidence"));
            Files.createDirectories(output);
            Files.writeString(output.resolve("restart-" + order + ".json"), new ObjectMapper().writeValueAsString(
                    Map.of("jvmPid", ProcessHandle.current().pid(), "statusResponse", status.body(),
                            "points", jdbc.queryForList("SELECT id,event_kind,operation_key,available_delta FROM member_point_entries WHERE order_id=?", order))));
        }
    }
}
