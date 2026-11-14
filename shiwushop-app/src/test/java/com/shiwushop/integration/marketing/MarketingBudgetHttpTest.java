package com.shiwushop.integration.marketing;

import com.shiwushop.marketing.mapper.MarketingBudgetMapper;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.marketing.enabled=true", "shop.member.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=12",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MarketingBudgetHttpTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ShopService shops;
    @MockitoSpyBean
    MarketingBudgetMapper mapper;
    CommerceFixture fixture;
    TestAccounts.Account admin;
    long activity, platform, merchant;
    final ObjectMapper json = new ObjectMapper();
    String merchantRoot;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(
                    connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_"));
        }
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        var key = UUID.randomUUID().toString();
        activity = fixture.catalog.insert("""
                INSERT INTO marketing_activities(activity_code,version_no,name,activity_type,status,start_at,end_at,
                  eligible_level_codes,creator_user_id,rules_text,request_scope,request_key,request_hash)
                VALUES(?,1,'冬日满减','FULL_REDUCTION','DRAFT','2026-11-01','2026-12-01',JSON_ARRAY('ORDINARY'),?,
                  '满额减免','CREATE_PLATFORM',?,UNHEX(SHA2(?,256)))
                """, key, admin.id(), key, key);
        platform = fixture.catalog.insert(
                "INSERT INTO marketing_budgets(activity_id,party,budget_amount) VALUES(?,'PLATFORM',100)", activity);
        merchant = fixture.catalog.insert("""
                INSERT INTO marketing_budgets(activity_id,party,shop_id,budget_amount,platform_share_bp)
                VALUES(?,'MERCHANT',?,100,5000)
                """, activity, fixture.catalog.shop);
        merchantRoot = "/merchant/shops/" + fixture.catalog.shop + "/marketing";
    }

    @AfterEach
    void closeSessions() {
        if (fixture != null) {
            fixture.client.close();
            fixture.merchantClient.close();
        }
    }

    Map<String, Object> adjustment(int amount, String evidence) {
        return Map.of("deltaAmount", amount, "reason", "调整额度", "evidenceRef", evidence);
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void queriesSeparateStoreAndPlatformDataAndRejectFormerOwner() throws Exception {
        var own = fixture.merchantClient.get(merchantRoot + "/activities/" + activity + "/budgets");
        assertEquals(200, own.statusCode(), own.body());
        assertTrue(own.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var rows = json.readTree(own.body()).path("data");
        assertEquals(1, rows.size());
        assertEquals(merchant, rows.get(0).path("id").asLong());
        assertEquals(404, fixture.merchantClient.get(merchantRoot + "/budgets/" + platform + "/entries").statusCode());
        assertEquals(403, fixture.client.get("/admin/marketing/activities/" + activity + "/budgets").statusCode());
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            assertEquals(2, json.readTree(client.get("/admin/marketing/activities/" + activity + "/budgets").body())
                    .path("data").size());
            assertEquals(404, client.get("/admin/marketing/activities/" + Long.MAX_VALUE + "/budgets").statusCode());
        }
        var response = fixture.merchantClient.request("POST", merchantRoot + "/budgets/" + merchant + "/adjustments",
                adjustment(1, "TRANSFER-1"), true, Map.of("Idempotency-Key", "owner:1"));
        assertEquals(200, response.statusCode(), response.body());
        var ownEntry = json.readTree(
                        fixture.merchantClient.get(merchantRoot + "/budgets/" + merchant + "/entries").body())
                .path("data").path("items").get(0);
        assertEquals(1, ownEntry.path("budgetDeltaAmount").asInt());
        assertEquals(101, ownEntry.path("budgetAfter").asInt());
        assertEquals(fixture.catalog.merchant.id(), ownEntry.path("actorUserId").asLong());
        assertEquals("TRANSFER-1", ownEntry.path("evidenceRef").asString());
        var nextOwner = new TestAccounts(jdbc).create("MERCHANT", "Shop123!");
        shops.changeOwner(fixture.catalog.shop, nextOwner.id());
        var replay = fixture.merchantClient.request("POST", merchantRoot + "/budgets/" + merchant + "/adjustments",
                adjustment(1, "TRANSFER-1"), true, Map.of("Idempotency-Key", "owner:1"));
        assertEquals(404, replay.statusCode(), replay.body());
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_budget_entries WHERE budget_id=?", merchant));
    }

    @Test
    void adjustmentPersistsFullEvidenceAndReplaysOriginalEntryWithCurrentBalance() throws Exception {
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            String path = "/admin/marketing/budgets/" + platform;
            var body = adjustment(50, "凭".repeat(500));
            var first = client.request("POST", path + "/adjustments", body, true,
                    Map.of("Idempotency-Key", "Budget:~"));
            assertEquals(200, first.statusCode(), first.body());
            var result = json.readTree(first.body()).path("data");
            long entry = result.path("budgetEntryId").asLong();
            assertEquals(150, result.path("budgetAmount").asInt());
            assertEquals(1, result.path("version").asInt());
            assertFalse(result.has("budget"));
            assertEquals(500,
                    count("SELECT CHAR_LENGTH(evidence_reference) FROM marketing_budget_entries WHERE id=?", entry));
            var later = client.request("POST", path + "/adjustments", adjustment(5, "SECOND"), true,
                    Map.of("Idempotency-Key", "Budget:second"));
            assertEquals(200, later.statusCode(), later.body());
            var replay = client.request("POST", path + "/adjustments", body, true,
                    Map.of("Idempotency-Key", "Budget:~"));
            assertEquals(200, replay.statusCode(), replay.body());
            assertEquals(entry, json.readTree(replay.body()).path("data").path("budgetEntryId").asLong());
            assertEquals(155, json.readTree(replay.body()).path("data").path("budgetAmount").asInt());
            var conflict = client.request("POST", path + "/adjustments", adjustment(50, "OTHER"), true,
                    Map.of("Idempotency-Key", "Budget:~"));
            assertEquals(409, conflict.statusCode(), conflict.body());
            assertEquals("IDEMPOTENCY_CONFLICT", json.readTree(conflict.body()).path("data").path("reason").asString());
            var page = json.readTree(client.get(path + "/entries?size=1").body()).path("data");
            assertTrue(page.path("hasMore").asBoolean());
            assertFalse(page.path("items").get(0).has("operationKey"));
            assertTrue(page.path("items").get(0).path("createdAt").asString().endsWith("+08:00"));
            var last = json.readTree(
                            client.get(path + "/entries?size=1&afterId=" + page.path("nextCursor").asLong()).body())
                    .path("data");
            assertFalse(last.path("hasMore").asBoolean());
            assertTrue(last.path("nextCursor").isNull());
            var original = last.path("items").get(0);
            assertEquals(50, original.path("budgetDeltaAmount").asInt());
            assertEquals(150, original.path("budgetAfter").asInt());
            assertEquals(admin.id(), original.path("actorUserId").asLong());
            assertEquals("凭".repeat(500), original.path("evidenceRef").asString());
            assertEquals(2, count("SELECT COUNT(*) FROM marketing_budget_entries WHERE budget_id=?", platform));
            assertEquals(2,
                    count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='MARKETING_BUDGET_ADJUSTED'",
                            platform));
            var summary = jdbc.queryForObject(
                    "SELECT summary FROM audit_events WHERE target_id=? AND action='MARKETING_BUDGET_ADJUSTED' ORDER BY id LIMIT 1",
                    String.class, platform);
            assertEquals(entry, json.readTree(summary).path("entryId").asLong());
            assertFalse(summary.contains("凭"));
        }
    }

    @Test
    void shrinkCannotTakeExistingCommitmentsAndAdjustmentDoesNotConfirmAgreement() throws Exception {
        jdbc.update("UPDATE marketing_budgets SET held_amount=60,spent_amount=20 WHERE id=?", merchant);
        String path = merchantRoot + "/budgets/" + merchant + "/adjustments";
        var failure = fixture.merchantClient.request("POST", path, adjustment(-21, "LOW"), true,
                Map.of("Idempotency-Key", "shrink"));
        assertEquals(409, failure.statusCode(), failure.body());
        assertEquals("BUDGET_NOT_ENOUGH", json.readTree(failure.body()).path("data").path("reason").asString());
        assertEquals(0, count("SELECT COUNT(*) FROM marketing_budget_entries WHERE budget_id=?", merchant));
        var exact = fixture.merchantClient.request("POST", path, adjustment(-20, "EXACT"), true,
                Map.of("Idempotency-Key", "shrink"));
        assertEquals(200, exact.statusCode(), exact.body());
        assertEquals(0, json.readTree(exact.body()).path("data").path("availableAmount").asInt());
        assertTrue(json.readTree(exact.body()).path("data").path("agreedAt").isNull());
        assertEquals(5000, count("SELECT platform_share_bp FROM marketing_budgets WHERE id=?", merchant));
    }

    @Test
    void eightConcurrentRetriesWriteOneEntryAndAudit() throws Exception {
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port,
                admin); var pool = Executors.newFixedThreadPool(8)) {
            var barrier = new CyclicBarrier(8);
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 8; i++)
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    var response = client.request("POST", "/admin/marketing/budgets/" + platform + "/adjustments",
                            adjustment(25, "RACE"), true, Map.of("Idempotency-Key", "same-command"));
                    assertEquals(200, response.statusCode(), response.body());
                    return json.readTree(response.body()).path("data").path("budgetEntryId").asLong();
                }));
            var entries = new HashSet<Long>();
            for (var future : futures)
                entries.add(future.get(20, TimeUnit.SECONDS));
            assertEquals(1, entries.size());
        }
        assertEquals(125, count("SELECT budget_amount FROM marketing_budgets WHERE id=?", platform));
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_budget_entries WHERE budget_id=?", platform));
        assertEquals(1,
                count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='MARKETING_BUDGET_ADJUSTED'",
                        platform));
    }

    @Test
    void auditFailureRollsBackBudgetAndLedgerAndOriginalKeyCanRetry() throws Exception {
        doThrow(new IllegalStateException("controlled audit failure")).when(mapper)
                .audit(anyLong(), eq(platform), anyString(), any());
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            String path = "/admin/marketing/budgets/" + platform + "/adjustments";
            var failed = client.request("POST", path, adjustment(10, "ROLLBACK"), true,
                    Map.of("Idempotency-Key", "retry-original"));
            assertEquals(500, failed.statusCode(), failed.body());
            assertEquals(100, count("SELECT budget_amount FROM marketing_budgets WHERE id=?", platform));
            assertEquals(0, count("SELECT COUNT(*) FROM marketing_budget_entries WHERE budget_id=?", platform));
            assertEquals(0,
                    count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='MARKETING_BUDGET_ADJUSTED'",
                            platform));
            reset(mapper);
            var retried = client.request("POST", path, adjustment(10, "ROLLBACK"), true,
                    Map.of("Idempotency-Key", "retry-original"));
            assertEquals(200, retried.statusCode(), retried.body());
            assertEquals(new BigDecimal("110.00"),
                    jdbc.queryForObject("SELECT budget_amount FROM marketing_budgets WHERE id=?", BigDecimal.class,
                            platform));
        }
    }
}
