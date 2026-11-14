package com.shiwushop.integration.marketing;

import com.shiwushop.marketing.mapper.MarketingCouponMapper;
import com.shiwushop.marketing.service.MarketingCouponService;
import com.shiwushop.support.*;
import com.shiwushop.member.service.MemberRewardService;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.vo.order.OrderRewardSummary;
import org.mybatis.spring.SqlSessionTemplate;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.marketing.enabled=true", "shop.member.enabled=true",
        "shop.member.reward-start-at=2026-11-01T00:00:00", "shop.member.scan-delay-ms=3600000",
        "spring.datasource.hikari.maximum-pool-size=12",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MarketingCouponHttpTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    MemberRewardService rewards;
    @Autowired
    OrderMapper orders;
    @Autowired
    SqlSessionTemplate session;
    @Autowired
    OrderItemMapper orderItems;
    @Autowired
    MarketingCouponService coupons;
    @MockitoSpyBean
    MarketingCouponMapper mapper;
    @MockitoSpyBean
    com.shiwushop.marketing.MarketingSettings marketingSettings;
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name = "localSimulationGateway")
    com.shiwushop.trade.payment.LocalSimulationGateway gateway;
    CommerceFixture fixture;
    HttpAccountClient adminClient, reviewerClient;
    TestAccounts.Account admin;
    final ObjectMapper json = new ObjectMapper();
    @Autowired
    MutableMarketingClock clock;

    @BeforeEach
    void prepare() throws Exception {
        clock.at(java.time.LocalDateTime.of(2026, 11, 5, 10, 0));
        try (var c = jdbc.getDataSource().getConnection()) {
            assertTrue(c.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_"));
        }
        String base = "http://127.0.0.1:" + port;
        fixture = new CommerceFixture(jdbc, base);
        admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        adminClient = CommerceFixture.login(base, admin);
        reviewerClient = CommerceFixture.login(base, new TestAccounts(jdbc).create("ADMIN", "Shop123!"));
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

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    long id(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("data").path("id").asLong();
    }

    String tag(long activity) throws Exception {
        return adminClient.get("/admin/marketing/activities/" + activity).headers().firstValue("ETag").orElseThrow();
    }

    long activity(String mode, int budget, int limit) throws Exception {
        return activity(mode, budget, limit, 20);
    }

    long activity(String mode, int budget, int limit, int threshold) throws Exception {
        return activity(mode, budget, limit, threshold, List.of(fixture.sku));
    }

    long activity(String mode, int budget, int limit, int threshold, List<Long> skuIds) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "优惠券");
        body.put("type", "COUPON");
        body.put("startAt", "2020-01-01T00:00:00+08:00");
        body.put("endAt", "2035-12-01T00:00:00+08:00");
        body.put("eligibleLevelCodes", List.of("ORDINARY", "SILVER", "GOLD", "PLATINUM"));
        body.put("rulesText", "指定商品优惠券");
        body.put("scopes", skuIds.stream().map(sku -> Map.of("skuId", sku, "scopeRole", "SALE")).toList());
        body.put("tiers", List.of());
        body.put("couponRule",
                Map.of("faceAmount", 5, "thresholdAmount", threshold, "issueLimit", limit, "perUserLimit", limit,
                        "claimEndAt", "2035-12-01T00:00:00+08:00", "validityDays", 30, "grantMode", mode));
        body.put("budgetProposals", List.of(Map.of("party", "PLATFORM", "budgetAmount", budget),
                Map.of("party", "MERCHANT", "shopId", fixture.catalog.shop, "budgetAmount", budget, "platformShareBp",
                        5000)));
        return publish(body, budget, 5000);
    }

    long publish(Map<String, Object> body, int budget, int bp) throws Exception {
        long activity = id(adminClient.request("POST", "/admin/marketing/activities", body, true,
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        var agreement = fixture.merchantClient.request("PUT",
                "/merchant/shops/" + fixture.catalog.shop + "/marketing/activities/" + activity + "/agreement",
                Map.of("platformShareBp", bp, "merchantBudgetAmount", budget, "reason", "同意承担"), true,
                Map.of("If-Match", tag(activity)));
        assertEquals(200, agreement.statusCode(), agreement.body());
        var submitted = adminClient.request("POST", "/admin/marketing/activities/" + activity + "/submit", null, true,
                Map.of("If-Match", tag(activity)));
        assertEquals(200, submitted.statusCode(), submitted.body());
        var approved = reviewerClient.request("POST", "/admin/marketing/activities/" + activity + "/decision",
                Map.of("decision", "APPROVE", "reason", "批准"), true, Map.of("If-Match", tag(activity)));
        assertEquals(200, approved.statusCode(), approved.body());
        return activity;
    }

    Map<String, Object> compensation(long user, int count, String evidence) {
        return Map.of("userId", user, "count", count, "reason", "纠正漏发", "evidenceRef", evidence);
    }

    HttpResponse<String> grant(long activity, String key, Map<String, Object> body) throws Exception {
        return adminClient.request("POST", "/admin/marketing/coupon-rules/" + activity + "/grants", body, true,
                Map.of("Idempotency-Key", key));
    }

    @Test
    void claimIssuesOnePerKeyAndHonorsCumulativeLimit() throws Exception {
        long activity = activity("CLAIM", 100, 2);
        String url = "/marketing/coupon-rules/" + activity + "/claim";
        var key = Map.of("Idempotency-Key", "claim:1");
        var first = fixture.client.request("POST", url, null, true, key);
        long grantId = id(first);
        assertEquals(1, json.readTree(first.body()).path("data").path("coupons").size());
        assertEquals(grantId, id(fixture.client.request("POST", url, null, true, key)));
        assertNotEquals(grantId,
                id(fixture.client.request("POST", url, null, true, Map.of("Idempotency-Key", "claim:2"))));
        var limit = fixture.client.request("POST", url, null, true, Map.of("Idempotency-Key", "claim:3"));
        assertEquals(409, limit.statusCode());
        assertEquals("PURCHASE_LIMIT_REACHED", json.readTree(limit.body()).path("data").path("reason").asString());
        assertEquals(2, count("SELECT issued_count FROM marketing_coupon_rules WHERE activity_id=?", activity));
    }

    @Test
    void singleCodeConcurrentRedemptionAndReplayUseOneGrant() throws Exception {
        long activity = activity("CODE", 100, 10);
        String secret = UUID.randomUUID().toString(), url = "/users/me/coupons/redeem";
        var batch = adminClient.request("POST", "/admin/marketing/coupon-rules/" + activity + "/codes",
                Map.of("codes", List.of(code(secret))), true, Map.of("Idempotency-Key", "single"));
        assertEquals(200, batch.statusCode());
        var body = Map.of("code", secret);
        var key = Map.of("Idempotency-Key", "redeem");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CyclicBarrier(2);
            var a = pool.submit(() -> {
                gate.await();
                return fixture.client.request("POST", url, body, true, key);
            });
            var b = pool.submit(() -> {
                gate.await();
                return fixture.merchantClient.request("POST", url, body, true, key);
            });
            var first = a.get(30, TimeUnit.SECONDS);
            var second = b.get(30, TimeUnit.SECONDS);
            assertEquals(List.of(200, 409),
                    List.of(first.statusCode(), second.statusCode()).stream().sorted().toList());
            var winner = first.statusCode() == 200 ? fixture.client : fixture.merchantClient;
            var success = first.statusCode() == 200 ? first : second;
            var denied = first.statusCode() == 200 ? second : first;
            assertEquals("CODE_NOT_USABLE", json.readTree(denied.body()).path("data").path("reason").asString());
            assertEquals(1, json.readTree(success.body()).path("data").path("coupons").size());
            assertEquals(id(success), id(winner.request("POST", url, body, true, key)));
            var changed = winner.request("POST", url, Map.of("code", "DIFFERENT"), true, key);
            assertEquals("IDEMPOTENCY_CONFLICT", json.readTree(changed.body()).path("data").path("reason").asString());
            assertEquals(1, count("SELECT redeemed_count FROM marketing_coupon_codes WHERE activity_id=?", activity));
        }
    }

    @Test
    void publicCodeHasOneSharedCapAcrossUsersAndMatchesExactCase() throws Exception {
        long activity = activity("CODE", 100, 10);
        String raw = "Public-" + UUID.randomUUID(), url = "/users/me/coupons/redeem";
        var input = Map.of("code", raw, "codeKind", "PUBLIC", "maxRedemptions", 2, "expiresAt",
                "2035-01-01T00:00:00+08:00");
        var created = adminClient.request("POST", "/admin/marketing/coupon-rules/" + activity + "/codes",
                Map.of("codes", List.of(input)), true, Map.of("Idempotency-Key", "batch"));
        assertEquals(200, created.statusCode());
        var key = Map.of("Idempotency-Key", "first");
        var malformed = fixture.client.request("POST", url, Map.of("code", " " + raw), true, key);
        assertEquals(400, malformed.statusCode());
        var unknown = fixture.client.request("POST", url, Map.of("code", raw.toLowerCase(Locale.ROOT)), true, key);
        assertEquals("CODE_NOT_USABLE", json.readTree(unknown.body()).path("data").path("reason").asString());
        var body = Map.of("code", raw);
        id(fixture.client.request("POST", url, body, true, key));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CyclicBarrier(2);
            var responses = List.of(fixture.client, fixture.merchantClient).stream().map(client -> pool.submit(() -> {
                gate.await();
                return client.request("POST", url, body, true, Map.of("Idempotency-Key", "last"));
            })).toList();
            var first = responses.getFirst().get(30, TimeUnit.SECONDS);
            var second = responses.getLast().get(30, TimeUnit.SECONDS);
            assertEquals(List.of(200, 409),
                    List.of(first.statusCode(), second.statusCode()).stream().sorted().toList());
        }
        assertEquals(2, count("SELECT redeemed_count FROM marketing_coupon_codes WHERE activity_id=?", activity));
        assertEquals(2, count("SELECT COUNT(*) FROM user_coupons WHERE activity_id=?", activity));
    }

    @Test
    void insufficientBudgetLeavesCodeAvailableForOriginalKeyRetry() throws Exception {
        long activity = activity("CODE", 1, 10);
        String secret = UUID.randomUUID().toString();
        var batch = adminClient.request("POST", "/admin/marketing/coupon-rules/" + activity + "/codes",
                Map.of("codes", List.of(code(secret))), true, Map.of("Idempotency-Key", "batch"));
        assertEquals(200, batch.statusCode());
        String url = "/users/me/coupons/redeem";
        var key = Map.of("Idempotency-Key", "retry");
        var body = Map.of("code", secret);
        var denied = fixture.client.request("POST", url, body, true, key);
        assertEquals("BUDGET_NOT_ENOUGH", json.readTree(denied.body()).path("data").path("reason").asString());
        assertEquals(0, count("SELECT redeemed_count FROM marketing_coupon_codes WHERE activity_id=?", activity));
        for (long budget : jdbc.queryForList("SELECT id FROM marketing_budgets WHERE activity_id=?", Long.class,
                activity)) {
            var adjusted = adminClient.request("POST", "/admin/marketing/budgets/" + budget + "/adjustments",
                    Map.of("deltaAmount", 9, "reason", "补足", "evidenceRef", "单据"), true,
                    Map.of("Idempotency-Key", "repair"));
            assertEquals(200, adjusted.statusCode());
        }
        id(fixture.client.request("POST", url, body, true, key));
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_coupon_grants WHERE activity_id=?", activity));
    }

    @Test
    void compensationReservesBudgetAndReplaysWithoutExposingEvidence() throws Exception {
        long activity = activity("COMPENSATION", 100, 10);
        var body = compensation(fixture.buyer.id(), 2, "凭".repeat(500));
        var issued = grant(activity, "comp:~", body);
        long grant = id(issued);
        assertEquals(grant, id(grant(activity, "comp:~", body)));
        assertEquals(2, json.readTree(issued.body()).path("data").path("coupons").size());
        assertEquals(2, count("SELECT issued_count FROM marketing_coupon_rules WHERE activity_id=?", activity));
        assertEquals(4,
                count("SELECT COUNT(*) FROM marketing_budget_holds h JOIN marketing_budgets b ON b.id=h.budget_id WHERE b.activity_id=?",
                        activity));
        assertEquals(10, count("SELECT SUM(held_amount) FROM marketing_budgets WHERE activity_id=?", activity));
        assertEquals(500,
                jdbc.queryForObject("SELECT CHAR_LENGTH(evidence_reference) FROM marketing_coupon_grants WHERE id=?",
                        Integer.class, grant));
        var mine = fixture.client.get("/users/me/coupons?size=1");
        assertEquals(200, mine.statusCode(), mine.body());
        assertTrue(json.readTree(mine.body()).path("data").path("hasMore").asBoolean());
        assertFalse(mine.body().contains("evidence"));
        assertFalse(mine.body().contains("sourceKey"));
        var foreign = fixture.merchantClient.get("/users/me/coupon-grants");
        assertEquals(0, json.readTree(foreign.body()).path("data").path("items").size());
        assertEquals(409, grant(activity, "comp:~",
                compensation(fixture.catalog.merchant.id(), 2, "凭".repeat(500))).statusCode());
        assertEquals(1,
                count("SELECT COUNT(*) FROM audit_events WHERE target_type='COUPON_GRANT' AND target_id=?", grant));
    }

    @Test
    void missingBudgetOrIssueCapacityRollsBackQuotaGrantCouponsAndAudit() throws Exception {
        long activity = activity("COMPENSATION", 1, 10);
        var denied = grant(activity, "budget", compensation(fixture.buyer.id(), 1, "批准单"));
        assertEquals(409, denied.statusCode(), denied.body());
        assertEquals("BUDGET_NOT_ENOUGH", json.readTree(denied.body()).path("data").path("reason").asString());
        assertEquals(0, count("SELECT COUNT(*) FROM marketing_coupon_grants WHERE activity_id=?", activity));
        assertEquals(0, count("SELECT issued_count FROM marketing_coupon_rules WHERE activity_id=?", activity));
        assertEquals(0, count("SELECT COUNT(*) FROM marketing_user_quotas WHERE activity_id=?", activity));
        long budget = count("SELECT id FROM marketing_budgets WHERE activity_id=? AND party='PLATFORM'", activity);
        for (long id : jdbc.queryForList("SELECT id FROM marketing_budgets WHERE activity_id=?", Long.class,
                activity)) {
            var adjusted = adminClient.request("POST", "/admin/marketing/budgets/" + id + "/adjustments",
                    Map.of("deltaAmount", 9, "reason", "补足", "evidenceRef", "单据"), true,
                    Map.of("Idempotency-Key", "repair"));
            assertEquals(200, adjusted.statusCode(), adjusted.body());
        }
        id(grant(activity, "budget", compensation(fixture.buyer.id(), 1, "批准单")));
        assertEquals(new java.math.BigDecimal("2.50"),
                jdbc.queryForObject("SELECT held_amount FROM marketing_budgets WHERE id=?", java.math.BigDecimal.class,
                        budget));
    }

    @Test
    void concurrentCompensationSameKeyWritesOneGrant() throws Exception {
        long activity = activity("COMPENSATION", 100, 10);
        var body = compensation(fixture.buyer.id(), 2, "批准单");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CyclicBarrier(5);
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 5; i++)
                futures.add(pool.submit(() -> {
                    gate.await();
                    return id(grant(activity, "parallel", body));
                }));
            var ids = new HashSet<Long>();
            for (var f : futures)
                ids.add(f.get(30, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
        }
        assertEquals(2, count("SELECT COUNT(*) FROM user_coupons WHERE activity_id=?", activity));
        assertEquals(4,
                count("SELECT COUNT(*) FROM marketing_budget_entries e JOIN marketing_budgets b ON b.id=e.budget_id WHERE b.activity_id=?",
                        activity));
    }

    Map<String, Object> code(String value) {
        return Map.of("code", value, "codeKind", "SINGLE", "maxRedemptions", 1, "expiresAt",
                "2035-01-01T00:00:00+08:00");
    }

    @Test
    void codeBatchKeepsOrderIsAtomicAndNeverReturnsSecretMaterial() throws Exception {
        long activity = activity("CODE", 100, 10);
        String root = "/admin/marketing/coupon-rules/" + activity + "/codes";
        String prefix = UUID.randomUUID().toString();
        var a = code(prefix + "-A");
        var b = code(prefix + "-B");
        var body = Map.of("codes", List.of(a, b));
        var response = adminClient.request("POST", root, body, true, Map.of("Idempotency-Key", "batch"));
        assertEquals(200, response.statusCode(), response.body());
        var records = json.readTree(response.body()).path("data");
        assertEquals(2, records.size());
        assertFalse(response.body().contains(prefix));
        assertFalse(response.body().contains("Hash"));
        assertEquals(response.body(),
                adminClient.request("POST", root, body, true, Map.of("Idempotency-Key", "batch")).body());
        assertEquals(409, adminClient.request("POST", root, Map.of("codes", List.of(b, a)), true,
                Map.of("Idempotency-Key", "batch")).statusCode());
        assertEquals(409, adminClient.request("POST", root, Map.of("codes", List.of(code(prefix + "-C"), a)), true,
                Map.of("Idempotency-Key", "collision")).statusCode());
        assertEquals(2, count("SELECT COUNT(*) FROM marketing_coupon_codes WHERE activity_id=?", activity));
        long code = records.get(0).path("id").asLong();
        for (int i = 0; i < 2; i++)
            assertEquals(200,
                    adminClient.post("/admin/marketing/coupon-codes/" + code + "/disable", Map.of("reason", "停止使用"))
                            .statusCode());
        assertEquals(1,
                count("SELECT COUNT(*) FROM audit_events WHERE target_type='COUPON_CODE' AND target_id=?", code));
    }

    @Test
    void expiredAvailableCouponReleasesOnlyItsOwnOriginalCommitmentsOnce() throws Exception {
        long activity = activity("COMPENSATION", 100, 10);
        var issued = grant(activity, "expiry", compensation(fixture.buyer.id(), 2, "批准单"));
        id(issued);
        long coupon = json.readTree(issued.body()).path("data").path("coupons").get(0).path("id").asLong();
        jdbc.update("UPDATE user_coupons SET valid_from='2020-01-01',expires_at='2020-02-01' WHERE id=?", coupon);
        coupons.expire(coupon);
        coupons.expire(coupon);
        assertEquals("EXPIRED",
                jdbc.queryForObject("SELECT status FROM user_coupons WHERE id=?", String.class, coupon));
        assertEquals(5, count("SELECT SUM(held_amount) FROM marketing_budgets WHERE activity_id=?", activity));
        assertEquals(2,
                count("SELECT COUNT(*) FROM marketing_budget_entries e JOIN marketing_budgets b ON b.id=e.budget_id WHERE b.activity_id=? AND e.event_kind='RELEASE'",
                        activity));
    }

    @Test
    void compensationAuditFailureRollsBackEveryAccountingEffect() throws Exception {
        long activity = activity("COMPENSATION", 100, 10);
        doThrow(new IllegalStateException("injected coupon audit failure")).when(mapper)
                .audit(eq(admin.id()), eq("COUPON_COMPENSATED"), eq("COUPON_GRANT"), anyLong(), anyString(), any());
        assertEquals(500, grant(activity, "rollback", compensation(fixture.buyer.id(), 2, "批准单")).statusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM user_coupons WHERE activity_id=?", activity));
        assertEquals(0, count("SELECT SUM(held_amount) FROM marketing_budgets WHERE activity_id=?", activity));
        assertEquals(0, count("SELECT issued_count FROM marketing_coupon_rules WHERE activity_id=?", activity));
        reset(mapper);
        id(grant(activity, "rollback", compensation(fixture.buyer.id(), 2, "批准单")));
    }

    @Test
    void publicActivityViewsHideBudgetAuditAndInternalCouponCounters() throws Exception {
        long activity = activity("COMPENSATION", 100, 10);
        try (var anonymous = new HttpAccountClient("http://127.0.0.1:" + port)) {
            var detail = anonymous.get("/marketing/activities/" + activity);
            assertEquals(200, detail.statusCode(), detail.body());
            var data = json.readTree(detail.body()).path("data");
            assertEquals(fixture.sku, data.path("scopes").get(0).path("skuId").asLong());
            for (String hidden : List.of("budgetProposals", "creatorUserId", "reviewReason", "unitCostAmount",
                    "issueLimit", "issuedCount"))
                assertFalse(detail.body().contains(hidden), hidden);
            var list = anonymous.get("/marketing/activities?shopId=" + fixture.catalog.shop + "&skuId=" + fixture.sku +
                    "&type=COUPON&size=1");
            assertEquals(200, list.statusCode(), list.body());
            assertEquals(activity, json.readTree(list.body()).path("data").path("items").get(0).path("id").asLong());
            long draft = id(adminClient.request("POST", "/admin/marketing/activities/" + activity + "/versions",
                    Map.of("reason", "尚未公开"), true, Map.of("Idempotency-Key", "private-version")));
            assertEquals(404, anonymous.get("/marketing/activities/" + draft).statusCode());
        }
    }

    long monthlyPending(CommerceFixture catalog) throws Exception {
        return monthlyPending(catalog, "2090-01-01", "2090-02-01", 100);
    }

    long monthlyPending(CommerceFixture catalog, String start, String end, int budget) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "黄金月券");
        body.put("type", "COUPON");
        body.put("startAt", start + "T00:00:00+08:00");
        body.put("endAt", end + "T00:00:00+08:00");
        body.put("eligibleLevelCodes", List.of("GOLD"));
        body.put("rulesText", "黄金等级当月两张券");
        body.put("scopes", List.of(Map.of("skuId", catalog.sku, "scopeRole", "SALE")));
        body.put("tiers", List.of());
        body.put("couponRule", Map.of("faceAmount", 10, "thresholdAmount", 299, "issueLimit", 100, "perUserLimit", 2,
                "claimEndAt", end + "T00:00:00+08:00", "validityDays", 30, "grantMode", "MEMBERSHIP", "memberLevelCode",
                "GOLD"));
        body.put("budgetProposals",
                List.of(Map.of("party", "MERCHANT", "shopId", catalog.catalog.shop, "budgetAmount", budget,
                        "platformShareBp", 0)));
        long activity = id(adminClient.request("POST", "/admin/marketing/activities", body, true,
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        var agreed = catalog.merchantClient.request("PUT",
                "/merchant/shops/" + catalog.catalog.shop + "/marketing/activities/" + activity + "/agreement",
                Map.of("platformShareBp", 0, "merchantBudgetAmount", budget, "reason", "确认"), true,
                Map.of("If-Match", tag(activity)));
        assertEquals(200, agreed.statusCode(), agreed.body());
        assertEquals(200, adminClient.request("POST", "/admin/marketing/activities/" + activity + "/submit", null, true,
                Map.of("If-Match", tag(activity))).statusCode());
        return activity;
    }

    @Autowired
    com.shiwushop.marketing.MonthlyCouponScheduler monthly;
    @Autowired
    com.shiwushop.marketing.service.impl.MonthlyCouponService monthlyService;

    @org.springframework.boot.test.context.TestConfiguration
    static class ClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        MutableMarketingClock marketingTestClock() {
            return new MutableMarketingClock();
        }
    }

    static class MutableMarketingClock extends java.time.Clock {
        private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> instant =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-11-05T02:00:00Z"));

        void at(java.time.LocalDateTime time) {
            instant.set(time.atZone(getZone()).toInstant());
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneId.of("Asia/Shanghai");
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return java.time.Clock.fixed(instant(), zone);
        }

        @Override
        public java.time.Instant instant() {
            return instant.get();
        }
    }

    @Autowired
    org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduledTasks;

    @Test
    void monthlyRecoveryUsesHistoricalLevelAndOriginalExpiry() throws Exception {
        var registered = scheduledTasks.getScheduledTasks().stream().map(task -> task.getTask().toString()).toList();
        assertTrue(registered.contains("com.shiwushop.marketing.MonthlyCouponScheduler.advance"),
                registered.toString());
        long user = fixture.buyer.id();
        // 金级月初资格，月中已降级；补发不能使用今天的等级。
        jdbc.update(
                "INSERT INTO member_level_changes(user_id,to_level_code,effective_at,operation_key) VALUES (?,'GOLD','2026-09-30 23:59:59',?)",
                user, "test-gold:" + user);
        jdbc.update(
                "INSERT INTO member_level_changes(user_id,from_level_code,to_level_code,effective_at,operation_key) VALUES (?,'GOLD','ORDINARY','2026-11-02 00:00:00',?)",
                user, "test-drop:" + user);
        monthly.advance();
        var url = "/members/me/monthly-benefits?month=2026-11";
        var waiting = fixture.client.get(url);
        assertEquals(200, waiting.statusCode(), waiting.body());
        assertEquals("ATTENTION", json.readTree(waiting.body()).path("data").path("state").asString());
        var expired = fixture.client.get("/members/me/monthly-benefits?month=2026-10");
        assertEquals("EXPIRED", json.readTree(expired.body()).path("data").path("state").asString());
        assertEquals(400, fixture.client.get("/members/me/monthly-benefits?month=2026-12").statusCode());
        var list = adminClient.get(
                "/admin/marketing/coupon-grants?status=ATTENTION&sourceKind=MEMBERSHIP&userId=" + user);
        assertEquals(200, list.statusCode(), list.body());
        long grant = json.readTree(list.body()).path("data").path("items").get(0).path("id").asLong();
        long activity = monthlyPending(fixture, "2026-11-01", "2026-12-01", 10);
        var approved = reviewerClient.request("POST", "/admin/marketing/activities/" + activity + "/decision",
                Map.of("decision", "APPROVE", "reason", "补齐月券模板"), true, Map.of("If-Match", tag(activity)));
        assertEquals(200, approved.statusCode(), approved.body());
        try {
            var retry = adminClient.post("/admin/marketing/coupon-grants/" + grant + "/retry",
                    Map.of("reason", "已补模板"));
            assertEquals(202, retry.statusCode(), retry.body());
            monthly.advance();
            assertEquals(1,
                    count("SELECT COUNT(*) FROM marketing_coupon_grants WHERE id=? AND status='ATTENTION' AND error_code='BUDGET_NOT_ENOUGH'",
                            grant));
            assertEquals(0, count("SELECT COUNT(*) FROM user_coupons WHERE grant_id=?", grant));
            assertEquals(0, count("SELECT issued_count FROM marketing_coupon_rules WHERE activity_id=?", activity));
            assertEquals(0, count("SELECT held_amount FROM marketing_budgets WHERE activity_id=?", activity));
            long budgetId = count("SELECT id FROM marketing_budgets WHERE activity_id=?", activity);
            var adjusted = adminClient.request("POST", "/admin/marketing/budgets/" + budgetId + "/adjustments",
                    Map.of("deltaAmount", 10, "reason", "补齐第二张月券", "evidenceRef", "隔离测试"), true,
                    Map.of("Idempotency-Key", "monthly-topup"));
            assertEquals(200, adjusted.statusCode(), adjusted.body());
            assertEquals(202,
                    adminClient.post("/admin/marketing/coupon-grants/" + grant + "/retry", Map.of("reason", "已补预算"))
                            .statusCode());
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = threads.submit(() -> monthly.advance());
                var second = threads.submit(() -> monthly.advance());
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            }
            var result = fixture.client.get(url);
            var data = json.readTree(result.body()).path("data");
            assertEquals("GRANTED", data.path("state").asString(), result.body());
            assertEquals("GOLD", data.path("eligibleLevelCode").asString());
            assertEquals(2, data.path("coupons").size());
            assertEquals(2,
                    count("SELECT COUNT(*) FROM user_coupons WHERE grant_id=? AND valid_from='2026-11-01' AND expires_at='2026-12-01'",
                            grant));
            assertEquals(200,
                    adminClient.post("/admin/marketing/coupon-grants/" + grant + "/retry", Map.of("reason", "重试核对"))
                            .statusCode());
        } finally {
            var close = adminClient.request("POST", "/admin/marketing/activities/" + activity + "/terminate",
                    Map.of("reason", "释放隔离排期"), true, Map.of("If-Match", tag(activity)));
            assertEquals(200, close.statusCode(), close.body());
        }
    }

    @Test
    void monthBoundaryCreatesOneHistoricalEntitlementAtMidnight() {
        long user = fixture.buyer.id();
        jdbc.update("INSERT INTO member_level_changes(user_id,to_level_code,effective_at,operation_key) " +
                "VALUES (?,'GOLD','2026-11-30 23:59:59',?)", user, "month-edge:" + user);
        String key = "MEMBER:2026-12";
        clock.at(java.time.LocalDateTime.of(2026, 11, 30, 23, 59, 59, 999_000_000));
        monthly.advance();
        assertEquals(0, count("SELECT COUNT(*) FROM marketing_coupon_grants WHERE user_id=? AND source_key=?", user,
                key));
        clock.at(java.time.LocalDateTime.of(2026, 12, 1, 0, 0));
        monthlyService.establish(user, java.time.YearMonth.of(2026, 12));
        clock.at(java.time.LocalDateTime.of(2026, 12, 1, 0, 0, 0, 1_000_000));
        monthly.advance();
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_coupon_grants WHERE user_id=? AND source_key=? " +
                "AND eligible_level_code='GOLD' AND entitled_count=2 AND face_amount=10 " +
                "AND threshold_amount=299 AND expires_at='2026-12-31 00:00:00'", user, key));
    }

    @Test
    void sameLevelMonthlyTemplatesCannotOverlapEvenAcrossDifferentShopsAndSkus() throws Exception {
        var other = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        var created = new ArrayList<Long>();
        try {
            long first = monthlyPending(fixture), second = monthlyPending(other);
            created.add(first);
            created.add(second);
            String firstTag = tag(first), secondTag = tag(second);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var gate = new CyclicBarrier(2);
                var decision = Map.of("decision", "APPROVE", "reason", "黄金月券审批");
                var a = pool.submit(() -> {
                    gate.await();
                    return reviewerClient.request("POST", "/admin/marketing/activities/" + first + "/decision",
                            decision, true, Map.of("If-Match", firstTag));
                });
                var b = pool.submit(() -> {
                    gate.await();
                    return reviewerClient.request("POST", "/admin/marketing/activities/" + second + "/decision",
                            decision, true, Map.of("If-Match", secondTag));
                });
                var responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
                assertEquals(List.of(200, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList(),
                        responses.toString());
                var denied = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
                assertEquals("ACTIVITY_OVERLAP", json.readTree(denied.body()).path("data").path("reason").asString());
            }
        } finally {
            for (long activity : created)
                if ("APPROVED".equals(
                        jdbc.queryForObject("SELECT status FROM marketing_activities WHERE id=?", String.class,
                                activity))) {
                    var closed = adminClient.request("POST", "/admin/marketing/activities/" + activity + "/terminate",
                            Map.of("reason", "隔离样本完成，释放测试排期"), true, Map.of("If-Match", tag(activity)));
                    assertEquals(200, closed.statusCode(), closed.body());
                }
            other.client.close();
            other.merchantClient.close();
        }
    }

    long priceActivity(String type, long skuId) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", type);
        body.put("type", type);
        body.put("startAt", "2020-01-01T00:00:00+08:00");
        body.put("endAt", "2035-12-01T00:00:00+08:00");
        body.put("eligibleLevelCodes", List.of("ORDINARY", "SILVER", "GOLD", "PLATINUM"));
        body.put("rulesText", "指定商品折扣");
        var scope = new LinkedHashMap<String, Object>();
        scope.put("skuId", skuId);
        scope.put("scopeRole", "SALE");
        if (type.equals("MEMBER_PRICE"))
            scope.put("memberPrices", Map.of("SILVER", 100, "GOLD", 95, "PLATINUM", 90));
        if (type.equals("LIMITED_PRICE"))
            scope.put("activityPrice", 90);
        body.put("scopes", List.of(scope));
        body.put("tiers", type.equals("FULL_REDUCTION") ? List.of(
                Map.of("thresholdAmount", 180, "reductionAmount", 10)) : List.of());
        body.put("budgetProposals",
                List.of(Map.of("party", "MERCHANT", "shopId", fixture.catalog.shop, "budgetAmount", 1000,
                        "platformShareBp", 0)));
        return publish(body, 1000, 0);
    }

    @Test
    void actualQuoteUsesConfirmedCalculationOrderWithoutOccupyingAnyResource() throws Exception {
        long pricedProduct = fixture.catalog.product("报价商品", "ON_SALE", "100.00", 20);
        long pricedSku = fixture.catalog.sku(pricedProduct);
        String skuPath = "/merchant/products/" + pricedProduct + "/skus/" + pricedSku;
        var growth = adminClient.request("POST", "/admin/members/" + fixture.buyer.id() + "/growth-adjustments",
                Map.of("deltaAmount", 5000, "reason", "报价资格夹具", "evidenceRef", "隔离用例"), true,
                Map.of("Idempotency-Key", "quote-gold"));
        assertEquals(200, growth.statusCode(), growth.body());
        priceActivity("MEMBER_PRICE", pricedSku);
        priceActivity("LIMITED_PRICE", pricedSku);
        long reduction = priceActivity("FULL_REDUCTION", pricedSku);
        long couponActivity = activity("COMPENSATION", 100, 10, 170, List.of(pricedSku));
        var granted = grant(couponActivity, "quote", compensation(fixture.buyer.id(), 1, "隔离报价"));
        id(granted);
        long coupon = json.readTree(granted.body()).path("data").path("coupons").get(0).path("id").asLong();
        var body = Map.<String, Object>of("source", "DIRECT", "skuId", pricedSku, "quantity", 2, "addressId",
                fixture.address,
                "shopChoices",
                List.of(Map.of("shopId", fixture.catalog.shop, "couponMode", "SELECT", "userCouponId", coupon,
                        "reductionActivityId", reduction)));
        var quote = fixture.client.post("/checkouts/quote", body);
        assertEquals(200, quote.statusCode(), quote.body());
        var data = json.readTree(quote.body()).path("data");
        assertEquals(165, data.path("totalAmount").asInt());
        assertEquals(200, data.path("originalAmount").asInt());
        assertEquals(35, data.path("discountAmount").asInt());
        assertEquals(16500, data.path("rewardPreview").path("pointUnits").asLong());
        assertEquals("GOLD", data.path("memberLevelCode").asString());
        try (var anonymous = new HttpAccountClient("http://127.0.0.1:" + port)) {
            var detail = anonymous.get("/products/" + pricedProduct);
            assertEquals(200, detail.statusCode(), detail.body());
            assertEquals(100, json.readTree(detail.body()).path("data").path("skus").get(0).path("price").asInt());
            for (int read = 0; read < 2; read++) {
                var listing = anonymous.get("/products?shopId=" + fixture.catalog.shop);
                assertEquals(200, listing.statusCode(), listing.body());
                assertEquals(100, json.readTree(listing.body()).path("data").path("items").get(0)
                        .path("startingPrice").asInt());
            }
        }
        assertEquals(data.path("quoteHash").asString(),
                json.readTree(fixture.client.post("/checkouts/quote", body).body()).path("data").path("quoteHash")
                        .asString());
        assertEquals(0, count("SELECT COUNT(*) FROM orders WHERE user_id=?", fixture.buyer.id()));
        assertEquals(20, count("SELECT stock FROM skus WHERE id=?", pricedSku));
        assertEquals("AVAILABLE",
                jdbc.queryForObject("SELECT status FROM user_coupons WHERE id=?", String.class, coupon));
        assertEquals(5, count("SELECT SUM(held_amount) FROM marketing_budgets WHERE activity_id=?", couponActivity));
        assertEquals(0, count("SELECT COUNT(*) FROM marketing_user_quotas WHERE user_id=? AND quota_kind='BUY'",
                fixture.buyer.id()));
        assertEquals(404, fixture.merchantClient.post("/checkouts/quote", body).statusCode());
        String mediaKey = "quote-" + UUID.randomUUID() + ".png";
        long media = fixture.catalog.insert("""
                INSERT INTO media_assets(uploaded_by_user_id,storage_key,mime_type,byte_size,width,height,sha256,status)
                VALUES(?,?,'image/png',1,1,1,UNHEX(SHA2(?,256)),'READY')
                """, fixture.catalog.merchant.id(), mediaKey, mediaKey);
        fixture.catalog.trackMedia(media, java.nio.file.Path.of(".").toAbsolutePath().normalize());
        jdbc.update("INSERT INTO product_images(product_id,media_id,is_cover) VALUES(?,?,1)", pricedProduct, media);
        assertEquals(200, fixture.merchantClient.request("PUT", "/merchant/products/" + pricedProduct + "/status",
                Map.of("status", "OFF_SALE"), true, Map.of()).statusCode());
        assertEquals(200,
                fixture.merchantClient.request("PUT", skuPath, Map.of("price", 101, "stock", 20), true, Map.of())
                        .statusCode());
        long revision = count("SELECT revision FROM products WHERE id=?", pricedProduct);
        assertEquals(200, fixture.merchantClient.post("/merchant/products/" + pricedProduct + "/submit",
                Map.of("revision", revision)).statusCode());
        assertEquals(200, adminClient.post("/admin/products/" + pricedProduct + "/decision",
                Map.of("revision", revision, "approved", true)).statusCode());
        var changed = json.readTree(fixture.client.post("/checkouts/quote", body).body()).path("data");
        assertEquals(165, changed.path("totalAmount").asInt());
        assertNotEquals(data.path("quoteHash").asString(), changed.path("quoteHash").asString());
        var checkout = new LinkedHashMap<String, Object>(body);
        checkout.remove("source");
        checkout.put("confirmedTotal", 165);
        checkout.put("idempotencyKey", "confirmed-promotion");
        checkout.put("quoteHash", data.path("quoteHash").asString());
        var stale = fixture.client.post("/checkouts/direct", checkout);
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals(changed.path("quoteHash").asString(),
                json.readTree(stale.body()).path("data").path("quote").path("quoteHash").asString());
        checkout.put("quoteHash", changed.path("quoteHash").asString());
        var placed = fixture.client.post("/checkouts/direct", checkout);
        assertEquals(200, placed.statusCode(), placed.body());
        long order = json.readTree(placed.body()).path("data").path("orders").get(0).path("id").asLong();
        doReturn(false).when(marketingSettings).enabled();
        assertEquals(placed.body(), fixture.client.post("/checkouts/direct", checkout).body());
        checkout.put("idempotencyKey", "disabled-new-promotion");
        var blocked = fixture.client.post("/checkouts/direct", checkout);
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertEquals(1,
                count("SELECT COUNT(*) FROM orders WHERE id=? AND original_amount=202 AND discount_amount=37 AND total_amount=165 AND member_level_code='GOLD' AND pricing_version=1",
                        order));
        assertEquals(1,
                count("SELECT COUNT(*) FROM order_items WHERE order_id=? AND original_unit_price=101 AND unit_price=90 AND payable_amount=165",
                        order));
        assertEquals(3,
                count("SELECT COUNT(*) FROM order_promotion_allocations a JOIN order_items i ON i.id=a.order_item_id WHERE order_id=?",
                        order));
        assertEquals(1,
                count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND reserved_order_id=? AND status='RESERVED'",
                        coupon, order));
        assertEquals(5, count("SELECT SUM(held_amount) FROM marketing_budgets WHERE activity_id=?", couponActivity));
        var cancelled = fixture.client.post("/orders/" + order + "/cancel", null);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals(1,
                count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND reserved_order_id IS NULL AND status='AVAILABLE'",
                        coupon));
        assertEquals(5, count("SELECT SUM(held_amount) FROM marketing_budgets WHERE activity_id=?", couponActivity));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DIRECT", "CART"})
    void acceptedQuoteFlowsThroughPaymentAndFullRefund(String source) throws Exception {
        PaymentHttpFixture.configure(gateway);
        long activity = activity("COMPENSATION", 100, 10, 5);
        var granted = grant(activity, "accept-quote", compensation(fixture.buyer.id(), 1, "19.90减5"));
        id(granted);
        long coupon = json.readTree(granted.body()).path("data").path("coupons").get(0).path("id").asLong();
        var body = new LinkedHashMap<String, Object>();
        body.put("source", source);
        body.put("addressId", fixture.address);
        if (source.equals("DIRECT")) {
            body.put("skuId", fixture.sku);
            body.put("quantity", 1);
        } else {
            body.put("cartItemIds",
                    List.of(id(fixture.client.post("/cart/items", Map.of("skuId", fixture.sku, "quantity", 1)))));
        }
        body.put("shopChoices",
                List.of(Map.of("shopId", fixture.catalog.shop, "couponMode", "SELECT", "userCouponId", coupon)));
        var quote = fixture.client.post("/checkouts/quote", body);
        assertEquals(200, quote.statusCode(), quote.body());
        assertEquals("14.90", json.readTree(quote.body()).path("data").path("totalAmount").decimalValue().setScale(2)
                .toPlainString());
        body.remove("source");
        body.put("quoteHash", json.readTree(quote.body()).path("data").path("quoteHash").asString());
        body.put("confirmedTotal", "14.90");
        body.put("idempotencyKey", "accept-quote");
        var placed = fixture.client.post(source.equals("DIRECT") ? "/checkouts/direct" : "/checkouts", body);
        assertEquals(200, placed.statusCode(), placed.body());
        long order = json.readTree(placed.body()).path("data").path("orders").get(0).path("id").asLong();
        long item = count("SELECT id FROM order_items WHERE order_id=?", order);
        assertEquals(1,
                count("SELECT COUNT(*) FROM orders WHERE id=? AND total_amount=14.90 AND pricing_version=1", order));
        assertEquals(1,
                count("SELECT COUNT(*) FROM order_items WHERE id=? AND original_unit_price=19.90 AND discount_amount=5 AND payable_amount=14.90",
                        item));
        assertEquals(1,
                count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND reserved_order_id=? AND status='RESERVED'",
                        coupon, order));
        var payment = fixture.client.post("/orders/" + order + "/payment", Map.of());
        assertEquals(200, payment.statusCode(), payment.body());
        var notice = PaymentHttpFixture.notice(jdbc, order);
        notice.put("total_amount", "14.90");
        for (int i = 0; i < 2; i++)
            assertEquals("success", fixture.client.form("/payments/alipay/notify", notice).body());
        assertEquals(1,
                count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND used_order_id=? AND status='USED'", coupon,
                        order));
        assertEquals(2,
                count("SELECT COUNT(*) FROM marketing_budgets WHERE activity_id=? AND held_amount=0 AND spent_amount=2.50",
                        activity));
        long sale = id(fixture.client.request("POST", "/orders/" + order + "/after-sales",
                Map.of("type", "REFUND_ONLY", "reason", "整单退款",
                        "items", List.of(Map.of("orderItemId", item, "quantity", 1))), true,
                Map.of("Idempotency-Key", "accept-refund")));
        String path = "/merchant/shops/" + fixture.catalog.shop + "/after-sales/" + sale;
        assertEquals(200, fixture.merchantClient.post(path + "/review", Map.of("approved", true)).statusCode());
        var amount = new java.math.BigDecimal("14.90");
        when(gateway.refund(anyString(), anyString(), anyString(), any())).thenAnswer(c ->
                new com.shiwushop.payment.service.RefundGateway.Result(true, c.getArgument(0), c.getArgument(1),
                        c.getArgument(2), amount));
        for (int i = 0; i < 2; i++) {
            var result = fixture.merchantClient.post(path + "/refund", Map.of());
            assertEquals(200, result.statusCode(), result.body());
        }
        assertEquals(1,
                count("SELECT COUNT(*) FROM after_sale_cases WHERE id=? AND status='REFUNDED' AND refund_amount=14.90",
                        sale));
        assertEquals(2,
                count("SELECT COUNT(*) FROM marketing_refund_allocations r JOIN after_sale_items ai ON ai.id=r.after_sale_item_id WHERE ai.case_id=?",
                        sale));
        assertEquals(2,
                count("SELECT COUNT(*) FROM marketing_budgets WHERE activity_id=? AND held_amount=2.50 AND spent_amount=0",
                        activity));
        assertEquals(1,
                count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND status='AVAILABLE' AND used_order_id IS NULL",
                        coupon));
    }

    @Test
    void paymentAndTwoRefundsUseOriginalSharesAndReturnCouponOnce() throws Exception {
        PaymentHttpFixture.configure(gateway);
        long activity = activity("COMPENSATION", 100, 10);
        var issued = grant(activity, "three", compensation(fixture.buyer.id(), 1, "成交测试"));
        id(issued);
        long coupon = json.readTree(issued.body()).path("data").path("coupons").get(0).path("id").asLong();
        var body = new LinkedHashMap<String, Object>();
        body.put("source", "DIRECT");
        body.put("quantity", 3);
        body.put("skuId", fixture.sku);
        body.put("addressId", fixture.address);
        body.put("shopChoices",
                List.of(Map.of("shopId", fixture.catalog.shop, "couponMode", "SELECT", "userCouponId", coupon)));
        var quote = fixture.client.post("/checkouts/quote", body);
        assertEquals(200, quote.statusCode(), quote.body());
        body.remove("source");
        body.put("quoteHash", json.readTree(quote.body()).path("data").path("quoteHash").asString());
        body.put("confirmedTotal", "54.70");
        body.put("idempotencyKey", "refund-order");
        var checkout = fixture.client.post("/checkouts/direct", body);
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = json.readTree(checkout.body()).path("data").path("orders").get(0).path("id").asLong();
        long item = count("SELECT id FROM order_items WHERE order_id=?", order);
        var payment = fixture.client.post("/orders/" + order + "/payment", Map.of());
        assertEquals(200, payment.statusCode(), payment.body());
        var notice = PaymentHttpFixture.notice(jdbc, order);
        notice.put("total_amount", "54.70");
        for (int i = 0; i < 2; i++)
            assertEquals("success", fixture.client.form("/payments/alipay/notify", notice).body());
        assertEquals(1,
                count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND status='USED' AND used_order_id=?", coupon,
                        order));
        assertEquals(5, count("SELECT SUM(spent_amount) FROM marketing_budgets WHERE activity_id=?", activity));
        assertEquals(5470,
                count("SELECT available_point_units FROM member_accounts WHERE user_id=?", fixture.buyer.id()));
        for (int qty : List.of(1, 2)) {
            var request = Map.of("type", "REFUND_ONLY", "reason", "退货测试", "items",
                    List.of(Map.of("orderItemId", item, "quantity", qty)));
            long sale = id(fixture.client.request("POST", "/orders/" + order + "/after-sales", request, true,
                    Map.of("Idempotency-Key", "refund-" + qty)));
            var expected = new java.math.BigDecimal(qty == 1 ? "18.24" : "36.46");
            assertEquals(expected, jdbc.queryForObject("SELECT refund_amount FROM after_sale_cases WHERE id=?",
                    java.math.BigDecimal.class, sale));
            String path = "/merchant/shops/" + fixture.catalog.shop + "/after-sales/" + sale;
            var review = fixture.merchantClient.post(path + "/review", Map.of("approved", true));
            assertEquals(200, review.statusCode(), review.body());
            when(gateway.refund(anyString(), anyString(), anyString(), any())).thenAnswer(c ->
                    new com.shiwushop.payment.service.RefundGateway.Result(true, c.getArgument(0), c.getArgument(1),
                            c.getArgument(2), expected));
            for (int i = 0; i < 2; i++) {
                var refund = fixture.merchantClient.post(path + "/refund", Map.of());
                assertEquals(200, refund.statusCode(), refund.body());
            }
            assertEquals(qty == 1 ? "USED" : "AVAILABLE",
                    jdbc.queryForObject("SELECT status FROM user_coupons WHERE id=?", String.class, coupon));
        }
        assertEquals(0, count("SELECT SUM(spent_amount) FROM marketing_budgets WHERE activity_id=?", activity));
        assertEquals(5, count("SELECT SUM(held_amount) FROM marketing_budgets WHERE activity_id=?", activity));
        assertEquals(0, count("SELECT available_point_units FROM member_accounts WHERE user_id=?", fixture.buyer.id()));
        assertEquals(4,
                count("SELECT COUNT(*) FROM marketing_refund_allocations r JOIN after_sale_items ai ON ai.id=r.after_sale_item_id WHERE ai.order_item_id=?",
                        item));
    }

    @Test
    void returningAllCashWaitsForTheLastZeroPayableSaleItemBeforeReturningCoupon() throws Exception {
        PaymentHttpFixture.configure(gateway);
        long zeroSku = fixture.catalog.sku(fixture.catalog.product("零现金商品", "ON_SALE", "5.00", 20));
        long otherSku = fixture.catalog.sku(fixture.catalog.product("现金商品", "ON_SALE", "19.90", 20));
        long activity = activity("COMPENSATION", 100, 10, 5, List.of(zeroSku));
        var issued = grant(activity, "mixed", compensation(fixture.buyer.id(), 1, "混合订单"));
        id(issued);
        long coupon = json.readTree(issued.body()).path("data").path("coupons").get(0).path("id").asLong();
        var cartIds = new ArrayList<Long>();
        for (long sku : List.of(otherSku, zeroSku))
            cartIds.add(id(fixture.client.post("/cart/items", Map.of("skuId", sku, "quantity", 1))));
        var body = new LinkedHashMap<String, Object>();
        body.put("source", "CART");
        body.put("cartItemIds", cartIds);
        body.put("addressId", fixture.address);
        body.put("shopChoices",
                List.of(Map.of("shopId", fixture.catalog.shop, "couponMode", "SELECT", "userCouponId", coupon)));
        var quote = fixture.client.post("/checkouts/quote", body);
        assertEquals(200, quote.statusCode(), quote.body());
        body.remove("source");
        body.put("quoteHash", json.readTree(quote.body()).path("data").path("quoteHash").asString());
        body.put("confirmedTotal", "19.90");
        body.put("idempotencyKey", "mixed-order");
        var checkout = fixture.client.post("/checkouts", body);
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = json.readTree(checkout.body()).path("data").path("orders").get(0).path("id").asLong();
        var unpaid = orderRewards(order);
        assertEquals("WAIT_PAYMENT", unpaid.path("pointStatus").asString());
        assertEquals("WAIT_RECEIPT", unpaid.path("growthStatus").asString());
        assertEquals(0, unpaid.path("creditedPointUnits").asLong());
        var payment = fixture.client.post("/orders/" + order + "/payment", Map.of());
        assertEquals(200, payment.statusCode(), payment.body());
        var notice = PaymentHttpFixture.notice(jdbc, order);
        notice.put("total_amount", "19.90");
        assertEquals("success", fixture.client.form("/payments/alipay/notify", notice).body());
        var paid = orderRewards(order);
        assertEquals("APPLIED", paid.path("pointStatus").asString());
        assertEquals(1990, paid.path("creditedPointUnits").asLong());
        assertTrue(paid.path("exclusionReason").isNull());
        // 给成长到期处理器提供已过七天的收货夹具，入账与后续扣回均走真实账务事务。
        transactions.executeWithoutResult(tx -> {
            var locked = orders.selectForUpdate(order);
            rewards.growthDue(locked.getUserId(), order, java.time.LocalDateTime.of(2026, 10, 28, 10, 0), false,
                    orderItems.rewardLines(order));
        });
        long source = count("SELECT id FROM member_growth_entries WHERE user_id=? AND event_kind='EARN'",
                fixture.buyer.id());
        var adjust = adminClient.request("POST", "/admin/members/" + fixture.buyer.id() + "/growth-adjustments",
                Map.of("deltaAmount", "-2.00", "sourceEntryId", source, "reason", "来源扣减", "evidenceRef",
                        "隔离测试"),
                true, Map.of("Idempotency-Key", "order-summary"));
        assertEquals(200, adjust.statusCode(), adjust.body());
        var adjusted = orderRewards(order);
        assertEquals("APPLIED", adjusted.path("growthStatus").asString());
        assertEquals("19.90", adjusted.path("creditedGrowthAmount").decimalValue().setScale(2).toPlainString());
        assertEquals(2, adjusted.path("clawedBackGrowthAmount").asInt());
        when(gateway.refund(anyString(), anyString(), anyString(), any())).thenAnswer(c ->
                new com.shiwushop.payment.service.RefundGateway.Result(true, c.getArgument(0), c.getArgument(1),
                        c.getArgument(2), new java.math.BigDecimal("19.90")));
        for (long sku : List.of(otherSku, zeroSku)) {
            long item = count("SELECT id FROM order_items WHERE order_id=? AND sku_id=?", order, sku);
            var request = Map.of("type", "REFUND_ONLY", "reason", "分行确认", "items",
                    List.of(Map.of("orderItemId", item, "quantity", 1)));
            long sale = id(fixture.client.request("POST", "/orders/" + order + "/after-sales", request, true,
                    Map.of("Idempotency-Key", "return-" + sku)));
            String path = "/merchant/shops/" + fixture.catalog.shop + "/after-sales/" + sale;
            var review = fixture.merchantClient.post(path + "/review", Map.of("approved", true));
            assertEquals(200, review.statusCode(), review.body());
            var refund = fixture.merchantClient.post(path + "/refund", Map.of());
            assertEquals(200, refund.statusCode(), refund.body());
            assertEquals(sku == otherSku ? 1 : 0, count("SELECT COUNT(*) FROM refund_attempts WHERE case_id=?", sale));
            assertEquals(sku == otherSku ? "USED" : "AVAILABLE",
                    jdbc.queryForObject("SELECT status FROM user_coupons WHERE id=?", String.class, coupon));
        }
        var refunded = orderRewards(order);
        assertEquals(1990, refunded.path("creditedPointUnits").asLong());
        assertEquals(1990, refunded.path("clawedBackPointUnits").asLong());
        assertEquals(refunded.path("creditedGrowthAmount").decimalValue(),
                refunded.path("clawedBackGrowthAmount").decimalValue());
        assertEquals("19.90", refunded.path("creditedGrowthAmount").decimalValue().setScale(2).toPlainString());
        assertTrue(refunded.path("exclusionReason").isNull());
        verifyPendingStatuses(order, otherSku);
    }

    private JsonNode orderRewards(long order) throws Exception {
        String merchant = "/merchant/shops/" + fixture.catalog.shop + "/orders";
        var detail = fixture.client.get("/orders/" + order);
        assertEquals(200, detail.statusCode(), detail.body());
        var buyer = json.readTree(detail.body()).path("data");
        assertEquals(0, buyer.path("pointUnits").asInt(-1));
        var summary = buyer.path("rewardSummary");
        assertEquals(Set.of("pointStatus", "growthStatus", "creditedPointUnits", "creditedGrowthAmount",
                "clawedBackPointUnits",
                "clawedBackGrowthAmount", "exclusionReason"), summary.propertyNames());
        for (var response : List.of(fixture.client.get("/orders?size=1"),
                fixture.merchantClient.get(merchant + "?size=1"),
                fixture.merchantClient.get(merchant + "/" + order))) {
            assertEquals(200, response.statusCode(), response.body());
            var data = json.readTree(response.body()).path("data");
            var row = data.has("items") && !data.has("id") ? data.path("items").get(0) : data;
            assertEquals(order, row.path("id").asLong());
            assertEquals(summary, row.path("rewardSummary"));
        }
        return summary;
    }

    private void verifyPendingStatuses(long order, long paidSku) {
        // 在可回滚的查询夹具内单独制造同一订单的不同任务组合。
        transactions.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            long paidItem = count("SELECT id FROM order_items WHERE order_id=? AND sku_id=?", order, paidSku);
            String update = "UPDATE member_reward_tasks SET status=?,exclusion_reason=NULL WHERE order_item_id=? AND reward_kind='GROWTH'";
            jdbc.update(update, "READY", paidItem);
            long other = count("SELECT id FROM order_items WHERE order_id=? AND sku_id<>?", order, paidSku);
            jdbc.update(update, "APPLIED", other);
            assertEquals("READY", summaryAfterFixtureUpdate(order).growthStatus());
            jdbc.update(update, "WAIT_AFTERSALE", other);
            assertEquals("WAIT_AFTERSALE", summaryAfterFixtureUpdate(order).growthStatus());
            jdbc.update(update, "WAIT_RECEIPT", paidItem);
            assertEquals("WAIT_RECEIPT", summaryAfterFixtureUpdate(order).growthStatus());
            jdbc.update(update, "ATTENTION", other);
            assertEquals("ATTENTION", summaryAfterFixtureUpdate(order).growthStatus());
            jdbc.update(
                    "UPDATE member_reward_tasks SET status='EXCLUDED',exclusion_reason='FULL_REFUND' WHERE order_item_id=?",
                    paidItem);
            jdbc.update(
                    "UPDATE member_reward_tasks SET status='EXCLUDED',exclusion_reason='NON_CASH_ITEM' WHERE order_item_id=?",
                    other);
            var excluded = summaryAfterFixtureUpdate(order);
            assertEquals("EXCLUDED", excluded.pointStatus());
            assertEquals("EXCLUDED", excluded.growthStatus());
            assertNull(excluded.exclusionReason());
        });
    }

    private OrderRewardSummary summaryAfterFixtureUpdate(long order) {
        // JDBC 修改夹具绕过 MyBatis；同事务下一次查询需先清除会话缓存。
        session.clearCache();
        return orders.rewardSummaries(List.of(order)).get(order);
    }

    @Test
    void zeroCashIsSettledAndRefundedWithoutAnyGatewayAttempt() throws Exception {
        long zeroSku = fixture.catalog.sku(fixture.catalog.product("零现金商品", "ON_SALE", "5.00", 20));
        long nextSku = fixture.catalog.sku(fixture.catalog.product("限购商品", "ON_SALE", "6.00", 20));
        long activity = activity("COMPENSATION", 100, 10, 5, List.of(zeroSku, nextSku));
        var issued = grant(activity, "zero", compensation(fixture.buyer.id(), 1, "零现金测试"));
        id(issued);
        long coupon = json.readTree(issued.body()).path("data").path("coupons").get(0).path("id").asLong();
        var body = new LinkedHashMap<String, Object>();
        body.put("source", "DIRECT");
        body.put("quantity", 1);
        body.put("skuId", zeroSku);
        body.put("addressId", fixture.address);
        body.put("shopChoices",
                List.of(Map.of("shopId", fixture.catalog.shop, "couponMode", "SELECT", "userCouponId", coupon)));
        var quote = fixture.client.post("/checkouts/quote", body);
        assertEquals(200, quote.statusCode(), quote.body());
        body.remove("source");
        body.put("quoteHash", json.readTree(quote.body()).path("data").path("quoteHash").asString());
        body.put("confirmedTotal", "0.00");
        body.put("idempotencyKey", "zero-order");
        var checkout = fixture.client.post("/checkouts/direct", body);
        assertEquals(200, checkout.statusCode(), checkout.body());
        var placed = json.readTree(checkout.body()).path("data").path("orders").get(0);
        long order = placed.path("id").asLong(), item = count("SELECT id FROM order_items WHERE order_id=?", order);
        assertEquals("ZERO_CASH", placed.path("settlementKind").asString());
        assertEquals("PAID", placed.path("status").asString());
        assertEquals(409, fixture.client.post("/orders/" + order + "/payment", Map.of()).statusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", order));
        assertEquals(2,
                count("SELECT COUNT(*) FROM member_reward_tasks WHERE order_item_id=? AND exclusion_reason='NON_CASH_ITEM'",
                        item));
        var excludedReward = orderRewards(order);
        assertEquals("EXCLUDED", excludedReward.path("pointStatus").asString());
        assertEquals("EXCLUDED", excludedReward.path("growthStatus").asString());
        assertEquals("NON_CASH_ITEM", excludedReward.path("exclusionReason").asString());
        long sale = id(fixture.client.request("POST", "/orders/" + order + "/after-sales",
                Map.of("type", "REFUND_ONLY", "reason", "退货",
                        "items", List.of(Map.of("orderItemId", item, "quantity", 1))), true,
                Map.of("Idempotency-Key", "zero-refund")));
        String path = "/merchant/shops/" + fixture.catalog.shop + "/after-sales/" + sale;
        assertEquals(200, fixture.merchantClient.post(path + "/review", Map.of("approved", true)).statusCode());
        var refund = fixture.merchantClient.post(path + "/refund", Map.of());
        assertEquals(200, refund.statusCode(), refund.body());
        assertEquals(0, count("SELECT COUNT(*) FROM refund_attempts WHERE case_id=?", sale));
        assertEquals(1, count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND status='AVAILABLE'", coupon));
        long limited = publish(
                Map.of("name", "零现金限购", "type", "LIMITED_PRICE", "startAt", "2020-01-01T00:00:00+08:00",
                        "endAt", "2035-01-01T00:00:00+08:00", "eligibleLevelCodes", List.of("ORDINARY"), "rulesText",
                        "每人一件",
                        "scopes",
                        List.of(Map.of("skuId", nextSku, "scopeRole", "SALE", "activityPrice", 5, "perUserLimit",
                                1)),
                        "tiers", List.of(), "budgetProposals",
                        List.of(Map.of("party", "MERCHANT", "shopId", fixture.catalog.shop,
                                "budgetAmount", 10, "platformShareBp", 0))), 10, 0);
        body.remove("quoteHash");
        body.remove("confirmedTotal");
        body.remove("idempotencyKey");
        body.put("source", "DIRECT");
        body.put("skuId", nextSku);
        var nextQuote = fixture.client.post("/checkouts/quote", body);
        assertEquals(200, nextQuote.statusCode(), nextQuote.body());
        body.remove("source");
        body.put("quoteHash", json.readTree(nextQuote.body()).path("data").path("quoteHash").asString());
        body.put("confirmedTotal", "0.00");
        body.put("idempotencyKey", "zero-cancel");
        var next = fixture.client.post("/checkouts/direct", body);
        assertEquals(200, next.statusCode(), next.body());
        long cancelled = json.readTree(next.body()).path("data").path("orders").get(0).path("id").asLong();
        var paidAt = jdbc.queryForObject("SELECT paid_at FROM orders WHERE id=?", java.time.LocalDateTime.class,
                cancelled);
        for (int i = 0; i < 2; i++) {
            var stop = fixture.client.post("/orders/" + cancelled + "/cancel", null);
            assertEquals(200, stop.statusCode(), stop.body());
            assertEquals("CANCELLED", json.readTree(stop.body()).path("data").path("status").asString());
        }
        var saved = fixture.client.get("/orders/" + cancelled);
        assertEquals(200, saved.statusCode(), saved.body());
        var shipment = fixture.merchantClient.post(
                "/merchant/shops/" + fixture.catalog.shop + "/orders/" + cancelled + "/shipment",
                Map.of("carrierName", "本地承运", "trackingNo", "ZERO-CANCEL"));
        assertEquals(409, shipment.statusCode(), shipment.body());
        assertEquals(0, count("SELECT COUNT(*) FROM shipments WHERE order_id=?", cancelled));
        assertEquals("STOPPED", json.readTree(saved.body()).path("data").path("fulfillmentState").asString());
        assertEquals(1,
                count("SELECT COUNT(*) FROM orders WHERE id=? AND status='PAID' AND paid_at IS NOT NULL", cancelled));
        assertEquals(paidAt,
                jdbc.queryForObject("SELECT paid_at FROM orders WHERE id=?", java.time.LocalDateTime.class, cancelled));
        assertEquals(1, count("SELECT COUNT(*) FROM user_coupons WHERE id=? AND status='AVAILABLE'", coupon));
        assertEquals(1,
                count("SELECT consumed_count FROM marketing_user_quotas WHERE activity_id=? AND user_id=? AND quota_kind='BUY'",
                        limited, fixture.buyer.id()));
        assertEquals(20, count("SELECT stock FROM skus WHERE id=?", nextSku));
        assertEquals(1,
                count("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=? AND status='REFUNDED'", cancelled));
    }

    @Test
    void quoteRejectsUnknownServerPriceAndMixedSourceFieldsBeforeReadingResources() throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("source", "DIRECT");
        body.put("skuId", fixture.sku);
        body.put("quantity", 1);
        body.put("addressId", fixture.address);
        body.put("unitPrice", 0);
        assertEquals(400, fixture.client.post("/checkouts/quote", body).statusCode());
        body.remove("unitPrice");
        body.put("cartItemIds", List.of(1));
        assertEquals(400, fixture.client.post("/checkouts/quote", body).statusCode());
    }
}
