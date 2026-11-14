package com.shiwushop.integration.member;

import com.shiwushop.member.service.MemberRewardService;
import com.shiwushop.member.service.MemberMaintenanceService;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.service.MemberTaskService;
import com.shiwushop.trade.service.OrderRewardService;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.payment.service.RefundGateway;
import com.shiwushop.trade.payment.LocalSimulationGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.user.service.AccountTradeGate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实HTTP/Session/MySQL事务；仅支付渠道适配器为可控模拟，不代表支付宝沙箱验证。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.member.enabled=true", "shop.member.reward-start-at=2026-11-01T00:00:00",
        "shop.member.scan-delay-ms=86400000", "spring.datasource.hikari.maximum-pool-size=12",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Import(MemberRewardHttpTest.TimeConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MemberRewardHttpTest {
    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        TestClock memberTestClock() {
            return new TestClock();
        }
    }

    static class TestClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-11-04T07:00:00Z"));

        void at(LocalDateTime value) {
            time.set(value.atZone(getZone()).toInstant());
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Shanghai");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return time.get();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestClock clock;
    @MockitoSpyBean
    MemberRewardService rewards;
    @Autowired
    MemberMaintenanceService maintenance;
    @Autowired
    MemberAccountService memberAccounts;
    @Autowired
    MemberTaskService tasks;
    @Autowired
    OrderRewardService reconciliation;
    @Autowired
    OrderMapper orders;
    @Autowired
    OrderItemMapper items;
    @MockitoSpyBean
    ShopService shops;
    @MockitoSpyBean
    AccountTradeGate tradeGate;
    @Autowired
    PlatformTransactionManager transactionManager;
    @MockitoBean(name = "localSimulationGateway")
    LocalSimulationGateway gateway;
    CommerceFixture fixture;
    final ObjectMapper json = new ObjectMapper();
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 15, 0);

    @BeforeEach
    void prepare() throws Exception {
        clock.at(NOW);
        try (var connection = jdbc.getDataSource().getConnection()) {
            String url = connection.getMetaData().getURL();
            assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_member_"), url);
        }
        PaymentHttpFixture.configure(gateway);
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        jdbc.update("UPDATE users SET nickname='林晓' WHERE id=?", fixture.buyer.id());
    }

    @AfterEach
    void closeSessions() {
        // 本轮独立schema保留账务和失败现场，仅关闭本测试会话连接。
        if (fixture != null) {
            fixture.client.close();
            fixture.merchantClient.close();
        }
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    long balance(String column) {
        return count("SELECT " + column + " FROM member_accounts WHERE user_id=?", fixture.buyer.id());
    }

    void growth(long order, LocalDateTime receipt, boolean unresolved) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var locked = orders.selectForUpdate(order);
            rewards.growthDue(locked.getUserId(), order, receipt, unresolved, items.rewardLines(order));
        });
    }

    @Test
    void mem20PaymentCreditsImmediatelyAndDuplicateNoticeDoesNotExtendExpiry() throws Exception {
        long order = fixture.order("payment-points");
        assertEquals(0, balance("available_point_units"));
        var start = fixture.client.post("/orders/" + order + "/payment", Map.of());
        assertEquals(200, start.statusCode(), start.body());
        var invalid = PaymentHttpFixture.notice(jdbc, order);
        invalid.put("sign", "forged");
        assertNotEquals("success", fixture.client.form("/payments/alipay/notify", invalid).body());
        assertEquals(0, balance("available_point_units"));
        var notice = PaymentHttpFixture.notice(jdbc, order);
        assertEquals("success", fixture.client.form("/payments/alipay/notify", notice).body());
        assertEquals(1990, balance("available_point_units"));
        assertEquals(0, balance("growth_amount"));
        var expiry = jdbc.queryForObject("SELECT expires_at FROM member_point_lots WHERE user_id=?",
                LocalDateTime.class, fixture.buyer.id());
        clock.at(NOW.plusDays(1));
        assertEquals("success", fixture.client.form("/payments/alipay/notify", notice).body());
        assertEquals(expiry,
                jdbc.queryForObject("SELECT expires_at FROM member_point_lots WHERE user_id=?", LocalDateTime.class,
                        fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=? AND event_kind='EARN'",
                fixture.buyer.id()));
        var overview = fixture.client.get("/members/me");
        assertEquals(200, overview.statusCode(), overview.body());
        assertEquals(1990, json.readTree(overview.body()).path("data").path("availablePointUnits").asLong());
    }

    @Test
    void mem03ReceiptSevenDayBoundaryIsIndependentOfPoints() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusDays(7).minusNanos(1_000_000));
        growth(order, NOW, false);
        assertEquals(0, balance("growth_amount"));
        clock.at(NOW.plusDays(7));
        growth(order, NOW, false);
        growth(order, NOW, false);
        assertEquals(new BigDecimal("19.90"),
                jdbc.queryForObject("SELECT growth_amount FROM member_accounts WHERE user_id=?", BigDecimal.class,
                        fixture.buyer.id()));
        assertEquals(1990, balance("available_point_units"));
        assertEquals(2, count("SELECT COUNT(*) FROM member_reward_tasks WHERE user_id=? AND status='APPLIED'",
                fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void mem04UnresolvedAfterSaleBlocksGrowthButDoesNotRemovePaymentPoints() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusDays(7));
        growth(order, NOW, true);
        assertEquals(0, balance("growth_amount"));
        assertEquals(1990, balance("available_point_units"));
        assertEquals(1, count("SELECT COUNT(*) FROM member_reward_tasks WHERE user_id=? AND status='WAIT_AFTERSALE'",
                fixture.buyer.id()));
        growth(order, NOW, false);
        assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void mem17BuyerBecomingOwnerDoesNotChangeExistingOrderQualification() throws Exception {
        long order = fixture.order("before-transfer");
        shops.changeOwner(fixture.catalog.shop, fixture.buyer.id());
        assertEquals(fixture.catalog.merchant.id(),
                count("SELECT shop_owner_user_id_at_order FROM orders WHERE id=?", order));
        assertEquals(200, fixture.client.post("/orders/" + order + "/payment", Map.of()).statusCode());
        assertEquals("success",
                fixture.client.form("/payments/alipay/notify", PaymentHttpFixture.notice(jdbc, order)).body());
        assertEquals(1990, balance("available_point_units"));
        long own = fixture.order("after-transfer");
        assertEquals(2,
                count("SELECT COUNT(*) FROM member_reward_tasks t JOIN order_items i ON i.id=t.order_item_id WHERE i.order_id=? AND exclusion_reason='SELF_SHOP_OWNER'",
                        own));
    }

    @Test
    void mem18OwnerSellingShopDoesNotRestoreExcludedOrderQualification() throws Exception {
        shops.changeOwner(fixture.catalog.shop, fixture.buyer.id());
        long order = fixture.order("self-order");
        shops.changeOwner(fixture.catalog.shop, fixture.catalog.merchant.id());
        assertEquals(200, fixture.client.post("/orders/" + order + "/payment", Map.of()).statusCode());
        assertEquals("success",
                fixture.client.form("/payments/alipay/notify", PaymentHttpFixture.notice(jdbc, order)).body());
        assertEquals(0, balance("available_point_units"));
        clock.at(NOW.plusDays(7));
        growth(order, NOW, false);
        assertEquals(0, balance("growth_amount"));
    }

    @Test
    void mem05TwentyConcurrentPaymentReplaysCreditOneSource() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        try (var pool = Executors.newFixedThreadPool(20)) {
            CyclicBarrier barrier = new CyclicBarrier(20);
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++)
                jobs.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        var locked = orders.selectForUpdate(order);
                        rewards.paymentConfirmed(locked.getUserId(), order, locked.getPaidAt(),
                                items.rewardLines(order));
                    });
                    return null;
                }));
            for (var job : jobs)
                job.get(30, TimeUnit.SECONDS);
        }
        assertEquals(1990, balance("available_point_units"));
        assertEquals(1, count("SELECT COUNT(*) FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void mem06ConfirmedRefundReversesPointsAndGrowthInTheRefundTransaction() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusDays(7));
        growth(order, NOW, false);
        refund(order);
        assertEquals(0, balance("available_point_units"));
        assertEquals(0, balance("growth_amount"));
        assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=? AND event_kind='REFUND'",
                fixture.buyer.id()));
        assertEquals(1,
                count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=? AND event_kind='REFUND_REVERSE'",
                        fixture.buyer.id()));
    }

    @Test
    void threePartialRefundsConserveTheOriginalPointAndGrowthAwards() throws Exception {
        var checkout = fixture.client.post("/checkouts/direct", fixture.direct("three-refunds", 3, "59.70"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = json.readTree(checkout.body()).path("data").path("orders").get(0).path("id").asLong();
        long item = count("SELECT id FROM order_items WHERE order_id=?", order);
        assertEquals(200, fixture.client.post("/orders/" + order + "/payment", Map.of()).statusCode());
        var notice = PaymentHttpFixture.notice(jdbc, order);
        notice.put("total_amount", "59.70");
        assertEquals("success", fixture.client.form("/payments/alipay/notify", notice).body());
        clock.at(NOW.plusDays(7));
        growth(order, NOW, false);
        assertEquals(5970, balance("available_point_units"));
        for (int step = 1; step <= 3; step++) {
            var request = Map.of("type", "REFUND_ONLY", "reason", "逐件退款", "items",
                    List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", request, true,
                    Map.of("Idempotency-Key", "member-partial-" + step));
            assertEquals(200, created.statusCode(), created.body());
            long sale = json.readTree(created.body()).path("data").path("id").asLong();
            assertEquals(new BigDecimal("19.90"), jdbc.queryForObject(
                    "SELECT refund_amount FROM after_sale_cases WHERE id=?", BigDecimal.class, sale));
            String path = "/merchant/shops/" + fixture.catalog.shop + "/after-sales/" + sale;
            assertEquals(200, fixture.merchantClient.post(path + "/review", Map.of("approved", true)).statusCode());
            when(gateway.refund(anyString(), anyString(), anyString(), any())).thenAnswer(call ->
                    new RefundGateway.Result(true, call.getArgument(0), call.getArgument(1), call.getArgument(2),
                            new BigDecimal("19.90")));
            assertEquals(200, fixture.merchantClient.post(path + "/refund", Map.of()).statusCode());
            assertEquals(200, fixture.merchantClient.post(path + "/refund", Map.of()).statusCode());
            assertEquals((3 - step) * 1990L, balance("available_point_units"));
            assertEquals(new BigDecimal("19.90").multiply(BigDecimal.valueOf(3 - step)),
                    jdbc.queryForObject("SELECT growth_amount FROM member_accounts WHERE user_id=?",
                            BigDecimal.class, fixture.buyer.id()));
        }
        assertEquals(3, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=? AND event_kind='REFUND_REVERSE'",
                fixture.buyer.id()));
        assertEquals(3, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=? AND event_kind='REFUND'",
                fixture.buyer.id()));
    }

    void refund(long order) throws Exception {
        long item = count("SELECT id FROM order_items WHERE order_id=?", order);
        var body = Map.of("type", "REFUND_ONLY", "reason", "规格不合适", "items",
                List.of(Map.of("orderItemId", item, "quantity", 1)));
        var apply = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                Map.of("Idempotency-Key", "member-refund"));
        assertEquals(200, apply.statusCode(), apply.body());
        long sale = json.readTree(apply.body()).path("data").path("id").asLong();
        String path = "/merchant/shops/" + fixture.catalog.shop + "/after-sales/" + sale;
        assertEquals(200, fixture.merchantClient.post(path + "/review", Map.of("approved", true)).statusCode());
        when(gateway.refund(anyString(), anyString(), anyString(), any())).thenAnswer(call ->
                new RefundGateway.Result(true, call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        new BigDecimal("19.90")));
        for (int i = 0; i < 2; i++) {
            var refund = fixture.merchantClient.post(path + "/refund", Map.of());
            assertEquals(200, refund.statusCode(), refund.body());
        }
    }

    @Test
    void mem12OverviewExcludesExpiredPointsWithoutWritingAndMaintenanceSettlesOnce() throws Exception {
        PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusMonths(12).minusNanos(1_000_000));
        var before = fixture.client.get("/members/me");
        assertEquals(200, before.statusCode(), before.body());
        assertEquals(1990, json.readTree(before.body()).path("data").path("availablePointUnits").asLong());
        clock.at(NOW.plusMonths(12));
        long version = balance("version");
        for (int i = 0; i < 2; i++) {
            var expired = fixture.client.get("/members/me");
            assertEquals(200, expired.statusCode(), expired.body());
            assertEquals(0, json.readTree(expired.body()).path("data").path("availablePointUnits").asLong());
            assertTrue(expired.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        }
        assertEquals(1990, balance("available_point_units"));
        assertEquals(version, balance("version"));
        assertEquals(0, count("SELECT expired_units FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=? AND event_kind='EXPIRE'",
                fixture.buyer.id()));
        maintenance.expirePoints(fixture.buyer.id());
        maintenance.expirePoints(fixture.buyer.id());
        assertEquals(0, balance("available_point_units"));
        assertEquals(1990, count("SELECT expired_units FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=? AND event_kind='EXPIRE'",
                fixture.buyer.id()));
    }

    @Test
    void missingAccountReturnsUnavailableWithoutInitializingOnGet() throws Exception {
        var response = fixture.client.get("/members/me");
        assertEquals(503, response.statusCode(), response.body());
        assertEquals("DEPENDENCY_UNAVAILABLE", json.readTree(response.body()).path("data").path("reason").asString());
        assertEquals(0, count("SELECT COUNT(*) FROM member_accounts WHERE user_id=?", fixture.buyer.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM member_level_changes WHERE user_id=?", fixture.buyer.id()));
        maintenance.initialize(fixture.buyer.id());
        assertEquals(200, fixture.client.get("/members/me").statusCode());
    }

    @Test
    void publicLevelRulesMatchTheFourConfiguredTiers() throws Exception {
        var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port + "/members/levels")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var levels = json.readTree(response.body()).path("data");
        assertEquals(4, levels.size());
        assertEquals("ORDINARY", levels.get(0).path("code").asString());
        assertEquals("PLATINUM", levels.get(3).path("code").asString());
        assertEquals(15000, levels.get(3).path("minGrowthAmount").asInt());
        assertEquals(2, levels.get(3).path("monthlyCouponCount").asInt());
        assertEquals(20, levels.get(3).path("monthlyCouponFaceAmount").asInt());
        assertEquals(499, levels.get(3).path("monthlyCouponThresholdAmount").asInt());
        assertEquals(java.util.Set.of("code", "name", "minGrowthAmount", "monthlyCouponCount",
                "monthlyCouponFaceAmount", "monthlyCouponThresholdAmount"), levels.get(0).propertyNames());
    }

    @Test
    void rewardTaskPagesFilterOwnerOrderAndKindWithoutLeakingLeaseFields() throws Exception {
        long order = fixture.order("query-tasks");
        String path = "/members/me/reward-tasks?size=1&orderId=" + order;
        var response = fixture.client.get(path);
        assertEquals(200, response.statusCode(), response.body());
        var first = json.readTree(response.body()).path("data");
        assertTrue(first.path("hasMore").asBoolean());
        long cursor = first.path("nextCursor").asLong();
        assertEquals(cursor, first.path("items").get(0).path("id").asLong());
        var second = json.readTree(fixture.client.get(path + "&afterId=" + cursor).body()).path("data");
        assertFalse(second.path("hasMore").asBoolean());
        assertTrue(second.path("nextCursor").isNull());
        assertTrue(second.path("items").get(0).path("id").asLong() < cursor);
        var task = first.path("items").get(0);
        assertEquals(java.util.Set.of("id", "orderId", "orderItemId", "rewardKind", "status", "eligibleAt",
                "creditedGrowthAmount", "creditedPointUnits", "exclusionReason"), task.propertyNames());
        var filtered = json.readTree(
                        fixture.client.get("/members/me/reward-tasks?rewardKind=POINTS&status=WAIT_PAYMENT").body())
                .path("data");
        assertEquals(1, filtered.path("items").size());
        assertEquals("POINTS", filtered.path("items").get(0).path("rewardKind").asString());
        var other = fixture.merchantClient.get(path);
        assertEquals(200, other.statusCode(), other.body());
        assertTrue(json.readTree(other.body()).path("data").path("items").isEmpty());
        assertEquals(2, count("SELECT COUNT(*) FROM member_reward_tasks WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void pointLotPagesUseEffectiveExpiryAndNeverExposeSourceKeys() throws Exception {
        PaymentHttpFixture.paidOrder(fixture, jdbc);
        long secondOrder = fixture.order("second-lot");
        assertEquals(200, fixture.client.post("/orders/" + secondOrder + "/payment", Map.of()).statusCode());
        assertEquals("success",
                fixture.client.form("/payments/alipay/notify", PaymentHttpFixture.notice(jdbc, secondOrder)).body());
        var response = fixture.client.get("/members/me/point-lots?size=1&status=ACTIVE");
        assertEquals(200, response.statusCode(), response.body());
        var first = json.readTree(response.body()).path("data");
        assertTrue(first.path("hasMore").asBoolean());
        var lot = first.path("items").get(0);
        assertEquals(java.util.Set.of("id", "issuedUnits", "availableUnits", "heldUnits", "spentUnits", "expiredUnits",
                "reversedUnits", "debtOffsetUnits", "earnedAt", "expiresAt", "status"), lot.propertyNames());
        assertEquals(ZoneOffset.ofHours(8), OffsetDateTime.parse(lot.path("expiresAt").asString()).getOffset());
        var next = json.readTree(
                        fixture.client.get("/members/me/point-lots?size=1&afterId=" + first.path("nextCursor").asLong()).body())
                .path("data");
        assertEquals(1, next.path("items").size());
        assertFalse(next.path("hasMore").asBoolean());
        assertTrue(next.path("nextCursor").isNull());
        var other = fixture.merchantClient.get("/members/me/point-lots?userId=" + fixture.buyer.id());
        assertTrue(json.readTree(other.body()).path("data").path("items").isEmpty());
        clock.at(NOW.plusMonths(12));
        var expired = json.readTree(fixture.client.get("/members/me/point-lots?status=EXPIRED").body()).path("data")
                .path("items");
        assertEquals(2, expired.size());
        for (var item : expired) {
            assertEquals(0, item.path("availableUnits").asLong());
            assertEquals(1990, item.path("expiredUnits").asLong());
            assertEquals("EXPIRED", item.path("status").asString());
        }
        assertTrue(json.readTree(fixture.client.get("/members/me/point-lots?status=ACTIVE").body()).path("data")
                .path("items").isEmpty());
        assertEquals(3980, balance("available_point_units"));
        assertEquals(0, count("SELECT SUM(expired_units) FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void buyerLedgersExposeOnlyContractFieldsWithShanghaiOffset() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusDays(7));
        growth(order, NOW, false);
        var expected = Map.of(
                "growth-entries", java.util.Set.of("id", "eventKind", "orderItemId", "afterSaleCaseId", "deltaAmount",
                        "balanceAfter", "oldLevelCode", "newLevelCode", "createdAt", "reason"),
                "point-entries", java.util.Set.of("id", "eventKind", "orderId", "afterSaleCaseId", "availableDelta",
                        "heldDelta", "debtDelta", "availableAfter", "heldAfter", "debtAfter", "createdAt", "reason"));
        for (var contract : expected.entrySet()) {
            var response = fixture.client.get("/members/me/" + contract.getKey() + "?limit=1");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            var entry = json.readTree(response.body()).path("data").get(0);
            assertEquals(contract.getValue(), entry.propertyNames());
            assertEquals(ZoneOffset.ofHours(8), OffsetDateTime.parse(entry.path("createdAt").asString()).getOffset());
            var next = fixture.client.get(
                    "/members/me/" + contract.getKey() + "?beforeId=" + entry.path("id").asLong() + "&limit=1");
            assertTrue(json.readTree(next.body()).path("data").isEmpty());
        }
    }

    @Test
    void mem08SourceAdjustmentIsAuditedIdempotentAndNotDeductedAgainByRefund() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusDays(7));
        growth(order, NOW, false);
        long source = count("SELECT id FROM member_growth_entries WHERE user_id=? AND event_kind='EARN'",
                fixture.buyer.id());
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String evidence = "证".repeat(500);
        var body = Map.of("deltaAmount", "-19.90", "sourceEntryId", source, "reason", "原单权益纠正", "evidenceRef",
                evidence);
        String path = "/admin/members/" + fixture.buyer.id() + "/growth-adjustments";
        assertEquals(403, fixture.client.request("POST", path, body, true, Map.of("Idempotency-Key", "adjust-source"))
                .statusCode());
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            var first = client.request("POST", path, body, true, Map.of("Idempotency-Key", "adjust-source"));
            assertEquals(200, first.statusCode(), first.body());
            var result = json.readTree(first.body()).path("data");
            assertEquals(java.util.Set.of("entryId", "userId", "growthAmount", "levelCode"), result.propertyNames());
            assertEquals(evidence,
                    jdbc.queryForObject("SELECT evidence_reference FROM member_growth_entries WHERE id=?", String.class,
                            result.path("entryId").asLong()));
            var audit = json.readTree(jdbc.queryForObject(
                    "SELECT summary FROM audit_events WHERE target_id=? AND action='MEMBER_GROWTH_ADJUSTED'",
                    String.class, fixture.buyer.id()));
            assertEquals(result.path("entryId").asLong(), audit.path("entryId").asLong());
            assertEquals("member_growth_entries", audit.path("ledger").asString());
            assertEquals("evidence_reference", audit.path("evidenceField").asString());
            var second = client.request("POST", path, body, true, Map.of("Idempotency-Key", "adjust-source"));
            assertEquals(json.readTree(first.body()), json.readTree(second.body()));
            var changed = Map.of("deltaAmount", "-10.00", "sourceEntryId", source, "reason", "原单权益纠正",
                    "evidenceRef", evidence);
            var conflict = client.request("POST", path, changed, true, Map.of("Idempotency-Key", "adjust-source"));
            assertEquals(409, conflict.statusCode());
            assertEquals("IDEMPOTENCY_CONFLICT", json.readTree(conflict.body()).path("data").path("reason").asString());
        }
        refund(order);
        assertEquals(0, balance("growth_amount"));
        assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=? AND event_kind='ADJUST'",
                fixture.buyer.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=? AND event_kind='REFUND'",
                fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='MEMBER_GROWTH_ADJUSTED'",
                fixture.buyer.id()));
    }

    @Test
    void mem10LevelHistoryKeepsTheEarlierMonthBoundaryAfterDowngrade() throws Exception {
        // 测试账号由实时数据库创建，先将注册时间放到可控业务时钟之前。
        jdbc.update("UPDATE users SET created_at=? WHERE id=?", NOW.minusDays(1), fixture.buyer.id());
        maintenance.initialize(fixture.buyer.id());
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String path = "/admin/members/" + fixture.buyer.id() + "/growth-adjustments";
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            var add = client.request("POST", path,
                    Map.of("deltaAmount", "5000.00", "reason", "已核验成长补录", "evidenceRef", "CASE-GROWTH-01"),
                    true, Map.of("Idempotency-Key", "history-add"));
            assertEquals(200, add.statusCode(), add.body());
            long source = json.readTree(add.body()).path("data").path("entryId").asLong();
            clock.at(LocalDateTime.of(2026, 12, 15, 0, 0));
            var subtract = client.request("POST", path,
                    Map.of("deltaAmount", "-4500.00", "sourceEntryId", source, "reason", "原补录纠正", "evidenceRef",
                            "CASE-GROWTH-02"),
                    true, Map.of("Idempotency-Key", "history-subtract"));
            assertEquals(200, subtract.statusCode(), subtract.body());
        }
        assertEquals("GOLD", memberAccounts.levelAt(fixture.buyer.id(), LocalDateTime.of(2026, 12, 1, 0, 0)));
        assertEquals("ORDINARY", memberAccounts.overview(fixture.buyer.id()).levelCode());
    }

    @Test
    void mem14ExpiredSourceIsNotConvertedIntoRefundDebt() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusMonths(12));
        maintenance.expirePoints(fixture.buyer.id());
        // 未发货订单仍可退款；已失效积分不再追回。
        refund(order);
        assertEquals(0, balance("available_point_units"));
        assertEquals(0, balance("point_debt_units"));
        assertEquals(1990, count("SELECT expired_units FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void mem07RepeatedInitializationPreservesZeroBalanceAndActivationBoundary() {
        maintenance.initialize(fixture.buyer.id());
        maintenance.initialize(fixture.buyer.id());
        assertEquals(1, count("SELECT COUNT(*) FROM member_accounts WHERE user_id=?", fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM member_level_changes WHERE user_id=?", fixture.buyer.id()));
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0),
                jdbc.queryForObject("SELECT reward_start_at FROM member_accounts WHERE user_id=?", LocalDateTime.class,
                        fixture.buyer.id()));
        assertEquals(0, balance("available_point_units"));
        assertEquals(0, balance("growth_amount"));
    }

    @Test
    void invalidGrowthAdjustmentDoesNotWriteAndRequiresCsrf() throws Exception {
        maintenance.initialize(fixture.buyer.id());
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String path = "/admin/members/" + fixture.buyer.id() + "/growth-adjustments";
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            var valid = Map.of("deltaAmount", "1.00", "reason", "更正", "evidenceRef", "CASE-01");
            assertEquals(403,
                    client.request("POST", path, valid, false, Map.of("Idempotency-Key", "csrf")).statusCode());
            assertEquals(400, client.request("POST", path, valid, true, Map.of()).statusCode());
            var invalid = List.of(
                    Map.of("deltaAmount", "0", "reason", "更正", "evidenceRef", "CASE-01"),
                    Map.of("deltaAmount", "-1", "reason", "更正", "evidenceRef", "CASE-01"),
                    Map.of("deltaAmount", "1.001", "reason", "更正", "evidenceRef", "CASE-01"),
                    Map.of("deltaAmount", "1", "reason", "更正", "evidenceRef", "证".repeat(501)));
            for (var request : invalid) {
                var response = client.request("POST", path, request, true, Map.of("Idempotency-Key", "invalid"));
                assertEquals(400, response.statusCode(), response.body());
            }
        }
        assertEquals(0, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='MEMBER_GROWTH_ADJUSTED'",
                fixture.buyer.id()));
        assertEquals(0, balance("growth_amount"));
    }

    @Test
    void pointAdjustmentsConsumeEarliestExpiryAndReplayWithoutNewEntries() throws Exception {
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String path = "/admin/members/" + fixture.buyer.id() + "/point-adjustments";
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            var later = Map.of("deltaPointUnits", 300, "reason", "积分补录", "evidenceRef", "CASE-LATER", "expiresAt",
                    NOW.plusMonths(2).atOffset(ZoneOffset.ofHours(8)).toString());
            var earlier = Map.of("deltaPointUnits", 700, "reason", "积分补录", "evidenceRef", "证".repeat(500),
                    "expiresAt", NOW.plusMonths(1).atOffset(ZoneOffset.ofHours(8)).toString());
            assertEquals(200,
                    client.request("POST", path, later, true, Map.of("Idempotency-Key", "later")).statusCode());
            var added = client.request("POST", path, earlier, true, Map.of("Idempotency-Key", "earlier"));
            assertEquals(200, added.statusCode(), added.body());
            var subtract = Map.of("deltaPointUnits", -800, "reason", "权益纠正", "evidenceRef", "CASE-SUBTRACT");
            var first = client.request("POST", path, subtract, true, Map.of("Idempotency-Key", "subtract"));
            assertEquals(200, first.statusCode(), first.body());
            assertEquals(java.util.Set.of("entryId", "userId", "availablePointUnits", "pointDebtUnits"),
                    json.readTree(first.body()).path("data").propertyNames());
            assertEquals(json.readTree(first.body()), json.readTree(
                    client.request("POST", path, subtract, true, Map.of("Idempotency-Key", "subtract")).body()));
            assertEquals(List.of(0L, 200L), jdbc.queryForList(
                    "SELECT available_units FROM member_point_lots WHERE user_id=? ORDER BY expires_at", Long.class,
                    fixture.buyer.id()));
            assertEquals(List.of(700L, 100L), jdbc.queryForList(
                    "SELECT reversed_units FROM member_point_lots WHERE user_id=? ORDER BY expires_at", Long.class,
                    fixture.buyer.id()));
            var conflict = client.request("POST", path,
                    Map.of("deltaPointUnits", -100, "reason", "权益纠正", "evidenceRef", "CASE-SUBTRACT"), true,
                    Map.of("Idempotency-Key", "subtract"));
            assertEquals(409, conflict.statusCode(), conflict.body());
            assertEquals("IDEMPOTENCY_CONFLICT", json.readTree(conflict.body()).path("data").path("reason").asString());
            clock.at(NOW.plusMonths(3));
            // 过期后原键重放仍核对原记录，不重新发放或刷新期限。
            assertEquals(200,
                    client.request("POST", path, earlier, true, Map.of("Idempotency-Key", "earlier")).statusCode());
        }
        assertEquals(3, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=?", fixture.buyer.id()));
        assertEquals(3, count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='MEMBER_POINTS_ADJUSTED'",
                fixture.buyer.id()));
        assertEquals(200, balance("available_point_units"));
        assertEquals(0, balance("point_debt_units"));
    }

    @Test
    void refundedPointsAlreadyRemovedByAdjustmentAreNotRecoveredTwice() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            var response = client.request("POST", "/admin/members/" + fixture.buyer.id() + "/point-adjustments",
                    Map.of("deltaPointUnits", -1000, "reason", "原权益纠正", "evidenceRef", "CASE-POINTS"), true,
                    Map.of("Idempotency-Key", "correct"));
            assertEquals(200, response.statusCode(), response.body());
        }
        refund(order);
        assertEquals(0, balance("available_point_units"));
        assertEquals(0, balance("point_debt_units"));
        assertEquals(1990, count("SELECT reversed_units FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
        assertEquals(-990,
                count("SELECT available_delta FROM member_point_entries WHERE user_id=? AND event_kind='REFUND_REVERSE'",
                        fixture.buyer.id()));
    }

    @Test
    void pointAdjustmentRejectsInvalidCombinationExpiredOrInsufficientBalance() throws Exception {
        maintenance.initialize(fixture.buyer.id());
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String path = "/admin/members/" + fixture.buyer.id() + "/point-adjustments";
        var positive = Map.of("deltaPointUnits", 100, "reason", "补录", "evidenceRef", "CASE-1", "expiresAt",
                NOW.plusDays(1).atOffset(ZoneOffset.ofHours(8)).toString());
        assertEquals(403,
                fixture.client.request("POST", path, positive, true, Map.of("Idempotency-Key", "unauthorized"))
                        .statusCode());
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            assertEquals(403,
                    client.request("POST", path, positive, false, Map.of("Idempotency-Key", "csrf")).statusCode());
            var invalid = List.of(
                    Map.of("deltaPointUnits", 0, "reason", "补录", "evidenceRef", "CASE-1"),
                    Map.of("deltaPointUnits", 100, "reason", "补录", "evidenceRef", "CASE-1"),
                    Map.of("deltaPointUnits", 1.5, "reason", "补录", "evidenceRef", "CASE-1", "expiresAt",
                            NOW.plusDays(1).atOffset(ZoneOffset.ofHours(8)).toString()),
                    Map.of("deltaPointUnits", -100, "reason", "补录", "evidenceRef", "CASE-1", "expiresAt",
                            NOW.plusDays(1).atOffset(ZoneOffset.ofHours(8)).toString()),
                    Map.of("deltaPointUnits", 100, "reason", "补录", "evidenceRef", "CASE-1", "expiresAt",
                            NOW.atOffset(ZoneOffset.ofHours(8)).toString()));
            for (var body : invalid) {
                var response = client.request("POST", path, body, true, Map.of("Idempotency-Key", "invalid"));
                assertEquals(400, response.statusCode(), response.body());
            }
            for (long delta : List.of(-1L, Long.MIN_VALUE)) {
                var response = client.request("POST", path,
                        Map.of("deltaPointUnits", delta, "reason", "补录", "evidenceRef", "CASE-1"), true,
                        Map.of("Idempotency-Key", "insufficient"));
                assertEquals(409, response.statusCode(), response.body());
                assertEquals("POINTS_NOT_ENOUGH",
                        json.readTree(response.body()).path("data").path("reason").asString());
            }
        }
        assertEquals(0, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=?", fixture.buyer.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
        assertEquals(0, balance("available_point_units"));
    }

    @Test
    void earnedOrAdjustedPointsRepayRefundDebtBeforeBecomingAvailable() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        // MK-06兑换尚未交付：这里只构造带SPEND账本及分配的已花来源，退款仍调用真实HTTP。
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            orders.selectForUpdate(order);
            jdbc.queryForObject("SELECT user_id FROM member_accounts WHERE user_id=? FOR UPDATE", Long.class,
                    fixture.buyer.id());
            long lot = count("SELECT id FROM member_point_lots WHERE user_id=?", fixture.buyer.id());
            jdbc.update("UPDATE member_point_lots SET available_units=0,spent_units=1990,status='EXHAUSTED' WHERE id=?",
                    lot);
            jdbc.update("UPDATE member_accounts SET available_point_units=0,version=version+1 WHERE user_id=?",
                    fixture.buyer.id());
            String key = "TEST_SPEND:" + lot;
            jdbc.update(
                    "INSERT INTO member_point_entries(user_id,event_kind,operation_key,order_id,available_delta,held_delta,debt_delta,available_after,held_after,debt_after) VALUES(?,'SPEND',?,?,-1990,0,0,0,0,0)",
                    fixture.buyer.id(), key, order);
            long entry = count("SELECT id FROM member_point_entries WHERE operation_key=?", key);
            jdbc.update(
                    "INSERT INTO member_point_allocations(entry_id,lot_id,allocation_kind,units) VALUES(?,?,'SPEND',1990)",
                    entry, lot);
        });
        refund(order);
        assertEquals(1990, balance("point_debt_units"));
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            var response = client.request("POST", "/admin/members/" + fixture.buyer.id() + "/point-adjustments",
                    Map.of("deltaPointUnits", 1000, "reason", "人工补录", "evidenceRef", "CASE-DEBT", "expiresAt",
                            NOW.plusMonths(1).atOffset(ZoneOffset.ofHours(8)).toString()),
                    true, Map.of("Idempotency-Key", "debt"));
            assertEquals(200, response.statusCode(), response.body());
        }
        assertEquals(0, balance("available_point_units"));
        assertEquals(990, balance("point_debt_units"));
        long next = fixture.order("after-debt");
        assertEquals(200, fixture.client.post("/orders/" + next + "/payment", Map.of()).statusCode());
        assertEquals("success",
                fixture.client.form("/payments/alipay/notify", PaymentHttpFixture.notice(jdbc, next)).body());
        assertEquals(1000, balance("available_point_units"));
        assertEquals(0, balance("point_debt_units"));
        assertEquals(1990,
                count("SELECT SUM(debt_offset_units) FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void actualReceiptUsesStoredDeadlineAndExpiredLeaseCanBeTakenOver() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusNanos(500_000_000));
        var shipped = fixture.merchantClient.post(
                "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment",
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF202611040001"));
        assertEquals(200, shipped.statusCode(), shipped.body());
        var confirmed = fixture.client.post("/orders/" + order + "/shipment/confirm", Map.of());
        assertEquals(200, confirmed.statusCode(), confirmed.body());
        LocalDateTime receipt = jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                LocalDateTime.class, order);
        long task = count("SELECT id FROM member_reward_tasks WHERE user_id=? AND reward_kind='GROWTH'",
                fixture.buyer.id());
        assertEquals(receipt.plusDays(7),
                jdbc.queryForObject("SELECT eligible_at FROM member_reward_tasks WHERE id=?", LocalDateTime.class,
                        task));
        clock.at(receipt.plusDays(7).minusNanos(1_000_000));
        assertFalse(tasks.claim(task, "before-deadline"));
        clock.at(receipt.plusDays(7));
        assertTrue(tasks.claim(task, "old-worker"));
        assertFalse(tasks.claim(task, "other-worker"));
        clock.at(receipt.plusDays(7).plusSeconds(30));
        assertTrue(tasks.claim(task, "new-worker"));
        reconciliation.reconcile(order, task, "old-worker");
        assertEquals(0, balance("growth_amount"));
        reconciliation.reconcile(order, task, "new-worker");
        reconciliation.reconcile(order, task, "new-worker");
        assertEquals(new BigDecimal("19.90"),
                jdbc.queryForObject("SELECT growth_amount FROM member_accounts WHERE user_id=?", BigDecimal.class,
                        fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
        assertEquals("APPLIED",
                jdbc.queryForObject("SELECT status FROM member_reward_tasks WHERE id=?", String.class, task));
    }

    @Test
    void failedGrowthTransactionRollsBackAccountLedgerHistoryAndTaskTogether() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        clock.at(NOW.plusDays(7));
        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    var locked = orders.selectForUpdate(order);
                    rewards.growthDue(locked.getUserId(), order, NOW, false, items.rewardLines(order));
                    throw new IllegalStateException("controlled transaction failure");
                }));
        assertEquals(0, balance("growth_amount"));
        assertEquals(0, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM member_level_changes WHERE user_id=?", fixture.buyer.id()));
        assertEquals("WAIT_RECEIPT",
                jdbc.queryForObject("SELECT status FROM member_reward_tasks WHERE user_id=? AND reward_kind='GROWTH'",
                        String.class, fixture.buyer.id()));
        growth(order, NOW, false);
        assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void refundBeforeMissingPointsCompensationLeavesNoSpendableBalance() throws Exception {
        var suppress = new java.util.concurrent.atomic.AtomicBoolean(true);
        MemberRewardService rewardTarget = AopTestUtils.getUltimateTargetObject(rewards);
        // 只注入一次权益漏记，支付/售后仍走真实事务；模拟历史补偿输入，不声称原事务可部分提交。
        doAnswer(call -> suppress.get() ? null : call.callRealMethod()).when(rewardTarget)
                .paymentConfirmed(anyLong(), anyLong(), any(), anyList());
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        suppress.set(false);
        assertEquals(0, count("SELECT COUNT(*) FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
        refund(order);
        long task = count("SELECT id FROM member_reward_tasks WHERE user_id=? AND reward_kind='POINTS'",
                fixture.buyer.id());
        assertTrue(tasks.claim(task, "missing-points"));
        reconciliation.reconcile(order, task, "missing-points");
        assertEquals(0, balance("available_point_units"));
        assertEquals(0, balance("point_debt_units"));
        assertEquals(1990, count("SELECT reversed_units FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=? AND event_kind='EARN'",
                fixture.buyer.id()));
        assertEquals(1,
                count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=? AND event_kind='REFUND_REVERSE'",
                        fixture.buyer.id()));
        assertEquals("APPLIED",
                jdbc.queryForObject("SELECT status FROM member_reward_tasks WHERE id=?", String.class, task));
    }

    @Test
    void checkoutWaitingForOwnerTransferFreezesTheCommittedNewOwner() throws Exception {
        var transferLocked = new CountDownLatch(1);
        var checkoutReachedGate = new CountDownLatch(1);
        var allowTransferCommit = new CountDownLatch(1);
        AccountTradeGate gateTarget = AopTestUtils.getUltimateTargetObject(tradeGate);
        doAnswer(call -> {
            checkoutReachedGate.countDown();
            return call.callRealMethod();
        }).when(gateTarget).requireCanStartTrade(fixture.buyer.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var transfer = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                // 真实归属写入在外层事务内保持未提交，买家下单必经同一店铺行锁。
                shops.changeOwner(fixture.catalog.shop, fixture.buyer.id());
                transferLocked.countDown();
                try {
                    assertTrue(allowTransferCommit.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertTrue(transferLocked.await(10, TimeUnit.SECONDS));
            var checkout = pool.submit(() -> fixture.order("transfer-before-checkout"));
            try {
                assertTrue(checkoutReachedGate.await(10, TimeUnit.SECONDS));
                assertFalse(checkout.isDone());
            } finally {
                allowTransferCommit.countDown();
            }
            transfer.get(15, TimeUnit.SECONDS);
            long order = checkout.get(15, TimeUnit.SECONDS);
            assertEquals(fixture.buyer.id(), count("SELECT shop_owner_user_id_at_order FROM orders WHERE id=?", order));
            assertEquals(2,
                    count("SELECT COUNT(*) FROM member_reward_tasks t JOIN order_items i ON i.id=t.order_item_id WHERE i.order_id=? AND exclusion_reason='SELF_SHOP_OWNER'",
                            order));
            assertEquals(order, fixture.order("transfer-before-checkout"));
        }
    }

    @Test
    void transferWaitingForCheckoutDoesNotRewriteTheOldOwnerSnapshot() throws Exception {
        var checkoutLocked = new CountDownLatch(1);
        var allowCheckoutCommit = new CountDownLatch(1);
        var transferStarted = new CountDownLatch(1);
        ShopService shopTarget = AopTestUtils.getUltimateTargetObject(shops);
        doAnswer(call -> {
            var shop = call.callRealMethod();
            checkoutLocked.countDown();
            assertTrue(allowCheckoutCommit.await(10, TimeUnit.SECONDS));
            return shop;
        }).when(shopTarget).lockById(fixture.catalog.shop);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var checkout = pool.submit(() -> fixture.order("checkout-before-transfer"));
            assertTrue(checkoutLocked.await(10, TimeUnit.SECONDS));
            var transfer = pool.submit(() -> {
                transferStarted.countDown();
                shops.changeOwner(fixture.catalog.shop, fixture.buyer.id());
            });
            try {
                assertTrue(transferStarted.await(10, TimeUnit.SECONDS));
                assertFalse(transfer.isDone());
            } finally {
                allowCheckoutCommit.countDown();
            }
            long order = checkout.get(15, TimeUnit.SECONDS);
            transfer.get(15, TimeUnit.SECONDS);
            assertEquals(fixture.catalog.merchant.id(),
                    count("SELECT shop_owner_user_id_at_order FROM orders WHERE id=?", order));
            assertEquals(0,
                    count("SELECT COUNT(*) FROM member_reward_tasks t JOIN order_items i ON i.id=t.order_item_id WHERE i.order_id=? AND status='EXCLUDED'",
                            order));
            assertEquals(order, fixture.order("checkout-before-transfer"));
            assertEquals(fixture.catalog.merchant.id(),
                    count("SELECT shop_owner_user_id_at_order FROM orders WHERE id=?", order));
        }
    }
}

