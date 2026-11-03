package com.shiwushop.system.catalog;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.SystemEnvironment;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMovementSystemIT {
    @Test void checkoutMovementsArePaginatedForOwnerAndHiddenFromBuyer() throws Exception {
        String base = SystemEnvironment.base();
        var jdbc = SystemEnvironment.database();
        var json = new ObjectMapper();
        try (var fixture = new CommerceFixture(jdbc, base)) {
            fixture.order("inventory-system-first");
            fixture.order("inventory-system-second");
            String path = "/merchant/shops/" + fixture.catalog.shop + "/skus/" + fixture.sku + "/inventory-movements";
            var firstResponse = fixture.merchantClient.get(path + "?size=1&reason=ORDER_DEDUCT");
            assertEquals(200, firstResponse.statusCode(), firstResponse.body());
            var first = json.readTree(firstResponse.body()).path("data");
            assertEquals(1, first.path("items").size());
            assertEquals("ORDER_DEDUCT", first.path("items").get(0).path("reason").asString());
            assertFalse(first.path("items").get(0).has("operationKey"));
            assertTrue(first.path("hasMore").asBoolean());
            long firstId = first.path("items").get(0).path("id").asLong();
            var secondResponse = fixture.merchantClient.get(path + "?size=1&reason=ORDER_DEDUCT&afterId=" + first.path("nextCursor").asLong());
            assertEquals(200, secondResponse.statusCode(), secondResponse.body());
            var second = json.readTree(secondResponse.body()).path("data");
            assertEquals(1, second.path("items").size());
            assertTrue(second.path("items").get(0).path("id").asLong() < firstId);
            assertFalse(second.path("hasMore").asBoolean());
            assertEquals(404, fixture.client.get(path).statusCode());
            assertEquals(18, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
        }
    }
}
