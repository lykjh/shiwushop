package com.shiwushop.system.aftersale;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.SystemEnvironment;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 独立应用进程验证撤销、退货审核与历史目的地。 */
class AfterSaleWithdrawalSystemIT {
    @Test
    void withdrawAndReviewReturnAgainstPrivateSnapshot() throws Exception {
        String base = SystemEnvironment.base();
        var jdbc = SystemEnvironment.database();
        var json = new ObjectMapper();
        var fixture = new CommerceFixture(jdbc, base);
        try (var accounts = new TestAccounts(jdbc)) {
            var admin = accounts.create("ADMIN", "Shop123!");
            try (var adminClient = CommerceFixture.login(base, admin)) {
                long order = fixture.order("system-after-sale");
                jdbc.update("UPDATE orders SET status='PAID',paid_at=NOW() WHERE id=?", order);
                jdbc.update("INSERT INTO payment_attempts(order_id,channel,out_trade_no,alipay_trade_no,app_id,seller_id,amount,status,paid_at) VALUES (?,'ALIPAY_SANDBOX',?,?, 'system-app','system-seller',19.90,'SUCCESS',NOW())", order, "system-pay-" + order, "system-trade-" + order);
                Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
                var refundBody = Map.of("type", "REFUND_ONLY", "reason", "尺寸不合适",
                        "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
                var first = fixture.client.request("POST", "/orders/" + order + "/after-sales", refundBody, true, Map.of("Idempotency-Key", "system-withdraw"));
                assertEquals(200, first.statusCode(), first.body());
                long sale = json.readTree(first.body()).path("data").path("id").asLong();
                String path = "/after-sales/" + sale;
                assertEquals("CLOSED", json.readTree(fixture.client.post(path + "/withdraw", Map.of()).body()).path("data").path("status").asString());
                assertEquals(200, fixture.client.post(path + "/withdraw", Map.of()).statusCode());
                String merchant = "/merchant/shops/" + fixture.catalog.shop;
                assertEquals(200, fixture.merchantClient.post(merchant + "/orders/" + order + "/shipment", Map.of("carrierName", "顺丰", "trackingNo", "SF-SYSTEM-1")).statusCode());
                var returnBody = Map.of("type", "RETURN_REFUND", "reason", "尺寸不合适",
                        "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
                var second = fixture.client.request("POST", "/orders/" + order + "/after-sales", returnBody, true, Map.of("Idempotency-Key", "system-return"));
                assertEquals(200, second.statusCode(), second.body());
                long returned = json.readTree(second.body()).path("data").path("id").asLong();
                String returnPath = "/after-sales/" + returned;
                assertEquals(409, fixture.merchantClient.post(merchant + returnPath + "/review", Map.of("approved", true)).statusCode());
                var profile = Map.of("contactName", "王明", "contactPhone", "13912345678", "consignee", "张明",
                        "phone", "13812345678", "province", "江苏省", "city", "南京市", "district", "鼓楼区", "detail", "中山北路28号");
                assertEquals(200, fixture.merchantClient.request("PUT", merchant + "/return-profile", profile).statusCode());
                assertEquals(200, fixture.merchantClient.post(merchant + returnPath + "/review", Map.of("approved", true)).statusCode());
                var changed = new HashMap<>(profile);
                changed.put("consignee", "李华");
                assertEquals(200, fixture.merchantClient.request("PUT", merchant + "/return-profile", changed).statusCode());
                assertEquals("张明", json.readTree(fixture.client.get(returnPath).body()).path("data").path("returnDestination").path("consignee").asString());
                assertEquals("张明", json.readTree(adminClient.get("/admin" + returnPath).body()).path("data").path("returnDestination").path("consignee").asString());
            }
        } finally {
            jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", fixture.catalog.shop);
            fixture.close();
        }
    }
}
