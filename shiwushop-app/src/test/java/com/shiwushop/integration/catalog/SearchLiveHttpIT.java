package com.shiwushop.integration.catalog;

import com.shiwushop.support.CatalogFixture;
import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.autoconfigure.exclude=",
        "shop.search.cursor-secret=local-search-test-secret-at-least-32-bytes"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchLiveHttpIT {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    CatalogFixture fixture;
    HttpAccountClient client;
    long first, second;

    @BeforeEach
    void prepare() {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(schema);
        assertTrue(schema.startsWith("shiwushop_catalog_"), schema);
        fixture = new CatalogFixture(jdbc);
        client = new HttpAccountClient("http://127.0.0.1:" + port);
        first = fixture.product("竹编灯", "ON_SALE", "19.90", 3);
        second = fixture.product("竹编落地灯", "ON_SALE", "29.90", 2);
    }

    @AfterEach
    void cleanup() {
        if (client != null)
            client.close();
        if (fixture != null)
            fixture.close();
    }

    @Test
    void rankedKeywordPaginationKeepsExactMatchFirstAndUsesCurrentVisibility() throws Exception {
        String path = "/discovery/products?shopId=" + fixture.shop + "&q=" + encode("竹编灯");
        var firstPage = data(path + "&size=1");
        assertEquals(List.of(first), ids(firstPage.path("items")));
        assertTrue(firstPage.path("hasMore").asBoolean());
        String cursor = firstPage.path("nextCursor").asString();
        assertEquals(List.of(second), ids(data(path + "&size=1&cursor=" + encode(cursor)).path("items")));
        assertEquals(400, client.get(path + "&size=2&cursor=" + encode(cursor)).statusCode());
        assertEquals(400, client.get(path + "&size=1&cursor=" + encode(cursor + "a")).statusCode());

        jdbc.update("UPDATE products SET status='OFF_SALE' WHERE id=?", first);
        assertEquals(List.of(second), ids(data(path).path("items")));
        var refreshed = data("/discovery/products/refresh?ids=" + first + "," + second + "&q=" + encode("灯"));
        assertEquals(List.of(second), ids(refreshed.path("items")));
        assertEquals(first, refreshed.path("invalidIds").get(0).asLong());
    }

    @Test
    void filtersReadLiveSkusAndRespectPriceAndStock() throws Exception {
        String path = "/discovery/products?shopId=" + fixture.shop + "&q=" + encode("灯");
        assertEquals(List.of(first), ids(data(path + "&maxPrice=20").path("items")));
        jdbc.update("UPDATE skus SET stock=0 WHERE product_id=?", first);
        assertTrue(ids(data(path + "&maxPrice=20").path("items")).isEmpty());
        assertEquals(List.of(second, first), ids(data(path).path("items")));
        jdbc.update("UPDATE skus SET status='STOPPED' WHERE product_id=?", second);
        assertEquals(List.of(first), ids(data(path).path("items")));
        jdbc.update("UPDATE products SET status='ARCHIVED' WHERE id=?", first);
        assertTrue(ids(data(path).path("items")).isEmpty());
    }

    @Test
    void brandAndShopNamesParticipateAndSuggestionsRetainJumpPaths() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "松雾工坊" + suffix;
        long brand = fixture.insert("INSERT INTO brands(name,enabled) VALUES (?,1)", name);
        try {
            jdbc.update("UPDATE shops SET name=? WHERE id=?", "竹编旗舰店" + suffix, fixture.shop);
            jdbc.update("INSERT INTO product_brands(product_id,brand_id) VALUES (?,?)", first, brand);
            assertEquals(List.of(first), ids(data("/discovery/products?q=" + encode(name) + "&brandId=" + brand).path("items")));

            var suggestions = data("/search/suggestions?q=" + encode("竹编"));
            assertTrue(ids(suggestions.path("products")).contains(first));
            assertEquals("/shops/" + fixture.shop, suggestions.path("shops").get(0).path("path").asString());
            assertEquals("/brands/" + brand,
                    data("/search/suggestions?q=" + encode(name)).path("brands").get(0).path("path").asString());
            jdbc.update("UPDATE shops SET enabled=0 WHERE id=?", fixture.shop);
            assertTrue(ids(data("/discovery/products?q=" + encode(name) + "&brandId=" + brand).path("items")).isEmpty());
        } finally {
            jdbc.update("DELETE FROM product_brands WHERE product_id=?", first);
            jdbc.update("DELETE FROM brands WHERE id=?", brand);
        }
    }

    private JsonNode data(String path) throws Exception {
        var response = client.get(path);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("data");
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
