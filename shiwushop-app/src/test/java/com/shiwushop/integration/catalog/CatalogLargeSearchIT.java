package com.shiwushop.integration.catalog;

import com.shiwushop.product.search.SearchGateway;
import com.shiwushop.product.search.SearchIndexSource;
import com.shiwushop.support.CatalogFixture;
import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 显式运行的串行目录功能基线；只使用测试专库和 SHOP_SEARCH_URL 指向的独占索引。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.search.url=${SHOP_SEARCH_URL}", "shop.search.discovery-enabled=true", "shop.search.worker-enabled=false",
        "shop.search.cursor-secret=local-large-search-secret-at-least-32-bytes"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogLargeSearchIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchIndexSource source;
    @Autowired SearchGateway search;
    @Autowired ObjectMapper json;

    @Test
    void tenThousandProductsHaveCompleteCursorAndSparseShopResults() throws Exception {
        String endpoint = Objects.requireNonNull(System.getenv("SHOP_SEARCH_URL"), "独占搜索地址未设置");
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
                List<Long> all = new ArrayList<>(denseIds);
                all.addAll(sparseIds);
                for (int start = 0; start < all.size(); start += 500) {
                    List<Long> ids = all.subList(start, Math.min(start + 500, all.size()));
                    Map<Long, Map<String, Object>> documents = source.productDocuments(ids);
                    assertEquals(ids.size(), documents.size());
                    StringBuilder bulk = new StringBuilder();
                    for (long id : ids) {
                        bulk.append(json.writeValueAsString(Map.of("index",
                                Map.of("_index", "shiwushop-products-v2", "_id", id)))).append('\n');
                        bulk.append(json.writeValueAsString(documents.get(id))).append('\n');
                    }
                    assertFalse(search.bulk(bulk.toString()).path("errors").asBoolean());
                }

                Set<Long> seen = new HashSet<>();
                List<Long> millis = new ArrayList<>();
                String cursor = null;
                int pages = 0;
                do {
                    String path = "/discovery/products?shopId=" + dense.shop + "&size=50"
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
                assertEquals(10_000, seen.size());
                var sparseResponse = client.get("/discovery/products?shopId=" + sparse.shop + "&size=50");
                assertEquals(200, sparseResponse.statusCode(), sparseResponse.body());
                assertEquals(10, json.readTree(sparseResponse.body()).path("data").path("items").size());
                Collections.sort(millis);
                System.out.printf("D2_LARGE products=%d sparse=%d pages=%d p95Ms=%d p99Ms=%d%n",
                        seen.size(), sparseIds.size(), pages, percentile(millis, 0.95), percentile(millis, 0.99));
                for (long shop : List.of(dense.shop, sparse.shop)) {
                    String plan = jdbc.queryForObject("EXPLAIN ANALYZE SELECT p.id FROM products p "
                            + "FORCE INDEX (idx_products_shop_status_id) WHERE p.shop_id=? "
                            + "AND p.status='ON_SALE' ORDER BY p.id DESC LIMIT 50", String.class, shop);
                    System.out.println("D2_LARGE_SCAN shop=" + shop + " plan=" + plan);
                }
            } finally {
                try {
                    String body = json.writeValueAsString(Map.of("query", Map.of("terms",
                            Map.of("shopId", List.of(dense.shop, sparse.shop)))));
                    try (HttpClient http = HttpClient.newHttpClient()) {
                        var response = http.send(HttpRequest.newBuilder(URI.create(endpoint
                                + "/shiwushop-products-v2/_delete_by_query?refresh=true"))
                                .timeout(Duration.ofSeconds(60))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode(), response.body());
                    }
                } finally {
                    jdbc.update("DELETE FROM skus WHERE product_id IN (SELECT id FROM products WHERE shop_id=?)",
                            dense.shop);
                    jdbc.update("DELETE FROM products WHERE shop_id=?", dense.shop);
                    sparse.close();
                    dense.close();
                }
            }
        }
    }

    private static long percentile(List<Long> sorted, double rank) {
        return sorted.get((int) Math.ceil(sorted.size() * rank) - 1);
    }
}
