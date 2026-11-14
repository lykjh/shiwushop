package com.shiwushop.system.marketing;

import com.shiwushop.ShiwushopApplication;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 独立应用JVM，提交后强杀及跨实例重放；不声称杀在事务提交窗口。 */
@EnabledIfSystemProperty(named = "marketing.system.enabled", matches = "true")
class MarketingBudgetSystemTest {
    static final String URL = System.getProperty("spring.datasource.url", "");
    static final String USERNAME = System.getProperty("spring.datasource.username", "");
    static final String PASSWORD = System.getProperty("spring.datasource.password", "");
    static final String REDIS_PORT = System.getProperty("spring.data.redis.port", "");
    static final String REDIS_DATABASE = System.getProperty("spring.data.redis.database", "");
    static final Path MATERIALS = Path.of(
            "D:/desktop/IdeaProjects/docs/文档/shiwushop/协作材料/开发/全量剩余事项/开发三/20261105-02");
    static final Path TEST_MATERIALS = Path.of(
            "D:/desktop/IdeaProjects/docs/文档/shiwushop/协作材料/测试");
    final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, USERNAME, PASSWORD));
    final ObjectMapper json = new ObjectMapper();

    @Test
    void committedAdjustmentSurvivesKillAndTwoInstancesCannotShrinkBelowCommitments() throws Exception {
        boolean development = URL.startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_dev3_20261105b?");
        boolean independent = URL.startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_test3_20261105b?");
        assertTrue(development || independent);
        assertEquals("28379", REDIS_PORT);
        assertEquals(development ? "3" : "11", REDIS_DATABASE);
        assertFalse(USERNAME.isBlank());
        Path evidence = Path.of(System.getProperty("marketing.system.evidence")).toAbsolutePath().normalize();
        assertTrue(evidence.startsWith(development ? MATERIALS : TEST_MATERIALS));
        Files.createDirectories(evidence);
        TestAccounts.Account admin;
        long budget, entry;
        var request = Map.of("deltaAmount", 25, "reason", "系统额度增加", "evidenceRef", "SYSTEM-BUDGET-1");
        try (var initial = new Instance(evidence, "initial")) {
            var fixture = new CommerceFixture(jdbc, initial.base);
            try {
                admin = new TestAccounts(jdbc).create("ADMIN", "Shop123!");
                String key = UUID.randomUUID().toString();
                long activity = fixture.catalog.insert("""
                        INSERT INTO marketing_activities(activity_code,version_no,name,activity_type,status,start_at,end_at,
                          eligible_level_codes,creator_user_id,rules_text,request_scope,request_key,request_hash)
                        VALUES(?,1,'冬日预算验证','FULL_REDUCTION','DRAFT','2026-11-01','2026-12-01',JSON_ARRAY('ORDINARY'),?,
                          '额度调整','CREATE_PLATFORM',?,UNHEX(SHA2(?,256)))
                        """, key, admin.id(), key, key);
                budget = fixture.catalog.insert(
                        "INSERT INTO marketing_budgets(activity_id,party,budget_amount,held_amount,spent_amount) VALUES(?,'PLATFORM',100,60,20)",
                        activity);
                try (var client = CommerceFixture.login(initial.base, admin)) {
                    var response = client.request("POST", "/admin/marketing/budgets/" + budget + "/adjustments",
                            request, true, Map.of("Idempotency-Key", "original:~"));
                    assertEquals(200, response.statusCode(), response.body());
                    entry = json.readTree(response.body()).path("data").path("budgetEntryId").asLong();
                    Files.writeString(evidence.resolve("终止前响应.json"), response.body());
                }
                assertEquals(125, count("SELECT budget_amount FROM marketing_budgets WHERE id=?", budget));
            } finally {
                fixture.client.close();
                fixture.merchantClient.close();
            }
            initial.process.destroyForcibly();
            assertTrue(initial.process.waitFor(10, TimeUnit.SECONDS));
        }
        try (var first = new Instance(evidence, "recovery-a"); var second = new Instance(evidence, "recovery-b");
             var a = CommerceFixture.login(first.base, admin); var b = CommerceFixture.login(second.base, admin);
             var pool = Executors.newFixedThreadPool(2)) {
            String path = "/admin/marketing/budgets/" + budget + "/adjustments";
            var replayBarrier = new CyclicBarrier(2);
            var replays = new ArrayList<Future<String>>();
            for (var client : List.of(a, b))
                replays.add(pool.submit(() -> {
                    replayBarrier.await(10, TimeUnit.SECONDS);
                    var response = client.request("POST", path, request, true, Map.of("Idempotency-Key", "original:~"));
                    assertEquals(200, response.statusCode(), response.body());
                    assertEquals(entry, json.readTree(response.body()).path("data").path("budgetEntryId").asLong());
                    return response.body();
                }));
            var replayBodies = new ArrayList<String>();
            for (var future : replays)
                replayBodies.add(future.get(20, TimeUnit.SECONDS));
            Files.writeString(evidence.resolve("两实例原键重放.json"), json.writeValueAsString(replayBodies));
            assertEquals(1, count("SELECT COUNT(*) FROM marketing_budget_entries WHERE budget_id=?", budget));
            var shrinkBarrier = new CyclicBarrier(2);
            var shrinks = new ArrayList<Future<Integer>>();
            int index = 0;
            for (var client : List.of(a, b)) {
                String key = "shrink-" + index++;
                shrinks.add(pool.submit(() -> {
                    shrinkBarrier.await(10, TimeUnit.SECONDS);
                    var response = client.request("POST", path,
                            Map.of("deltaAmount", -30, "reason", "收缩未承诺预算", "evidenceRef", "SYSTEM-BUDGET-2"),
                            true, Map.of("Idempotency-Key", key));
                    if (response.statusCode() == 409)
                        assertEquals("BUDGET_NOT_ENOUGH",
                                json.readTree(response.body()).path("data").path("reason").asString());
                    return response.statusCode();
                }));
            }
            var statuses = new ArrayList<Integer>();
            for (var future : shrinks)
                statuses.add(future.get(20, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
            assertEquals(95, count("SELECT budget_amount FROM marketing_budgets WHERE id=?", budget));
            assertEquals(80, count("SELECT held_amount+spent_amount FROM marketing_budgets WHERE id=?", budget));
            assertEquals(2, count("SELECT COUNT(*) FROM marketing_budget_entries WHERE budget_id=?", budget));
            assertEquals(2,
                    count("SELECT COUNT(*) FROM audit_events WHERE target_id=? AND action='MARKETING_BUDGET_ADJUSTED'",
                            budget));
            Files.writeString(evidence.resolve("恢复后预算与审计.json"),
                    json.writeValueAsString(Map.of("budgetId", budget,
                            "originalEntryId", entry, "shrinkStatuses", statuses, "budget",
                            jdbc.queryForMap("SELECT * FROM marketing_budgets WHERE id=?", budget),
                            "entries", jdbc.queryForList(
                                    "SELECT id,operation_key,budget_delta_amount,budget_after FROM marketing_budget_entries WHERE budget_id=? ORDER BY id",
                                    budget),
                            "audit", jdbc.queryForList(
                                    "SELECT id,actor_user_id,action,summary FROM audit_events WHERE target_id=? AND action='MARKETING_BUDGET_ADJUSTED' ORDER BY id",
                                    budget))));
        }
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static final class Instance implements AutoCloseable {
        final Process process;
        final String base;

        Instance(Path evidence, String label) throws Exception {
            int port = "recovery-b".equals(label) ? 18126 : 18125;
            base = "http://127.0.0.1:" + port;
            var log = evidence.resolve(label + "-console.log");
            var args = List.of("-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai", "-Xmx512m", "-cp",
                    System.getProperty("surefire.test.class.path"), ShiwushopApplication.class.getName(),
                    "--server.address=127.0.0.1", "--server.port=" + port, "--spring.profiles.active=test,verifyCode",
                    "--spring.config.import=optional:file:./target/marketing-system-no-local.yml",
                    "--spring.autoconfigure.exclude=",
                    "--spring.datasource.url=" + URL, "--spring.datasource.username=" + USERNAME,
                    "--spring.datasource.password=" + PASSWORD,
                    "--spring.datasource.hikari.maximum-pool-size=4", "--spring.data.redis.port=" + REDIS_PORT,
                    "--spring.data.redis.database=" + REDIS_DATABASE,
                    "--shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    "--shop.sms.lookup-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    "--shop.member.enabled=false", "--shop.marketing.enabled=true",
                    "--shop.search.discovery-enabled=false", "--shop.search.worker-enabled=false",
                    "--shop.shipment.rocket-enabled=false", "--shop.shipment.expiry-enabled=false",
                    "--shop.payment.expiry-scan-delay-ms=86400000", "--shop.payment.refund-scan-delay-ms=86400000",
                    "--shop.aftersale.timeout-scan-delay-ms=86400000",
                    "--logging.file.name=" + evidence.resolve(label + "-app.log"));
            Path argFile = evidence.resolve(label + "-jvm.args");
            Files.write(argFile,
                    args.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                            .toList(), StandardCharsets.UTF_8);
            try {
                process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                        "@" + argFile)
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                Files.writeString(evidence.resolve(label + ".pid"), Long.toString(process.pid()));
                boolean ready = false;
                try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (process.isAlive() && System.nanoTime() < deadline) {
                        try {
                            var response = http.send(
                                    HttpRequest.newBuilder(URI.create(base + "/users/csrf")).timeout(Duration.ofSeconds(2))
                                            .GET().build(), HttpResponse.BodyHandlers.discarding());
                            if (response.statusCode() == 200) {
                                ready = true;
                                break;
                            }
                        } catch (java.io.IOException starting) { /* 有界启动探测，失败保留日志。 */ }
                        Thread.sleep(200);
                    }
                } finally {
                    if (!ready)
                        close();
                }
                assertTrue(ready, "独立应用未就绪，查看 " + log);
            } finally {
                Files.deleteIfExists(argFile);
            }
        }

        @Override
        public void close() throws InterruptedException {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS))
                    process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
