package com.shiwushop.integration.catalog;

import com.shiwushop.support.CatalogFixture;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryMovementHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();
    CatalogFixture catalog;
    HttpAccountClient merchant;
    long sku;
    String path;

    @BeforeEach void prepare() throws Exception {
        catalog = new CatalogFixture(jdbc);
        long product = catalog.product("测试商品", "ON_SALE", "19.90", 20);
        sku = catalog.sku(product);
        merchant = CommerceFixture.login("http://127.0.0.1:" + port, catalog.merchant);
        path = "/merchant/shops/" + catalog.shop + "/skus/" + sku + "/inventory-movements";
    }

    @AfterEach void cleanup() {
        merchant.close();
        jdbc.update("DELETE FROM inventory_movements WHERE sku_id=?", sku);
        catalog.close();
    }

    long movement(String reason, String time) {
        String key = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO inventory_movements(sku_id,reason,delta,balance_after,operation_key,created_at) VALUES (?,?,1,20,?,?)",
                sku, reason, key, time);
        return jdbc.queryForObject("SELECT id FROM inventory_movements WHERE operation_key=?", Long.class, key);
    }

    @Test void ownerSeesEmptyMovementPageForOwnSku() throws Exception {
        var response = merchant.get(path);
        assertEquals(200, response.statusCode(), response.body());
        var data = json.readTree(response.body()).path("data");
        assertEquals(0, data.path("items").size());
        assertFalse(data.path("hasMore").asBoolean());
    }

    @Test void readsAllReasonsAndNeverReturnsOperationKeyOrChangesStock() throws Exception {
        for (String reason : new String[]{"ORDER_DEDUCT", "ORDER_CANCEL_RETURN", "REFUND_RETURN", "MERCHANT_ADJUST"}) {
            movement(reason, "2026-11-03 10:00:00");
        }
        var response = merchant.get(path);
        assertEquals(200, response.statusCode(), response.body());
        var items = json.readTree(response.body()).path("data").path("items");
        assertEquals(4, items.size());
        assertEquals("MERCHANT_ADJUST", items.get(0).path("reason").asString());
        assertEquals("ORDER_DEDUCT", items.get(3).path("reason").asString());
        assertFalse(items.get(0).has("operationKey"));
        assertEquals(20, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, sku));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?", Integer.class, sku));
    }

    @Test void filtersByReasonAndHalfOpenTimeRange() throws Exception {
        movement("ORDER_DEDUCT", "2026-11-03 09:59:59");
        long included = movement("ORDER_DEDUCT", "2026-11-03 10:00:00");
        movement("MERCHANT_ADJUST", "2026-11-03 10:00:00");
        movement("ORDER_DEDUCT", "2026-11-03 11:00:00");
        String from = URLEncoder.encode("2026-11-03T10:00:00", StandardCharsets.UTF_8);
        String to = URLEncoder.encode("2026-11-03T11:00:00", StandardCharsets.UTF_8);
        var response = merchant.get(path + "?reason=ORDER_DEDUCT&createdFrom=" + from + "&createdTo=" + to);
        assertEquals(200, response.statusCode(), response.body());
        var items = json.readTree(response.body()).path("data").path("items");
        assertEquals(1, items.size());
        assertEquals(included, items.get(0).path("id").asLong());
    }

    @Test void cursorPagesSameTimeRowsAndIgnoresNewerInsert() throws Exception {
        long oldest = movement("MERCHANT_ADJUST", "2026-11-03 10:00:00");
        long middle = movement("MERCHANT_ADJUST", "2026-11-03 10:00:00");
        long newest = movement("MERCHANT_ADJUST", "2026-11-03 10:00:00");
        var first = json.readTree(merchant.get(path + "?size=2").body()).path("data");
        assertTrue(first.path("hasMore").asBoolean());
        assertEquals(newest, first.path("items").get(0).path("id").asLong());
        assertEquals(middle, first.path("nextCursor").asLong());
        movement("MERCHANT_ADJUST", "2026-11-03 10:00:00");
        var second = json.readTree(merchant.get(path + "?size=2&afterId=" + middle).body()).path("data");
        assertEquals(1, second.path("items").size());
        assertEquals(oldest, second.path("items").get(0).path("id").asLong());
        assertFalse(second.path("hasMore").asBoolean());
    }

    @Test void hidesOtherShopAndSku() throws Exception {
        try (var other = new CatalogFixture(jdbc)) {
            long product = other.product("其他商品", "ON_SALE", "10.00", 5);
            long otherSku = other.sku(product);
            assertEquals(404, merchant.get("/merchant/shops/" + other.shop + "/skus/" + otherSku + "/inventory-movements").statusCode());
            assertEquals(404, merchant.get("/merchant/shops/" + catalog.shop + "/skus/" + otherSku + "/inventory-movements").statusCode());
        }
    }
}
