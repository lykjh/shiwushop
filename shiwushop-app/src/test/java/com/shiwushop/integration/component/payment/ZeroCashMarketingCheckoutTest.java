package com.shiwushop.integration.component.payment;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.marketing.enabled=true", "shop.member.enabled=true",
        "shop.member.reward-start-at=2026-11-01T00:00:00", "shop.member.scan-delay-ms=3600000",
        "server.port=18128",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_test4_20261105b"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=28379", "spring.data.redis.database=12",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ZeroCashMarketingCheckoutTest {
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.username",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")));
        properties.add("spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name = "localSimulationGateway") com.shiwushop.trade.payment.LocalSimulationGateway gateway;
    final ObjectMapper json = new ObjectMapper();
    CommerceFixture fixture;
    HttpAccountClient admin, reviewer;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_test4_20261105b"));
        }
        String base = "http://127.0.0.1:" + port;
        fixture = new CommerceFixture(jdbc, base);
        admin = CommerceFixture.login(base, new TestAccounts(jdbc).create("ADMIN", "Shop123!"));
        reviewer = CommerceFixture.login(base, new TestAccounts(jdbc).create("ADMIN", "Shop123!"));
    }

    @AfterEach
    void close() {
        if (admin != null) admin.close();
        if (reviewer != null) reviewer.close();
        if (fixture != null) {
            fixture.client.close();
            fixture.merchantClient.close();
        }
    }

    @Test
    void couponCoversRealCheckoutAndRefundWithoutGatewayRecord() throws Exception {
        // 夹具的在售商品原价即为券面额，避免绕过商品改价需重审的规则。
        var rule = Map.of("faceAmount", new BigDecimal("19.90"), "thresholdAmount", new BigDecimal("19.90"),
                "issueLimit", 1, "perUserLimit", 1, "claimEndAt", "2035-12-01T00:00:00+08:00",
                "validityDays", 30, "grantMode", "COMPENSATION");
        var activityBody = Map.of("name", "零现金交易券", "type", "COUPON",
                "startAt", "2020-01-01T00:00:00+08:00", "endAt", "2035-12-01T00:00:00+08:00",
                "eligibleLevelCodes", List.of("ORDINARY"), "rulesText", "零现金交易验证",
                "scopes", List.of(Map.of("skuId", fixture.sku, "scopeRole", "SALE")), "tiers", List.of(),
                "couponRule", rule,
                "budgetProposals", List.of(Map.of("party", "PLATFORM", "budgetAmount", 100),
                        Map.of("party", "MERCHANT", "shopId", fixture.catalog.shop, "budgetAmount", 100,
                                "platformShareBp", 5000)));
        var created = admin.request("POST", "/admin/marketing/activities", activityBody, true,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, created.statusCode(), created.body());
        long activity = json.readTree(created.body()).path("data").path("id").asLong();
        String path = "/admin/marketing/activities/" + activity;
        var agreement = fixture.merchantClient.request("PUT",
                "/merchant/shops/" + fixture.catalog.shop + "/marketing/activities/" + activity + "/agreement",
                Map.of("platformShareBp", 5000, "merchantBudgetAmount", 100, "reason", "同意承担"), true,
                Map.of("If-Match", tag(path)));
        assertEquals(200, agreement.statusCode(), agreement.body());
        var submitted = admin.request("POST", path + "/submit", null, true, Map.of("If-Match", tag(path)));
        assertEquals(200, submitted.statusCode(), submitted.body());
        var approved = reviewer.request("POST", path + "/decision",
                Map.of("decision", "APPROVE", "reason", "批准"), true, Map.of("If-Match", tag(path)));
        assertEquals(200, approved.statusCode(), approved.body());
        var grant = admin.request("POST", "/admin/marketing/coupon-rules/" + activity + "/grants",
                Map.of("userId", fixture.buyer.id(), "count", 1, "reason", "纠正漏发", "evidenceRef", "隔离交易"),
                true, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, grant.statusCode(), grant.body());
        long coupon = json.readTree(grant.body()).path("data").path("coupons").get(0).path("id").asLong();

        var body = new LinkedHashMap<String, Object>();
        body.put("source", "DIRECT");
        body.put("quantity", 1);
        body.put("skuId", fixture.sku);
        body.put("addressId", fixture.address);
        body.put("shopChoices", List.of(Map.of("shopId", fixture.catalog.shop, "couponMode", "SELECT",
                "userCouponId", coupon)));
        var quote = fixture.client.post("/checkouts/quote", body);
        assertEquals(200, quote.statusCode(), quote.body());
        body.remove("source");
        body.put("quoteHash", json.readTree(quote.body()).path("data").path("quoteHash").asString());
        body.put("confirmedTotal", "0.00");
        body.put("idempotencyKey", UUID.randomUUID().toString());
        var checkout = fixture.client.post("/checkouts/direct", body);
        assertEquals(200, checkout.statusCode(), checkout.body());
        var placed = json.readTree(checkout.body()).path("data").path("orders").get(0);
        long order = placed.path("id").asLong();
        assertEquals("ZERO_CASH", placed.path("settlementKind").asString());
        assertEquals("PAID", placed.path("status").asString());
        assertEquals(0, count("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", order));
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        var sale = fixture.client.request("POST", "/orders/" + order + "/after-sales",
                Map.of("type", "REFUND_ONLY", "reason", "零现金退款",
                        "items", List.of(Map.of("orderItemId", item, "quantity", 1))), true,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(200, sale.statusCode(), sale.body());
        long caseId = json.readTree(sale.body()).path("data").path("id").asLong();
        String salePath = "/merchant/shops/" + fixture.catalog.shop + "/after-sales/" + caseId;
        var reviewed = fixture.merchantClient.post(salePath + "/review", Map.of("approved", true));
        assertEquals(200, reviewed.statusCode(), reviewed.body());
        var refunded = fixture.merchantClient.post(salePath + "/refund", Map.of());
        assertEquals(200, refunded.statusCode(), refunded.body());
        assertEquals(0, count("SELECT COUNT(*) FROM refund_attempts WHERE case_id=?", caseId));
        assertEquals(1, count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND status='AVAILABLE'", coupon));
        assertEquals(20, count("SELECT stock FROM skus WHERE id=?", fixture.sku));
    }

    private String tag(String path) throws Exception {
        return admin.get(path).headers().firstValue("ETag").orElseThrow();
    }

    private long count(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }
}
