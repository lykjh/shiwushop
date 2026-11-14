package com.shiwushop.integration.settlement;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.autoconfigure.exclude=", "server.port=18128", "shop.member.enabled=false",
        "shop.marketing.enabled=false", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=28379", "spring.data.redis.database=12"})
class ManualSettlementHttpTest {
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.username",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")));
        properties.add("spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }

    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();
    CommerceFixture fixture;
    TestAccounts admins;
    TestAccounts.Account admin;

    @BeforeEach
    void prepare() throws Exception {
        try (var db = jdbc.getDataSource().getConnection()) {
            assertTrue(db.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"));
        }
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:18128");
        admins = new TestAccounts(jdbc);
        admin = admins.create("ADMIN", "Shop123!");
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            for (long id : jdbc.queryForList("SELECT id FROM manual_settlements WHERE created_by_user_id=?",
                    Long.class, admin.id())) {
                jdbc.update("DELETE FROM manual_settlement_adjustments WHERE settlement_id=?", id);
                jdbc.update("DELETE FROM manual_settlement_lines WHERE settlement_id=?", id);
                jdbc.update("DELETE FROM audit_events WHERE target_type='MANUAL_SETTLEMENT' AND target_id=?", id);
                jdbc.update("DELETE FROM manual_settlements WHERE id=?", id);
            }
            fixture.close();
            admins.close();
        }
    }

    @Test
    void adminCashPayoutAndMerchantPrivateReadAcrossRealHttpRoutes() throws Exception {
        long order = fixture.order("manual-http-" + System.nanoTime());
        jdbc.update("UPDATE orders SET status='PAID',paid_at=NOW() WHERE id=?", order);
        jdbc.update("INSERT INTO payment_attempts(order_id,channel,out_trade_no,app_id,seller_id,amount,status,paid_at) "
                + "VALUES (?,'ALIPAY_SANDBOX',?,'test-app','test-seller',19.90,'SUCCESS',NOW())",
                order, "manual-http-pay-" + order);
        assertEquals(200, fixture.merchantClient.post("/merchant/shops/" + fixture.catalog.shop
                + "/orders/" + order + "/shipment", Map.of("carrierName", "SF",
                "trackingNo", "SF-SETTLEMENT-" + order)).statusCode());
        assertEquals(200, fixture.client.post("/orders/" + order + "/shipment/confirm", Map.of()).statusCode());
        jdbc.update("UPDATE orders SET fully_received_at=DATE_SUB(NOW(), INTERVAL 8 DAY) WHERE id=?", order);
        assertEquals(403, fixture.client.get("/admin/manual-settlements/candidates?shopId="
                + fixture.catalog.shop).statusCode());
        try (var client = CommerceFixture.login("http://127.0.0.1:18128", admin)) {
            assertEquals(200, client.get("/admin/manual-settlements/candidates?shopId="
                    + fixture.catalog.shop).statusCode());
            var created = client.post("/admin/manual-settlements", Map.of(
                    "requestKey", "http-create-" + order, "shopId", fixture.catalog.shop,
                    "payeeUserId", fixture.catalog.merchant.id(), "orders", List.of(Map.of(
                            "orderId", order, "ownershipEvidence", "signed merchant claim"))));
            assertEquals(200, created.statusCode(), created.body());
            long id = json.readTree(created.body()).path("data").path("id").asLong();
            String path = "/admin/manual-settlements/" + id;
            assertEquals(200, client.post(path + "/approve", Map.of("requestKey", "http-approve")).statusCode());
            assertEquals(200, client.post(path + "/processing", Map.of("requestKey", "http-process")).statusCode());
            var paid = client.post(path + "/payment", Map.of("requestKey", "http-pay",
                    "actualAmount", "19.90", "paidAt", LocalDateTime.now().minusMinutes(1).toString(),
                    "method", "BANK_TRANSFER", "externalReference", "offline-ref-" + order,
                    "payeeUserId", fixture.catalog.merchant.id()));
            assertEquals(200, paid.statusCode(), paid.body());
            assertEquals("PAID", json.readTree(paid.body()).path("data").path("status").asString());
            var correction = client.post(path + "/corrections", Map.of(
                    "requestKey", "http-evidence", "kind", "EVIDENCE",
                    "replacementProofReference", "corrected-proof-" + order,
                    "reason", "proof image corrected", "evidenceReference", "audit-proof-" + order));
            assertEquals(200, correction.statusCode(), correction.body());
            assertEquals(1, json.readTree(correction.body()).path("data").path("adjustments").size());
            assertEquals("corrected-proof-" + order, json.readTree(correction.body()).path("data")
                    .path("adjustments").get(0).path("replacementProofReference").asString());
            assertEquals(200, fixture.merchantClient.get("/merchant/manual-settlements/" + id).statusCode());
            assertEquals(404, fixture.client.get("/merchant/manual-settlements/" + id).statusCode());
            var newOwner = admins.create("MERCHANT", "Shop123!");
            jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?", newOwner.id(), fixture.catalog.shop);
            try (var other = CommerceFixture.login("http://127.0.0.1:18128", newOwner)) {
                assertEquals(404, other.get("/merchant/manual-settlements/" + id).statusCode());
                assertEquals(200, fixture.merchantClient.get("/merchant/manual-settlements/" + id).statusCode());
            } finally {
                jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?",
                        fixture.catalog.merchant.id(), fixture.catalog.shop);
            }
            assertEquals("PAID", jdbc.queryForObject("SELECT status FROM manual_settlements WHERE id=?",
                    String.class, id));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM manual_settlement_lines WHERE settlement_id=?",
                    Integer.class, id));
        }
    }
}
