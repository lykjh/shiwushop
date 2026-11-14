package com.shiwushop.integration.member;

import com.shiwushop.member.service.MemberTaskService;
import com.shiwushop.trade.payment.LocalSimulationGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.trade.service.OrderRewardService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.shiwushop.member.service.MemberAdministrationService;
import com.shiwushop.member.dto.GrowthAdjustment;
import com.shiwushop.common.BusinessException;

import java.math.BigDecimal;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.member.enabled=true", "shop.member.reward-start-at=2026-11-01T00:00:00",
        "shop.member.scan-delay-ms=86400000", "spring.datasource.hikari.maximum-pool-size=12",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Import(MemberRewardHttpTest.TimeConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminMemberHttpTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRewardHttpTest.TestClock clock;
    @Autowired
    MemberTaskService tasks;
    @Autowired
    OrderRewardService reconciliation;
    @Autowired
    MemberAdministrationService administration;
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
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_member_"));
        }
        PaymentHttpFixture.configure(gateway);
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
    }

    @AfterEach
    void closeSessions() {
        if (fixture != null) {
            fixture.client.close();
            fixture.merchantClient.close();
        }
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @ParameterizedTest
    @ValueSource(strings = {"growth", "point"})
    void concurrentAdjustmentRetriesObserveTheCommittedOriginalEntry(String kind) throws Exception {
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port,
                admin); var pool = Executors.newFixedThreadPool(8)) {
            Map<String, Object> request = kind.equals("growth")
                    ? Map.of("deltaAmount", 1, "reason", "并发补记", "evidenceRef", "RACE-ADJUST")
                    : Map.of("deltaPointUnits", 1, "reason", "并发补记", "evidenceRef", "RACE-ADJUST",
                    "expiresAt", NOW.plusDays(1).atOffset(ZoneOffset.ofHours(8)).toString());
            var barrier = new CyclicBarrier(8);
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 8; i++)
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    var response = client.request("POST",
                            "/admin/members/" + fixture.buyer.id() + "/" + kind + "-adjustments",
                            request, true, Map.of("Idempotency-Key", "same-adjustment"));
                    assertEquals(200, response.statusCode(), response.body());
                    return json.readTree(response.body()).path("data").path("entryId").asLong();
                }));
            var entries = new HashSet<Long>();
            for (var future : futures)
                entries.add(future.get(20, TimeUnit.SECONDS));
            assertEquals(1, entries.size());
        }
        String ledger = kind.equals("growth") ? "member_growth_entries" : "member_point_entries";
        assertEquals(1, count("SELECT COUNT(*) FROM " + ledger + " WHERE user_id=?", fixture.buyer.id()));
        assertEquals(1,
                count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action IN ('MEMBER_GROWTH_ADJUSTED','MEMBER_POINTS_ADJUSTED')",
                        fixture.buyer.id()));
    }

    @Test
    void differentCommandsCannotReverseTheSameSourceTwiceEvenWithOlderSnapshots() throws Exception {
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        long source = administration.adjustGrowth(admin.id(), fixture.buyer.id(), "source",
                new GrowthAdjustment(new BigDecimal("100"), null, "补记", "E1")).entryId();
        administration.adjustGrowth(admin.id(), fixture.buyer.id(), "other-source",
                new GrowthAdjustment(new BigDecimal("100"), null, "另一来源", "E2"));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                String key = "reverse-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                            // 两个旧快照先建立，再争用账户锁；另一来源使错误重复扣减也不会触发非负CHECK。
                            count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id());
                            try {
                                barrier.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            } catch (BrokenBarrierException | TimeoutException failed) {
                                throw new IllegalStateException(failed);
                            }
                            administration.adjustGrowth(admin.id(), fixture.buyer.id(), key,
                                    new GrowthAdjustment(new BigDecimal("-100"), source, "冲回来源", "E3"));
                        });
                        return 200;
                    } catch (BusinessException failure) {
                        return failure.getCode();
                    }
                }));
            }
            var statuses = new ArrayList<Integer>();
            for (var future : futures)
                statuses.add(future.get(20, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(100, count("SELECT growth_amount FROM member_accounts WHERE user_id=?", fixture.buyer.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE source_entry_id=?", source));
    }

    @Test
    void visibleAsciiKeysReplayExactlyAndRemainCaseSensitiveInMysql() throws Exception {
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String key = "Case:[]{}()/+.,;=?!@#$%^&*<>|";
        key = key + "~".repeat(64 - key.length());
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            for (String kind : List.of("growth", "point")) {
                String path = "/admin/members/" + fixture.buyer.id() + "/" + kind + "-adjustments";
                Map<String, Object> request = kind.equals("growth")
                        ? Map.of("deltaAmount", 1, "reason", "请求键复核", "evidenceRef", "KEY-01")
                        : Map.of("deltaPointUnits", 1, "reason", "请求键复核", "evidenceRef", "KEY-01",
                        "expiresAt", NOW.plusDays(1).atOffset(ZoneOffset.ofHours(8)).toString());
                var first = client.request("POST", path, request, true, Map.of("Idempotency-Key", key));
                assertEquals(200, first.statusCode(), first.body());
                var replay = client.request("POST", path, request, true, Map.of("Idempotency-Key", key));
                assertEquals(200, replay.statusCode(), replay.body());
                assertEquals(first.body(), replay.body());
                var changed = new HashMap<>(request);
                changed.put("evidenceRef", "KEY-02");
                var conflict = client.request("POST", path, changed, true, Map.of("Idempotency-Key", key));
                assertEquals(409, conflict.statusCode(), conflict.body());
                assertEquals("IDEMPOTENCY_CONFLICT",
                        json.readTree(conflict.body()).path("data").path("reason").asString());
                var different = client.request("POST", path, request, true,
                        Map.of("Idempotency-Key", key.replace("Case", "case")));
                assertEquals(200, different.statusCode(), different.body());
                assertNotEquals(json.readTree(first.body()).path("data").path("entryId").asLong(),
                        json.readTree(different.body()).path("data").path("entryId").asLong());
            }
        }
        assertEquals(2, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
        assertEquals(2, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=?", fixture.buyer.id()));
        assertEquals(4,
                count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action IN ('MEMBER_GROWTH_ADJUSTED','MEMBER_POINTS_ADJUSTED')",
                        fixture.buyer.id()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"growth", "point"})
    void replayKeepsTheOriginalAdjustmentResultAfterLaterBalanceChanges(String kind) throws Exception {
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String path = "/admin/members/" + fixture.buyer.id() + "/" + kind + "-adjustments";
        Map<String, Object> firstRequest = kind.equals("growth")
                ? Map.of("deltaAmount", 1, "reason", "首次补记", "evidenceRef", "REPLAY-1")
                : Map.of("deltaPointUnits", 10, "reason", "首次补记", "evidenceRef", "REPLAY-1",
                        "expiresAt", NOW.plusDays(1).atOffset(ZoneOffset.ofHours(8)).toString());
        Map<String, Object> laterRequest = kind.equals("growth")
                ? Map.of("deltaAmount", 2, "reason", "后续补记", "evidenceRef", "REPLAY-2")
                : Map.of("deltaPointUnits", 20, "reason", "后续补记", "evidenceRef", "REPLAY-2",
                        "expiresAt", NOW.plusDays(2).atOffset(ZoneOffset.ofHours(8)).toString());
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            var first = client.request("POST", path, firstRequest, true, Map.of("Idempotency-Key", "replay-first"));
            var later = client.request("POST", path, laterRequest, true, Map.of("Idempotency-Key", "replay-later"));
            var replay = client.request("POST", path, firstRequest, true, Map.of("Idempotency-Key", "replay-first"));
            assertEquals(200, first.statusCode(), first.body());
            assertEquals(200, later.statusCode(), later.body());
            assertEquals(200, replay.statusCode(), replay.body());
            assertNotEquals(json.readTree(first.body()).path("data"), json.readTree(later.body()).path("data"));
            assertEquals(json.readTree(first.body()).path("data"), json.readTree(replay.body()).path("data"));
        }
    }

    @Test
    void adminReadsFlatLedgerWithEvidenceAndOwnerOrderFilters() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String path = "/admin/members/" + fixture.buyer.id();
        assertEquals(403, fixture.client.get(path).statusCode());
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            assertEquals(200, client.get(path).statusCode());
            var request = Map.of("deltaPointUnits", 10, "reason", "复核补记", "evidenceRef", "CASE-ADMIN-01",
                    "expiresAt", NOW.plusDays(1).atOffset(ZoneOffset.ofHours(8)).toString());
            var adjusted = client.request("POST", path + "/point-adjustments", request, true,
                    Map.of("Idempotency-Key", "admin-view"));
            assertEquals(200, adjusted.statusCode(), adjusted.body());
            long entryId = json.readTree(adjusted.body()).path("data").path("entryId").asLong();
            var response = client.get(path + "/point-entries?size=1");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            var page = json.readTree(response.body()).path("data");
            var entry = page.path("items").get(0);
            assertEquals(entryId, entry.path("id").asLong());
            assertEquals(admin.id(), entry.path("actorUserId").asLong());
            assertEquals("CASE-ADMIN-01", entry.path("evidenceRef").asString());
            assertFalse(entry.has("entry"));
            assertFalse(entry.has("operationKey"));
            assertTrue(page.path("hasMore").asBoolean());
            var next = json.readTree(
                            client.get(path + "/point-entries?size=1&afterId=" + page.path("nextCursor").asLong()).body())
                    .path("data");
            assertFalse(next.path("hasMore").asBoolean());
            assertTrue(next.path("nextCursor").isNull());
            var filtered = json.readTree(client.get(path + "/point-entries?orderId=" + order).body()).path("data")
                    .path("items");
            assertEquals(1, filtered.size());
            assertEquals("EARN", filtered.get(0).path("eventKind").asString());
            assertEquals(400, client.get(path + "/point-entries?size=101").statusCode());
            assertEquals(404, client.get("/admin/members/" + Long.MAX_VALUE).statusCode());
            var growth = client.request("POST", path + "/growth-adjustments",
                    Map.of("deltaAmount", 10, "reason", "成长补记", "evidenceRef", "CASE-GROWTH"), true,
                    Map.of("Idempotency-Key", "growth-view"));
            assertEquals(200, growth.statusCode(), growth.body());
            var growthEntry = json.readTree(client.get(path + "/growth-entries").body()).path("data").path("items")
                    .get(0);
            assertEquals("CASE-GROWTH", growthEntry.path("evidenceRef").asString());
            assertTrue(growthEntry.has("sourceEntryId"));
            assertFalse(growthEntry.has("entry"));
            assertTrue(json.readTree(client.get(path + "/growth-entries?orderId=" + order).body()).path("data")
                    .path("items").isEmpty());
            var audit = json.readTree(jdbc.queryForObject(
                    "SELECT summary FROM audit_events WHERE target_id=? AND action='MEMBER_POINTS_ADJUSTED'",
                    String.class, fixture.buyer.id()));
            assertEquals(2000, audit.path("availableAfter").asLong());
            assertEquals(0, audit.path("debtAfter").asLong());
        }
    }

    @Test
    void adminDoesNotInitializeMissingMemberOnRead() throws Exception {
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            for (String suffix : List.of("", "/growth-entries", "/point-entries")) {
                var response = client.get("/admin/members/" + fixture.buyer.id() + suffix);
                assertEquals(503, response.statusCode(), response.body());
            }
        }
        assertEquals(0, count("SELECT COUNT(*) FROM member_accounts WHERE user_id=?", fixture.buyer.id()));
    }

    @Test
    void concurrentRetriesQueueOnceAndOnlyReconciliationCreditsGrowth() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        var shipped = fixture.merchantClient.post(
                "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment",
                Map.of("carrierName", "顺丰", "trackingNo", "SFADMIN20261104001"));
        assertEquals(200, shipped.statusCode(), shipped.body());
        assertEquals(200, fixture.client.post("/orders/" + order + "/shipment/confirm", Map.of()).statusCode());
        long task = count("SELECT id FROM member_reward_tasks WHERE user_id=? AND reward_kind='GROWTH'",
                fixture.buyer.id());
        var eligible = jdbc.queryForObject("SELECT eligible_at FROM member_reward_tasks WHERE id=?",
                LocalDateTime.class, task);
        clock.at(eligible);
        jdbc.update(
                "UPDATE member_reward_tasks SET status='ATTENTION',next_attempt_at=?,last_error_code='TEST_DEPENDENCY' WHERE id=?",
                eligible.plusMinutes(5), task);
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        String path = "/admin/marketing/reward-tasks/" + task + "/retry";
        assertEquals(403, fixture.client.post(path, Map.of("reason", "依赖恢复")).statusCode());
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port,
                admin); var pool = Executors.newFixedThreadPool(8)) {
            assertEquals(403, client.request("POST", path, Map.of("reason", "恢复"), false, Map.of()).statusCode());
            assertEquals(400, client.post(path, Map.of("reason", " ")).statusCode());
            assertEquals(400, client.post(path, Map.of("reason", "证".repeat(501))).statusCode());
            var barrier = new CyclicBarrier(8);
            var pending = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++)
                pending.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    var response = client.post(path, Map.of("reason", "复".repeat(500)));
                    assertEquals(202, response.statusCode(), response.body());
                    assertEquals(200, json.readTree(response.body()).path("code").asInt());
                    assertEquals("READY", json.readTree(response.body()).path("data").path("state").asString());
                    return null;
                }));
            for (var request : pending)
                request.get(30, TimeUnit.SECONDS);
            assertEquals(0, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
            assertEquals(1,
                    count("SELECT COUNT(*) FROM audit_events WHERE target_type='MEMBER_REWARD_TASK' AND target_id=?",
                            task));
            assertEquals("复".repeat(500), jdbc.queryForObject(
                    "SELECT summary FROM audit_events WHERE target_type='MEMBER_REWARD_TASK' AND target_id=?",
                    String.class, task));
            var list = client.get("/admin/marketing/reward-tasks?userId=" + fixture.buyer.id() + "&orderId=" + order +
                    "&rewardKind=GROWTH&status=READY&size=1");
            assertEquals(200, list.statusCode(), list.body());
            var row = json.readTree(list.body()).path("data").path("items").get(0);
            assertEquals(task, row.path("id").asLong());
            assertFalse(row.has("claimToken"));
            assertFalse(row.has("task"));
            assertEquals("TEST_DEPENDENCY", row.path("lastErrorCode").asString());
            assertTrue(tasks.claim(task, "admin-retry-worker"));
            assertEquals(202, client.post(path, Map.of("reason", "正在处理时重试")).statusCode());
            assertEquals("admin-retry-worker",
                    jdbc.queryForObject("SELECT claim_token FROM member_reward_tasks WHERE id=?", String.class, task));
            reconciliation.reconcile(order, task, "admin-retry-worker");
            assertEquals(200, client.post(path, Map.of("reason", "已完成后重试")).statusCode());
            assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
        }
    }

    @Test
    void retryPreservesFutureEligibilityAndRejectsExcludedOrMissingTask() throws Exception {
        fixture.order("future");
        long task = count("SELECT id FROM member_reward_tasks WHERE user_id=? AND reward_kind='GROWTH'",
                fixture.buyer.id());
        jdbc.update("UPDATE member_reward_tasks SET status='ATTENTION',eligible_at=?,next_attempt_at=? WHERE id=?",
                NOW.plusDays(7), NOW.plusDays(8), task);
        var admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
            String path = "/admin/marketing/reward-tasks/" + task + "/retry";
            assertEquals(202, client.post(path, Map.of("reason", "未来任务重新排队")).statusCode());
            assertEquals(NOW.plusDays(7),
                    jdbc.queryForObject("SELECT next_attempt_at FROM member_reward_tasks WHERE id=?",
                            LocalDateTime.class, task));
            assertFalse(tasks.claim(task, "too-early"));
            jdbc.update(
                    "UPDATE member_reward_tasks SET status='EXCLUDED',exclusion_reason='SELF_SHOP_OWNER' WHERE id=?",
                    task);
            var excluded = client.post(path, Map.of("reason", "不能绕过排除"));
            assertEquals(409, excluded.statusCode());
            assertEquals("INVALID_STATE", json.readTree(excluded.body()).path("data").path("reason").asString());
            assertEquals(404, client.post("/admin/marketing/reward-tasks/" + Long.MAX_VALUE + "/retry",
                    Map.of("reason", "不存在")).statusCode());
        }
        assertEquals(0, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
    }
}
