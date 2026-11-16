package com.shiwushop.integration.catalog;

import com.shiwushop.support.CatalogFixture;
import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 显式运行的串行目录功能基线；只使用独占的目录测试库。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.search.cursor-secret=local-large-search-secret-at-least-32-bytes"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogLargeSearchIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void tenThousandProductsHaveCompleteCursorAndSparseShopResults() throws Exception {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(schema);
        assertTrue(schema.startsWith("shiwushop_catalog_"), schema);
        CatalogFixture dense = new CatalogFixture(jdbc);
        CatalogFixture sparse = new CatalogFixture(jdbc);
        try (HttpAccountClient client = new HttpAccountClient("http://127.0.0.1:" + port)) {
            String prefix = "D2CAT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            try {
                for (int start = 0; start < 10_000; start += 500) {
                    StringBuilder sql = new StringBuilder("INSERT INTO products(shop_id,category_id,name,status) VALUES ");
                    List<Object> args = new ArrayList<>();
                    for (int n = start; n < start + 500; n++) {
                        if (n > start) sql.append(',');
                        sql.append("(?,?,?,'ON_SALE')");
                        args.add(dense.shop);
                        args.add(dense.category);
                        args.add(prefix + '-' + n);
                    }
                    jdbc.update(sql.toString(), args.toArray());
                }
                jdbc.update("INSERT INTO skus(product_id,option_signature,price,stock) "
                        + "SELECT id,LPAD(HEX(id),64,'0'),19.90,1 FROM products WHERE shop_id=?", dense.shop);
                for (int n = 0; n < 10; n++)
                    sparse.product(prefix + "-sparse-" + n, "ON_SALE", "19.90", 1);

                List<Long> denseIds = jdbc.queryForList("SELECT id FROM products WHERE shop_id=? ORDER BY id",
                        Long.class, dense.shop);
                List<Long> sparseIds = jdbc.queryForList("SELECT id FROM products WHERE shop_id=? ORDER BY id",
                        Long.class, sparse.shop);
                assertEquals(10_000, denseIds.size());
                assertEquals(10, sparseIds.size());
                Set<Long> seen = new HashSet<>();
                List<Long> millis = new ArrayList<>();
                String cursor = null;
                int pages = 0;
                do {
                    String path = "/discovery/products?shopId=" + dense.shop + "&size=50&q=" + prefix
                            + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor,
                            java.nio.charset.StandardCharsets.UTF_8));
                    long start = System.nanoTime();
                    var response = client.get(path);
                    millis.add((System.nanoTime() - start) / 1_000_000);
                    assertEquals(200, response.statusCode(), response.body());
                    var data = json.readTree(response.body()).path("data");
                    for (var item : data.path("items"))
                        assertTrue(seen.add(item.path("id").asLong()), "游标重复商品");
                    cursor = data.path("hasMore").asBoolean() ? data.path("nextCursor").asString() : null;
                    pages++;
                    assertTrue(pages <= 201, "游标未收敛");
                } while (cursor != null);
                assertEquals(new HashSet<>(denseIds), seen);
                var sparseResponse = client.get("/discovery/products?shopId=" + sparse.shop + "&size=50&q=" + prefix);
                assertEquals(200, sparseResponse.statusCode(), sparseResponse.body());
                Set<Long> sparseResults = new HashSet<>();
                var items = json.readTree(sparseResponse.body()).path("data").path("items");
                for (var item : items)
                    assertTrue(sparseResults.add(item.path("id").asLong()), "游标重复商品");
                assertEquals(new HashSet<>(sparseIds), sparseResults);
                Collections.sort(millis);
                System.out.printf("D2_LARGE products=%d sparse=%d pages=%d p95Ms=%d p99Ms=%d%n",
                        seen.size(), sparseIds.size(), pages, percentile(millis, 0.95), percentile(millis, 0.99));
            } finally {
                jdbc.update("DELETE FROM skus WHERE product_id IN (SELECT id FROM products WHERE shop_id=?)",
                        dense.shop);
                jdbc.update("DELETE FROM products WHERE shop_id=?", dense.shop);
                sparse.close();
                dense.close();
            }
        }
    }

    private static long percentile(List<Long> sorted, double rank) {
        return sorted.get((int) Math.ceil(sorted.size() * rank) - 1);
    }
}
