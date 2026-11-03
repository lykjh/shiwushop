package com.shiwushop.integration.merchant;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShopReturnProfileHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ownerCanCreateAndReadPrivateReturnProfile(CapturedOutput output) throws Exception {
        try (var accounts = new TestAccounts(jdbc)) {
            var owner = accounts.create("MERCHANT", "Shop123!");
            String name = "金陵生活馆" + owner.id();
            jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", owner.id(), name);
            Long shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, name);
            try (var client = CommerceFixture.login("http://127.0.0.1:" + port, owner)) {
                String path = "/merchant/shops/" + shopId + "/return-profile";
                assertEquals(409, client.get(path).statusCode());
                assertEquals(400, client.get("/merchant/shops/-1/return-profile").statusCode());
                assertEquals(400, client.get("/merchant/shops/not-a-number/return-profile").statusCode());
                var ordinary = accounts.create("USER", "Shop123!");
                try (var ordinaryClient = CommerceFixture.login("http://127.0.0.1:" + port, ordinary)) {
                    assertEquals(404, ordinaryClient.get(path).statusCode());
                    assertEquals(404, ordinaryClient.request("PUT", path, profile()).statusCode());
                    jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", ordinary.id(), "普通用户店" + ordinary.id());
                    Long ordinaryShop = jdbc.queryForObject("SELECT id FROM shops WHERE owner_user_id=?", Long.class, ordinary.id());
                    try {
                        String ownPath = "/merchant/shops/" + ordinaryShop + "/return-profile";
                        assertEquals(403, ordinaryClient.get(ownPath).statusCode());
                        assertEquals(403, ordinaryClient.request("PUT", ownPath, profile()).statusCode());
                    } finally { jdbc.update("DELETE FROM shops WHERE id=?", ordinaryShop); }
                }
                assertEquals(403, client.request("PUT", path, profile(), false, Map.of()).statusCode());
                try (var anonymous = new HttpAccountClient("http://127.0.0.1:" + port)) {
                    assertEquals(401, anonymous.get(path).statusCode());
                }
                var saved = client.request("PUT", path, profile());
                assertEquals(200, saved.statusCode(), saved.body());
                var read = client.get(path);
                assertEquals(200, read.statusCode(), read.body());
                assertEquals("张明", json.readTree(read.body()).path("data").path("consignee").asString());
                assertEquals("中山北路28号", json.readTree(read.body()).path("data").path("detail").asString());
                var publicShop = client.get("/shops/" + shopId);
                assertEquals(200, publicShop.statusCode());
                assertFalse(publicShop.body().contains("张明"));
                assertFalse(publicShop.body().contains("13812345678"));
                assertFalse(publicShop.body().contains("中山北路28号"));
                var stored = jdbc.queryForMap("SELECT * FROM shop_return_profiles WHERE shop_id=?", shopId);
                assertFalse(new String((byte[]) stored.get("consignee_ciphertext"), StandardCharsets.UTF_8).contains("张明"));
                assertFalse(new String((byte[]) stored.get("detail_ciphertext"), StandardCharsets.UTF_8).contains("中山北路28号"));
                for (String column : new String[]{"contact_name_ciphertext", "contact_phone_ciphertext", "consignee_ciphertext", "phone_ciphertext", "detail_ciphertext"}) {
                    assertTrue(((byte[]) stored.get(column)).length > 28, column);
                }
                var maximum = new HashMap<>(profile());
                maximum.put("contactName", "王".repeat(20));
                maximum.put("consignee", "张".repeat(20));
                maximum.put("province", "省".repeat(64));
                maximum.put("city", "市".repeat(64));
                maximum.put("district", "区".repeat(64));
                maximum.put("detail", "路".repeat(255));
                assertEquals(200, client.request("PUT", path, maximum).statusCode());
                assertEquals("路".repeat(255), json.readTree(client.get(path).body()).path("data").path("detail").asString());
                assertEquals(200, client.request("PUT", path, profile()).statusCode());
                var invalid = new HashMap<>(profile());
                invalid.put("phone", "123");
                assertEquals(400, client.request("PUT", path, invalid).statusCode());
                assertEquals("张明", json.readTree(client.get(path).body()).path("data").path("consignee").asString());
                var other = accounts.create("MERCHANT", "Shop123!");
                try (var otherClient = CommerceFixture.login("http://127.0.0.1:" + port, other)) {
                    assertEquals(404, otherClient.get(path).statusCode());
                    assertEquals(404, otherClient.request("PUT", path, profile()).statusCode());
                    jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?", other.id(), shopId);
                    assertEquals(404, client.get(path).statusCode());
                    assertEquals(404, client.request("PUT", path, profile()).statusCode());
                    assertEquals(200, otherClient.get(path).statusCode());
                    var replacement = new HashMap<>(profile());
                    replacement.put("consignee", "李华");
                    assertEquals(200, otherClient.request("PUT", path, replacement).statusCode());
                    assertEquals("李华", json.readTree(otherClient.get(path).body()).path("data").path("consignee").asString());
                }
                assertFalse(output.getAll().contains("张明"));
                assertFalse(output.getAll().contains("13912345678"));
                assertFalse(output.getAll().contains("中山北路28号"));
            } finally {
                // 红灯阶段尚无私密表；先检查存在性，保证首个行为失败不会破坏清理。
                Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name='shop_return_profiles'", Integer.class);
                if (exists == 1) jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", shopId);
                jdbc.update("DELETE FROM shops WHERE id=?", shopId);
            }
        }
    }

    static Map<String, Object> profile() {
        return Map.of("contactName", "经营联系人", "contactPhone", "13912345678", "consignee", "张明",
                "phone", "13812345678", "province", "江苏省", "city", "南京市", "district", "鼓楼区",
                "detail", "中山北路28号");
    }
}
