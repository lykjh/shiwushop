package com.shiwushop.integration.commerce;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独占库、Redis DB9、18083；真实 HTTP/Session/CSRF/MySQL，使用实际模拟适配器。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "shop.payment.simulation-enabled=true",
        "server.port=18083", "server.address=127.0.0.1", "spring.autoconfigure.exclude=",
        "spring.datasource.hikari.maximum-pool-size=12", "spring.data.redis.database=9",
        "shop.member.enabled=true", "shop.member.reward-start-at=2026-11-08T08:43:34",
        "shop.marketing.enabled=true", "shop.member.scan-delay-ms=86400000",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalSimulationPaymentHttpTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:3306/shiwushop_sim_payment_20261108?serverTimezone=Asia/Shanghai");
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> Objects.requireNonNull(System.getenv("SIM_DB_PASSWORD")));
        properties.add("spring.data.redis.port", () -> 6379);
    }

    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();
    CommerceFixture fixture;

    @BeforeEach
    void prepare() throws Exception {
        assertEquals("shiwushop_sim_payment_20261108", jdbc.queryForObject("SELECT DATABASE()", String.class));
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:18083");
    }

    @AfterEach
    void retainEvidence() {
        // 专库样本保留供重启核对；只关闭本用例的 HTTP 资源，不清理数据库或 Redis。
        if (fixture != null) {
            fixture.client.close();
            fixture.merchantClient.close();
        }
    }

    long started(String key) throws Exception {
        long order = fixture.order(key);
        var response = fixture.client.post("/orders/" + order + "/payment", Map.of());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("LOCAL_SIMULATION"), response.body());
        return order;
    }

    String simulate(long order) {
        return "/orders/" + order + "/payment/simulate";
    }

    @Test
    void defaultChannelOnlyPaysAfterExplicitConcurrentConfirmationAndCreditsOnce() throws Exception {
        long order = started("sim-explicit");
        snapshot("before", order);
        for (String endpoint : List.of("/orders/" + order + "/payment", "/orders/" + order))
            assertEquals(200, fixture.client.get(endpoint).statusCode());
        assertEquals(200, fixture.client.post("/orders/" + order + "/payment/sync", Map.of()).statusCode());
        assertEquals("UNPAID", orderStatus(order));
        assertEquals(0, earned(order));
        var startAgain = fixture.client.post("/orders/" + order + "/payment", Map.of());
        String checkout = json.readTree(startAgain.body()).path("data").path("payUrl").asString();
        var page = fixture.client.get(checkout.substring(checkout.indexOf("/payments/")));
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("模拟支付"));
        assertTrue(page.body().contains("不扣真实资金"));
        assertEquals("UNPAID", orderStatus(order));
        try (var pool = Executors.newFixedThreadPool(6)) {
            var barrier = new CyclicBarrier(6);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return fixture.client.post(simulate(order), Map.of("amount", "0.01")).statusCode();
                }));
            for (var result : results)
                assertEquals(200, result.get(20, TimeUnit.SECONDS));
        }
        assertEquals("PAID", orderStatus(order));
        assertEquals(1, earned(order));
        assertEquals(1990L, jdbc.queryForObject("SELECT available_delta FROM member_point_entries WHERE order_id=? AND event_kind='EARN'", Long.class, order));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=? AND status='SUCCESS' AND amount=19.90", Integer.class, order));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE target_type='ORDER' AND target_id=? AND action='LOCAL_PAYMENT_SIMULATED'", Integer.class, order));
        assertEquals(200, fixture.client.post(simulate(order), Map.of()).statusCode());
        assertEquals(200, fixture.client.get("/members/me").statusCode());
        assertEquals(1, earned(order));
        snapshot("after-repeated-concurrent", order);
    }

    @Test
    void sessionCsrfOwnershipAndRealCallbacksRemainMandatory() throws Exception {
        long order = started("sim-boundary");
        assertEquals(403, fixture.client.post(simulate(order), Map.of(), false).statusCode());
        assertEquals(404, fixture.merchantClient.post(simulate(order), Map.of()).statusCode());
        try (var anonymous = new HttpAccountClient("http://127.0.0.1:18083")) {
            anonymous.csrf();
            assertEquals(401, anonymous.post(simulate(order), Map.of()).statusCode());
        }
        assertEquals(400, fixture.client.form("/payments/alipay/notify", Map.of("trade_status", "TRADE_SUCCESS", "sign", "anything")).statusCode());
        assertEquals("UNPAID", orderStatus(order));
        jdbc.update("UPDATE payment_attempts SET channel='ALIPAY',app_id='real-app',seller_id='real-seller' WHERE order_id=?", order);
        assertEquals(409, fixture.client.post(simulate(order), Map.of()).statusCode());
        assertEquals(409, fixture.client.post("/orders/" + order + "/payment/sync", Map.of()).statusCode());
        jdbc.update("UPDATE payment_attempts SET status='CLOSED',closed_at=NOW() WHERE order_id=?", order);
        assertEquals(409, fixture.client.post("/orders/" + order + "/payment", Map.of()).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", Integer.class, order));
        assertEquals("UNPAID", orderStatus(order));
        assertEquals(0, earned(order));
        snapshot("formal-channel-rejected", order);
    }

    @Test
    void cancelledExpiredClosedAndMissingAttemptsCannotPay() throws Exception {
        long absent = fixture.order("sim-no-attempt");
        assertEquals(409, fixture.client.post(simulate(absent), Map.of()).statusCode());
        long cancelled = started("sim-cancelled");
        var cancel = fixture.client.post("/orders/" + cancelled + "/cancel", Map.of());
        assertEquals(200, cancel.statusCode(), cancel.body());
        assertEquals(409, fixture.client.post(simulate(cancelled), Map.of()).statusCode());
        long expired = started("sim-expired");
        jdbc.update("UPDATE orders SET created_at=DATE_SUB(NOW(),INTERVAL 1 HOUR),pay_expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", expired);
        assertEquals(409, fixture.client.post(simulate(expired), Map.of()).statusCode());
        long closed = started("sim-closed");
        jdbc.update("UPDATE payment_attempts SET status='CLOSED',closed_at=NOW() WHERE order_id=?", closed);
        assertEquals(409, fixture.client.post(simulate(closed), Map.of()).statusCode());
        assertEquals(0, earned(cancelled) + earned(expired) + earned(closed));
        snapshot("cancelled", cancelled);
        snapshot("expired", expired);
        snapshot("closed", closed);
    }

    @Test
    void refundUsesPersistentSimulationRequestAndReversesPointsOnce() throws Exception {
        long order = started("sim-refund");
        assertEquals(200, fixture.client.post(simulate(order), Map.of()).statusCode());
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        var applied = fixture.client.request("POST", "/orders/" + order + "/after-sales",
                Map.of("type", "REFUND_ONLY", "reason", "模拟退款测试", "items", List.of(Map.of("orderItemId", item, "quantity", 1))),
                true, Map.of("Idempotency-Key", "sim-refund"));
        assertEquals(200, applied.statusCode(), applied.body());
        long sale = json.readTree(applied.body()).path("data").path("id").asLong();
        String merchant = "/merchant/shops/" + fixture.catalog.shop + "/after-sales/" + sale;
        var review = fixture.merchantClient.post(merchant + "/review", Map.of("approved", true));
        assertEquals(200, review.statusCode(), review.body());
        for (int i = 0; i < 2; i++) {
            var refunded = fixture.merchantClient.post(merchant + "/refund", Map.of());
            assertEquals(200, refunded.statusCode(), refunded.body());
            assertTrue(refunded.body().contains("REFUNDED"), refunded.body());
            assertTrue(refunded.body().contains("LOCAL_SIMULATION"), refunded.body());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts WHERE case_id=? AND status='SUCCESS'", Integer.class, sale));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE target_type='AFTER_SALE' AND target_id=? AND action='REFUND_SUCCEEDED' AND summary LIKE '模拟退款成功%'", Integer.class, sale));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM member_point_entries WHERE order_id=? AND event_kind='REFUND_REVERSE'", Integer.class, order));
        snapshot("refunded", order);
    }

    String orderStatus(long order) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, order);
    }

    int earned(long order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM member_point_entries WHERE order_id=? AND event_kind='EARN'", Integer.class, order);
    }

    void snapshot(String stage, long order) throws Exception {
        var data = new LinkedHashMap<String, Object>();
        data.put("stage", stage);
        data.put("orders", jdbc.queryForList("SELECT id,user_id,status,total_amount,created_at,paid_at,pay_expires_at FROM orders WHERE id=?", order));
        data.put("payments", jdbc.queryForList("SELECT id,order_id,channel,out_trade_no,alipay_trade_no,amount,status,paid_at,closed_at FROM payment_attempts WHERE order_id=?", order));
        data.put("points", jdbc.queryForList("SELECT id,user_id,event_kind,operation_key,available_delta,available_after FROM member_point_entries WHERE order_id=?", order));
        data.put("audit", jdbc.queryForList("SELECT action,target_id,summary FROM audit_events WHERE target_type='ORDER' AND target_id=?", order));
        Path dir = Path.of(System.getProperty("simulation.evidence.dir", "target/simulation-evidence"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(stage + "-" + order + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }
}
