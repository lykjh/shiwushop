package com.shiwushop.integration.commerce;

import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AfterSaleWithdrawalHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SdkAlipayGateway gateway;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buyerCanWithdrawRequestedAndReapply() throws Exception {
        PaymentHttpFixture.configure(gateway);
        try (var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port)) {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            var body = Map.of("type", "REFUND_ONLY", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "withdraw-first"));
            assertEquals(200, created.statusCode(), created.body());
            long sale = json.readTree(created.body()).path("data").path("id").asLong();
            try (var accounts = new TestAccounts(jdbc)) {
                var outsider = accounts.create("USER", "Shop123!");
                try (var other = CommerceFixture.login("http://127.0.0.1:" + port, outsider)) {
                    assertEquals(404, other.post("/after-sales/" + sale + "/withdraw", Map.of()).statusCode());
                }
            }
            int stock = jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku);
            var response = fixture.client.post("/after-sales/" + sale + "/withdraw", Map.of());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("CLOSED", json.readTree(response.body()).path("data").path("status").asString());
            assertEquals(200, fixture.client.post("/after-sales/" + sale + "/withdraw", Map.of()).statusCode());
            assertEquals(stock, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts WHERE case_id=?", Integer.class, sale));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE target_type='AFTER_SALE' AND target_id=? AND action='AFTER_SALE_WITHDRAWN'", Integer.class, sale));
            assertTrue(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM after_sale_cases WHERE id=?", Boolean.class, sale));
            var again = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "withdraw-second"));
            assertEquals(200, again.statusCode(), again.body());
        }
    }
    @Test
    void returnApprovalRequiresDestination() throws Exception {
        PaymentHttpFixture.configure(gateway);
        try (var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port)) {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            String merchant = "/merchant/shops/" + fixture.catalog.shop;
            assertEquals(200, fixture.merchantClient.post(merchant + "/orders/" + order + "/shipment",
                    Map.of("carrierName", "顺丰", "trackingNo", "SF10001")).statusCode());
            var body = Map.of("type", "RETURN_REFUND", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "return-request"));
            assertEquals(200, created.statusCode(), created.body());
            long sale = json.readTree(created.body()).path("data").path("id").asLong();
            var review = fixture.merchantClient.post(merchant + "/after-sales/" + sale + "/review", Map.of("approved", true));
            assertEquals(409, review.statusCode(), review.body());
            assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?", String.class, sale));
        }
    }
    @Test
    void approvedReturnKeepsPrivateDestinationAfterProfileChangeAndTransfer(CapturedOutput output) throws Exception {
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        long sale = 0;
        try (var accounts = new TestAccounts(jdbc)) {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            String merchant = "/merchant/shops/" + fixture.catalog.shop;
            assertEquals(200, fixture.merchantClient.post(merchant + "/orders/" + order + "/shipment",
                    Map.of("carrierName", "顺丰", "trackingNo", "SF10002")).statusCode());
            var profile = Map.of("contactName", "王明", "contactPhone", "13912345678", "consignee", "张明",
                    "phone", "13812345678", "province", "江苏省", "city", "南京市", "district", "鼓楼区", "detail", "中山北路28号");
            assertEquals(200, fixture.merchantClient.request("PUT", merchant + "/return-profile", profile).statusCode());
            var body = Map.of("type", "RETURN_REFUND", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "return-snapshot"));
            assertEquals(200, created.statusCode(), created.body());
            sale = json.readTree(created.body()).path("data").path("id").asLong();
            String path = "/after-sales/" + sale;
            var review = fixture.merchantClient.post(merchant + path + "/review", Map.of("approved", true));
            assertEquals(200, review.statusCode(), review.body());
            assertEquals("张明", json.readTree(review.body()).path("data").path("returnDestination").path("consignee").asString());
            var changed = new java.util.HashMap<>(profile);
            changed.put("consignee", "李华");
            changed.put("detail", "珠江路88号");
            assertEquals(200, fixture.merchantClient.request("PUT", merchant + "/return-profile", changed).statusCode());
            assertEquals("张明", json.readTree(fixture.client.get(path).body()).path("data").path("returnDestination").path("consignee").asString());
            assertEquals("中山北路28号", json.readTree(fixture.client.get(path).body()).path("data").path("returnDestination").path("detail").asString());
            var stored = jdbc.queryForMap("SELECT * FROM after_sale_return_destinations WHERE case_id=?", sale);
            assertTrue(((byte[]) stored.get("consignee_ciphertext")).length > 28);
            assertFalse(new String((byte[]) stored.get("detail_ciphertext"), java.nio.charset.StandardCharsets.UTF_8).contains("中山北路28号"));
            var admin = accounts.create("ADMIN", "Shop123!");
            var next = accounts.create("MERCHANT", "Shop123!");
            var stranger = accounts.create("USER", "Shop123!");
            try (var adminClient = CommerceFixture.login("http://127.0.0.1:" + port, admin);
                 var nextClient = CommerceFixture.login("http://127.0.0.1:" + port, next);
                 var strangerClient = CommerceFixture.login("http://127.0.0.1:" + port, stranger)) {
                assertEquals("张明", json.readTree(adminClient.get("/admin" + path).body()).path("data").path("returnDestination").path("consignee").asString());
                assertEquals(403, strangerClient.get("/admin" + path).statusCode());
                assertEquals(404, strangerClient.get(path).statusCode());
                jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?", next.id(), fixture.catalog.shop);
                assertEquals(404, fixture.merchantClient.get(merchant + path).statusCode());
                assertEquals("张明", json.readTree(nextClient.get(merchant + path).body()).path("data").path("returnDestination").path("consignee").asString());
            } finally {
                jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?", fixture.catalog.merchant.id(), fixture.catalog.shop);
            }
            assertFalse(output.getAll().contains("张明"));
            assertFalse(output.getAll().contains("13812345678"));
            assertFalse(output.getAll().contains("中山北路28号"));
        } finally {
            if (sale != 0) jdbc.update("DELETE FROM after_sale_return_destinations WHERE case_id=?", sale);
            jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", fixture.catalog.shop);
            fixture.close();
        }
    }
    @Test
    void withdrawalStateAndShipmentRules() throws Exception {
        PaymentHttpFixture.configure(gateway);
        try (var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port)) {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            String merchant = "/merchant/shops/" + fixture.catalog.shop;
            assertEquals(200, fixture.merchantClient.post(merchant + "/orders/" + order + "/shipment",
                    Map.of("carrierName", "顺丰", "trackingNo", "SF10004")).statusCode());
            var body = Map.of("type", "RETURN_REFUND", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "withdraw-status"));
            long sale = json.readTree(created.body()).path("data").path("id").asLong();
            String path = "/after-sales/" + sale + "/withdraw";
            for (String status : List.of("REJECTED", "WAITING_RECEIPT", "REFUNDING", "REFUNDED")) {
                jdbc.update("UPDATE after_sale_cases SET status=? WHERE id=?", status, sale);
                assertEquals(409, fixture.client.post(path, Map.of()).statusCode(), status);
                assertEquals(status, jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?", String.class, sale));
            }
            jdbc.update("UPDATE after_sale_cases SET status='WAITING_RETURN' WHERE id=?", sale);
            jdbc.update("INSERT INTO return_shipments(case_id,carrier_name,tracking_no,sent_at) VALUES (?,?,?,NOW())", sale, "顺丰", "SF10003");
            assertEquals(409, fixture.client.post(path, Map.of()).statusCode());
            jdbc.update("DELETE FROM return_shipments WHERE case_id=?", sale);
            Long payment = jdbc.queryForObject("SELECT id FROM payment_attempts WHERE order_id=? AND status='SUCCESS'", Long.class, order);
            jdbc.update("INSERT INTO refund_attempts(case_id,payment_attempt_id,out_request_no,amount,status,requested_at) VALUES (?,?,?,19.90,'PENDING',NOW())", sale, payment, "withdraw-test-" + sale);
            assertEquals(409, fixture.client.post(path, Map.of()).statusCode());
            jdbc.update("DELETE FROM refund_attempts WHERE case_id=?", sale);
            assertEquals(200, fixture.client.post(path, Map.of()).statusCode());
            assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?", String.class, sale));
        }
    }
}
