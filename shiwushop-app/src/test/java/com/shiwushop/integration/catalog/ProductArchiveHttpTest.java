package com.shiwushop.integration.catalog;

import com.shiwushop.support.CommerceFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductArchiveHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate transactions;
    CommerceFixture fixture;
    Long activityId, activitySkuId;

    @BeforeEach
    void prepare() throws Exception {
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
    }

    @AfterEach
    void cleanup() {
        if (fixture != null) {
            if (activitySkuId != null)
                jdbc.update("DELETE FROM marketing_stock_pools WHERE activity_sku_id=?", activitySkuId);
            if (activityId != null) {
                jdbc.update("DELETE FROM marketing_activity_skus WHERE activity_id=?", activityId);
                jdbc.update("DELETE FROM marketing_activities WHERE id=?", activityId);
            }
            fixture.close();
        }
    }

    @Test
    void unpaidOrderBlocksArchiveThenRestoreRequiresFreshReview() throws Exception {
        long product = fixture.product;
        long order = fixture.order("archive-test");
        var offSale = fixture.merchantClient.request("PUT", "/merchant/products/" + product + "/status",
                Map.of("status", "OFF_SALE"));
        assertEquals(200, offSale.statusCode(), offSale.body());
        long revision = jdbc.queryForObject("SELECT revision FROM products WHERE id=?", Long.class, product);
        String path = "/merchant/products/" + product + "/archive";
        var blocked = fixture.merchantClient.post(path, Map.of("revision", revision));
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertEquals("OFF_SALE", jdbc.queryForObject("SELECT status FROM products WHERE id=?", String.class, product));

        jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", order);
        var archived = fixture.merchantClient.post(path, Map.of("revision", revision));
        assertEquals(200, archived.statusCode(), archived.body());
        assertEquals("ARCHIVED", jdbc.queryForObject("SELECT status FROM products WHERE id=?", String.class, product));
        long archivedRevision = jdbc.queryForObject("SELECT revision FROM products WHERE id=?", Long.class, product);
        assertEquals(archivedRevision, json.readTree(archived.body()).path("data").path("revision").asLong());
        assertTrue(json.readTree(archived.body()).path("data").path("reviewStatus").isNull());
        assertEquals(200, fixture.merchantClient.post(path, Map.of("revision", revision)).statusCode());
        assertEquals(archivedRevision,
                jdbc.queryForObject("SELECT revision FROM products WHERE id=?", Long.class, product).longValue());
        assertEquals(404, fixture.client.get("/products/" + product).statusCode());

        var restored = fixture.merchantClient.post("/merchant/products/" + product + "/restore", Map.of());
        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM products WHERE id=?", String.class, product));
        assertEquals(archivedRevision + 1,
                jdbc.queryForObject("SELECT revision FROM products WHERE id=?", Long.class, product).longValue());
        assertEquals(archivedRevision + 1,
                json.readTree(restored.body()).path("data").path("revision").asLong());
        assertEquals(404, fixture.client.get("/products/" + product).statusCode());
    }

    @Test
    void approvedActivityAndRemainingPoolBlockArchiveUntilResponsibilitiesEnd() throws Exception {
        long product = fixture.product;
        var offSale = fixture.merchantClient.request("PUT", "/merchant/products/" + product + "/status",
                Map.of("status", "OFF_SALE"));
        assertEquals(200, offSale.statusCode(), offSale.body());
        long revision = jdbc.queryForObject("SELECT revision FROM products WHERE id=?", Long.class, product);
        createActivity("archive-" + UUID.randomUUID());
        String path = "/merchant/products/" + product + "/archive";
        jdbc.update("UPDATE marketing_activities SET status='APPROVED' WHERE id=?", activityId);
        assertEquals(409, fixture.merchantClient.post(path, Map.of("revision", revision)).statusCode());
        jdbc.update("UPDATE marketing_activities SET status='TERMINATED' WHERE id=?", activityId);
        jdbc.update("INSERT INTO marketing_stock_pools(activity_sku_id,allocated_qty,available_qty) VALUES(?,1,1)",
                activitySkuId);
        assertEquals(409, fixture.merchantClient.post(path, Map.of("revision", revision)).statusCode());
        jdbc.update("UPDATE marketing_stock_pools SET available_qty=0,consumed_qty=1 WHERE activity_sku_id=?",
                activitySkuId);
        assertEquals(200, fixture.merchantClient.post(path, Map.of("revision", revision)).statusCode());
        assertEquals("ARCHIVED", jdbc.queryForObject("SELECT status FROM products WHERE id=?", String.class, product));
    }

    @Test
    void archiveWaitsForNewlyCommittedActivityResponsibility() throws Exception {
        long product = fixture.product;
        assertEquals(200, fixture.merchantClient.request("PUT", "/merchant/products/" + product + "/status",
                Map.of("status", "OFF_SALE")).statusCode());
        long revision = jdbc.queryForObject("SELECT revision FROM products WHERE id=?", Long.class, product);
        CountDownLatch shopLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var creator = pool.submit(() -> transactions.execute(tx -> {
                jdbc.queryForObject("SELECT id FROM shops WHERE id=? FOR UPDATE", Long.class, fixture.catalog.shop);
                shopLocked.countDown();
                createActivity("archive-race-" + UUID.randomUUID());
                jdbc.update("UPDATE marketing_activities SET status='APPROVED' WHERE id=?", activityId);
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("归档交错未释放店铺锁");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            try {
                assertTrue(shopLocked.await(5, TimeUnit.SECONDS));
                var archive = pool.submit(() -> fixture.merchantClient.post(
                        "/merchant/products/" + product + "/archive", Map.of("revision", revision)));
                org.junit.jupiter.api.Assertions.assertThrows(TimeoutException.class,
                        () -> archive.get(200, TimeUnit.MILLISECONDS));
                release.countDown();
                creator.get(5, TimeUnit.SECONDS);
                assertEquals(409, archive.get(5, TimeUnit.SECONDS).statusCode());
            } finally {
                release.countDown();
            }
        }
        assertEquals("OFF_SALE", jdbc.queryForObject("SELECT status FROM products WHERE id=?", String.class, product));
    }

    private void createActivity(String key) {
        activityId = fixture.catalog.insert("""
                INSERT INTO marketing_activities(activity_code,version_no,name,activity_type,status,start_at,end_at,
                  eligible_level_codes,creator_user_id,rules_text,request_scope,request_key,request_hash)
                VALUES(?,1,'归档责任','COUPON','DRAFT','2026-11-01','2026-12-01',JSON_ARRAY('ORDINARY'),?,
                  '归档责任测试','CREATE_PLATFORM',?,UNHEX(SHA2(?,256)))
                """, key, fixture.catalog.merchant.id(), key, key);
        activitySkuId = fixture.catalog.insert("INSERT INTO marketing_activity_skus(activity_id,sku_id) VALUES(?,?)",
                activityId, fixture.sku);
    }
}
