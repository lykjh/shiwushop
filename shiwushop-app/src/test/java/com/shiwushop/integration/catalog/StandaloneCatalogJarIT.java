package com.shiwushop.integration.catalog;

import com.shiwushop.support.CommerceFixture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 显式运行的独立 JAR 外部 HTTP 烟测，地址及专库由执行环境提供。 */
class StandaloneCatalogJarIT {
    @Test
    void packagedServerKeepsOldOrdersAndGuardsNewCatalogPromises() throws Exception {
        String base = Objects.requireNonNull(System.getenv("SHOP_STANDALONE_BASE"));
        DriverManagerDataSource data = new DriverManagerDataSource(
                Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_URL")),
                Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")),
                Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
        JdbcTemplate jdbc = new JdbcTemplate(data);
        ObjectMapper json = new ObjectMapper();
        try (CommerceFixture fixture = new CommerceFixture(jdbc, base)) {
            long order = fixture.order("jar-old-order");
            var stopped = fixture.merchantClient.request("PUT", "/merchant/products/" + fixture.product
                    + "/skus/" + fixture.sku + "/status", Map.of("status", "STOPPED"));
            assertEquals(200, stopped.statusCode(), stopped.body());
            assertEquals(409, fixture.client.post("/checkouts/direct",
                    fixture.direct("jar-new-order", 1, "19.90")).statusCode());
            var offSale = fixture.merchantClient.request("PUT", "/merchant/products/" + fixture.product + "/status",
                    Map.of("status", "OFF_SALE"));
            assertEquals(200, offSale.statusCode(), offSale.body());
            long revision = jdbc.queryForObject("SELECT revision FROM products WHERE id=?", Long.class,
                    fixture.product);
            String archive = "/merchant/products/" + fixture.product + "/archive";
            assertEquals(409, fixture.merchantClient.post(archive, Map.of("revision", revision)).statusCode());
            jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", order);
            var archived = fixture.merchantClient.post(archive, Map.of("revision", revision));
            assertEquals(200, archived.statusCode(), archived.body());
            assertEquals("ARCHIVED", json.readTree(archived.body()).path("data").path("status").asString());
            assertEquals(404, fixture.client.get("/products/" + fixture.product).statusCode());
            var restored = fixture.merchantClient.post("/merchant/products/" + fixture.product + "/restore", Map.of());
            assertEquals(200, restored.statusCode(), restored.body());
            assertEquals("DRAFT", json.readTree(restored.body()).path("data").path("status").asString());
        }
    }
}
