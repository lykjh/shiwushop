package com.shiwushop.integration.marketing;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.marketing.service.MarketingActivityService;
import com.shiwushop.marketing.mapper.MarketingActivityMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
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
class MarketingActivityHttpTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MarketingActivityService activities;
    @MockitoSpyBean
    MarketingActivityMapper mapper;
    CommerceFixture fixture;
    HttpAccountClient adminClient, reviewerClient;
    TestAccounts.Account admin;
    final ObjectMapper json = new ObjectMapper();
    String merchantRoot;

    @BeforeEach
    void prepare() throws Exception {
        try (var c = jdbc.getDataSource().getConnection()) {
            assertTrue(c.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_"));
        }
        String base = "http://127.0.0.1:" + port;
        fixture = new CommerceFixture(jdbc, base);
        admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        adminClient = CommerceFixture.login(base, admin);
        reviewerClient = CommerceFixture.login(base, new TestAccounts(jdbc).create("ADMIN", "Shop123!"));
        merchantRoot = "/merchant/shops/" + fixture.catalog.shop + "/marketing";
    }

    @AfterEach
    void close() {
        if (adminClient != null)
            adminClient.close();
        if (reviewerClient != null)
            reviewerClient.close();
        if (fixture != null) {
            fixture.client.close();
            fixture.merchantClient.close();
        }
    }

    Map<String, Object> draft() {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "冬日活动");
        body.put("type", "LIMITED_PRICE");
        body.put("startAt", "2035-11-01T00:00:00+08:00");
        body.put("endAt", "2035-12-01T00:00:00+08:00");
        body.put("eligibleLevelCodes", List.of("ORDINARY", "SILVER"));
        body.put("rulesText", "指定商品优惠");
        body.put("scopes", List.of(Map.of("skuId", fixture.sku, "scopeRole", "SALE", "activityPrice", 10)));
        body.put("tiers", List.of());
        body.put("couponRule", null);
        body.put("budgetProposals",
                List.of(Map.of("party", "MERCHANT", "shopId", fixture.catalog.shop, "budgetAmount", 100,
                        "platformShareBp", 0)));
        return body;
    }

    HttpResponse<String> create(Map<String, Object> body, String key) throws Exception {
        return adminClient.request("POST", "/admin/marketing/activities", body, true, Map.of("Idempotency-Key", key));
    }

    long id(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("data").path("id").asLong();
    }

    String etag(HttpResponse<String> response) {
        return response.headers().firstValue("ETag").orElseThrow();
    }

    String latest(long id) throws Exception {
        return etag(adminClient.get("/admin/marketing/activities/" + id));
    }

    HttpResponse<String> edit(long id, String tag, Map<String, Object> body) throws Exception {
        return adminClient.request("PUT", "/admin/marketing/activities/" + id, body, true, Map.of("If-Match", tag));
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void groupDraftOnlyAcceptsTwoMembersAtCreateAndEdit() throws Exception {
        var body = draft();
        body.put("type", "GROUP");
        body.put("scopes", List.of(Map.of("skuId", fixture.sku, "scopeRole", "SALE", "activityPrice", 10,
                "teamSize", 2, "participationSeconds", 60)));
        long activity = id(create(body, "group-two-members"));
        body.put("scopes", List.of(Map.of("skuId", fixture.sku, "scopeRole", "SALE", "activityPrice", 10,
                "teamSize", 3, "participationSeconds", 60)));
        assertEquals(400, create(body, "group-three-members").statusCode());
        assertEquals(400, edit(activity, latest(activity), body).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_activities WHERE creator_user_id=?", admin.id()));
    }

    void agree(long id) throws Exception {
        var response = fixture.merchantClient.request("PUT", merchantRoot + "/activities/" + id + "/agreement",
                Map.of("platformShareBp", 0, "merchantBudgetAmount", 100, "reason", "确认本期承担"), true,
                Map.of("If-Match", latest(id)));
        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void createReplayEditAndAgreementUsePersistentContentVersion() throws Exception {
        var body = draft();
        var first = create(body, "draft:~1");
        long activity = id(first);
        assertEquals(activity, id(create(body, "draft:~1")));
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_activities WHERE creator_user_id=?", admin.id()));
        agree(activity);
        assertTrue(json.readTree(adminClient.get("/admin/marketing/activities/" + activity).body()).path("data")
                .path("agreementsComplete").asBoolean());
        var old = latest(activity);
        body.put("name", "编辑名称");
        var edited = edit(activity, old, body);
        assertEquals(200, edited.statusCode(), edited.body());
        assertFalse(json.readTree(edited.body()).path("data").path("agreementsComplete").asBoolean());
        assertEquals(409, edit(activity, old, body).statusCode());
        assertEquals(409, create(body, "draft:~1").statusCode());
        assertEquals(activity, id(create(draft(), "draft:~1")));
        assertEquals("编辑名称",
                json.readTree(create(draft(), "draft:~1").body()).path("data").path("name").asString());
        assertEquals(403, fixture.merchantClient.request("PUT", merchantRoot + "/activities/" + activity, body, true,
                Map.of("If-Match", latest(activity))).statusCode());
    }

    @Test
    void ledgerBudgetCannotBeDeletedOrRepricedAndItsIdSurvivesDraftEdit() throws Exception {
        var body = draft();
        var proposals = new ArrayList<Map<String, Object>>();
        proposals.add(Map.of("party", "PLATFORM", "budgetAmount", 50));
        proposals.add(
                Map.of("party", "MERCHANT", "shopId", fixture.catalog.shop, "budgetAmount", 100, "platformShareBp", 0));
        body.put("budgetProposals", proposals);
        long activity = id(create(body, "ledger-budget"));
        long budget = count("SELECT id FROM marketing_budgets WHERE activity_id=? AND party='PLATFORM'", activity);
        var adjusted = adminClient.request("POST", "/admin/marketing/budgets/" + budget + "/adjustments",
                Map.of("deltaAmount", 10, "reason", "追加额度", "evidenceRef", "批准单"), true,
                Map.of("Idempotency-Key", "add:10"));
        assertEquals(200, adjusted.statusCode(), adjusted.body());
        String tag = latest(activity);
        body.put("budgetProposals", List.of(proposals.get(1)));
        assertEquals(409, edit(activity, tag, body).statusCode());
        body.put("budgetProposals", proposals);
        assertEquals(409, edit(activity, tag, body).statusCode());
        proposals.set(0, Map.of("party", "PLATFORM", "budgetAmount", 60));
        body.put("name", "保持原账本");
        var edited = edit(activity, tag, body);
        assertEquals(200, edited.statusCode(), edited.body());
        assertEquals(budget,
                count("SELECT id FROM marketing_budgets WHERE activity_id=? AND party='PLATFORM'", activity));
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_budget_entries WHERE budget_id=?", budget));
        assertEquals(60, count("SELECT budget_amount FROM marketing_budgets WHERE id=?", budget));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='ACTIVITY_DRAFT_EDITED'",
                activity));
    }

    @Test
    void submitRequiresAgreementAndDifferentReviewerAndApprovalFreezesDraft() throws Exception {
        long activity = id(create(draft(), "approval"));
        String path = "/admin/marketing/activities/" + activity;
        assertEquals(409,
                adminClient.request("POST", path + "/submit", null, true, Map.of("If-Match", latest(activity)))
                        .statusCode());
        agree(activity);
        var submitted = adminClient.request("POST", path + "/submit", null, true, Map.of("If-Match", latest(activity)));
        assertEquals(200, submitted.statusCode(), submitted.body());
        var decision = Map.of("decision", "APPROVE", "reason", "核对通过");
        assertEquals(403,
                adminClient.request("POST", path + "/decision", decision, true, Map.of("If-Match", etag(submitted)))
                        .statusCode());
        var approved = reviewerClient.request("POST", path + "/decision", decision, true,
                Map.of("If-Match", etag(submitted)));
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals("APPROVED", json.readTree(approved.body()).path("data").path("status").asString());
        assertEquals(409, edit(activity, etag(approved), draft()).statusCode());
    }

    @Test
    void concurrentCreationWithOneKeyWritesOneWholeActivity() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CyclicBarrier(6);
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 6; i++)
                futures.add(executor.submit(() -> {
                    gate.await();
                    return id(create(draft(), "same-key"));
                }));
            var ids = new HashSet<Long>();
            for (var f : futures)
                ids.add(f.get(30, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            long activity = ids.iterator().next();
            assertEquals(1, count("SELECT COUNT(*) FROM marketing_activity_skus WHERE activity_id=?", activity));
            assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='ACTIVITY_CREATED'",
                    activity));
        }
    }

    long pending(String key) throws Exception {
        long activity = id(create(draft(), key));
        agree(activity);
        var submitted = adminClient.request("POST", "/admin/marketing/activities/" + activity + "/submit", null, true,
                Map.of("If-Match", latest(activity)));
        assertEquals(200, submitted.statusCode(), submitted.body());
        return activity;
    }

    HttpResponse<String> approve(long activity, String tag) throws Exception {
        return reviewerClient.request("POST", "/admin/marketing/activities/" + activity + "/decision",
                Map.of("decision", "APPROVE", "reason", "核对通过"), true, Map.of("If-Match", tag));
    }

    @Test
    void overlappingApprovalsAreSerializedAtTheSku() throws Exception {
        long first = pending("overlap-a"), second = pending("overlap-b");
        String aTag = latest(first), bTag = latest(second);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CyclicBarrier(2);
            var a = executor.submit(() -> {
                gate.await();
                return approve(first, aTag);
            });
            var b = executor.submit(() -> {
                gate.await();
                return approve(second, bTag);
            });
            var responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList(),
                    responses.toString());
        }
    }

    @Test
    void stateReplayVersionAndTimerPreserveOriginalDecision() throws Exception {
        long activity = pending("state");
        String path = "/admin/marketing/activities/" + activity;
        String pendingTag = latest(activity);
        assertEquals(200, approve(activity, pendingTag).statusCode());
        assertEquals(200, approve(activity, pendingTag).statusCode());
        assertEquals(1,
                count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='ACTIVITY_APPROVED_FROM_PENDING_REVIEW'",
                        activity));
        var reason = Map.of("reason", "下一期价格");
        var version = adminClient.request("POST", path + "/versions", reason, true, Map.of("Idempotency-Key", "v:2"));
        long next = id(version);
        assertNotEquals(activity, next);
        assertEquals(2, json.readTree(version.body()).path("data").path("versionNo").asInt());
        assertFalse(json.readTree(version.body()).path("data").path("agreementsComplete").asBoolean());
        assertEquals(next,
                id(adminClient.request("POST", path + "/versions", reason, true, Map.of("Idempotency-Key", "v:2"))));
        assertEquals(409, adminClient.request("POST", path + "/versions", Map.of("reason", "不同原因"), true,
                Map.of("Idempotency-Key", "v:2")).statusCode());
        jdbc.update("UPDATE marketing_activities SET start_at='2020-01-01' WHERE id=?", activity);
        activities.advance(activity);
        activities.advance(activity);
        assertEquals("RUNNING", json.readTree(adminClient.get(path).body()).path("data").path("status").asString());
        String runningTag = latest(activity);
        var pauseReason = Map.of("reason", "核对库存");
        var paused = adminClient.request("POST", path + "/pause", pauseReason, true, Map.of("If-Match", runningTag));
        assertEquals(200, paused.statusCode(), paused.body());
        assertEquals(200,
                adminClient.request("POST", path + "/pause", pauseReason, true, Map.of("If-Match", runningTag))
                        .statusCode());
        var resumed = adminClient.request("POST", path + "/resume", Map.of("reason", "核对完成"), true,
                Map.of("If-Match", etag(paused)));
        assertEquals(200, resumed.statusCode(), resumed.body());
        jdbc.update("UPDATE marketing_activities SET end_at='2020-01-02' WHERE id=?", activity);
        activities.advance(activity);
        activities.advance(activity);
        assertEquals("ENDED", json.readTree(adminClient.get(path).body()).path("data").path("status").asString());
        assertEquals(1,
                count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='ACTIVITY_ENDED_FROM_RUNNING'",
                        activity));
    }

    @Test
    void auditFailureRollsBackAllDraftRowsAndOriginalKeyCanRetry() throws Exception {
        doThrow(new IllegalStateException("injected activity audit failure")).when(mapper)
                .audit(eq(admin.id()), anyLong(), eq("ACTIVITY_CREATED"), anyString(), any());
        var failed = create(draft(), "audit-rollback");
        assertEquals(500, failed.statusCode(), failed.body());
        assertEquals(0, count("SELECT COUNT(*) FROM marketing_activities WHERE creator_user_id=?", admin.id()));
        reset(mapper);
        long activity = id(create(draft(), "audit-rollback"));
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_activity_skus WHERE activity_id=?", activity));
    }

    @Test
    void ordinaryPriceCannotUndercutApprovedMemberPriceUntilActivityIsTerminated() throws Exception {
        var body = draft();
        body.put("type", "MEMBER_PRICE");
        body.put("scopes", List.of(Map.of("skuId", fixture.sku, "scopeRole", "SALE",
                "memberPrices", Map.of("SILVER", 18, "GOLD", 16, "PLATINUM", 14))));
        long activity = id(create(body, "member-price"));
        agree(activity);
        String path = "/admin/marketing/activities/" + activity;
        assertEquals(200,
                adminClient.request("POST", path + "/submit", null, true, Map.of("If-Match", latest(activity)))
                        .statusCode());
        assertEquals(200, approve(activity, latest(activity)).statusCode());
        String skuPath = "/merchant/products/" + fixture.product + "/skus/" + fixture.sku;
        assertEquals(200, fixture.merchantClient.request("PUT", "/merchant/products/" + fixture.product
                + "/status", Map.of("status", "OFF_SALE"), true, Map.of()).statusCode());
        var blocked = fixture.merchantClient.request("PUT", skuPath, Map.of("price", 17, "stock", 20), true, Map.of());
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertEquals(activity, json.readTree(blocked.body()).path("data").path("activityIds").get(0).asLong());
        assertEquals(new java.math.BigDecimal("19.90"),
                jdbc.queryForObject("SELECT price FROM skus WHERE id=?", java.math.BigDecimal.class, fixture.sku));
        assertEquals(200,
                fixture.merchantClient.request("PUT", skuPath, Map.of("price", 18, "stock", 20), true, Map.of())
                        .statusCode());
        var terminated = adminClient.request("POST", path + "/terminate", Map.of("reason", "重设下期会员价"), true,
                Map.of("If-Match", latest(activity)));
        assertEquals(200, terminated.statusCode(), terminated.body());
        assertEquals(200,
                fixture.merchantClient.request("PUT", skuPath, Map.of("price", 17, "stock", 20), true, Map.of())
                        .statusCode());
        assertEquals(18,
                count("SELECT p.price FROM marketing_member_prices p JOIN marketing_activity_skus s ON s.id=p.activity_sku_id WHERE s.activity_id=? AND p.level_code='SILVER'",
                        activity));
    }
}
