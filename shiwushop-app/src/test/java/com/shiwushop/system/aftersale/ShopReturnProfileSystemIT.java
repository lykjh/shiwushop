package com.shiwushop.system.aftersale;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.SystemEnvironment;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 独立应用进程验证私密资料、转店及公开响应。 */
class ShopReturnProfileSystemIT {
    @Test
    void transferMovesPrivateProfileAccessToNewOwner() throws Exception {
        String base = SystemEnvironment.base();
        var jdbc = SystemEnvironment.database();
        var json = new ObjectMapper();
        var fixture = new CommerceFixture(jdbc, base);
        try (var accounts = new TestAccounts(jdbc)) {
            var admin = accounts.create("ADMIN", "Shop123!");
            var next = accounts.create("MERCHANT", "Shop123!");
            try (var adminClient = CommerceFixture.login(base, admin);
                 var nextClient = CommerceFixture.login(base, next)) {
                String path = "/merchant/shops/" + fixture.catalog.shop + "/return-profile";
                var profile = Map.of("contactName", "王明", "contactPhone", "13912345678", "consignee", "张明",
                        "phone", "13812345678", "province", "江苏省", "city", "南京市", "district", "鼓楼区", "detail", "中山北路28号");
                assertEquals(200, fixture.merchantClient.request("PUT", path, profile).statusCode());
                assertEquals("张明", json.readTree(fixture.merchantClient.get(path).body()).path("data").path("consignee").asString());
                assertEquals(200, adminClient.request("PUT", "/admin/shops/" + fixture.catalog.shop + "/owner", Map.of("ownerUserId", next.id())).statusCode());
                assertEquals(404, fixture.merchantClient.get(path).statusCode());
                assertEquals(404, fixture.merchantClient.request("PUT", path, profile).statusCode());
                assertEquals("张明", json.readTree(nextClient.get(path).body()).path("data").path("consignee").asString());
                var changed = new HashMap<>(profile);
                changed.put("consignee", "李华");
                assertEquals(200, nextClient.request("PUT", path, changed).statusCode());
                assertEquals("李华", json.readTree(nextClient.get(path).body()).path("data").path("consignee").asString());
                String publicBody = fixture.client.get("/shops/" + fixture.catalog.shop).body();
                assertFalse(publicBody.contains("李华"));
                assertFalse(publicBody.contains("13812345678"));
                assertFalse(publicBody.contains("中山北路28号"));
            } finally {
                jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?", fixture.catalog.merchant.id(), fixture.catalog.shop);
                jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", fixture.catalog.shop);
            }
        } finally {
            fixture.close();
        }
    }
}
