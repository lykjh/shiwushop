package com.shiwushop.system.member;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 独立JVM与真实HTTP/DB；不启动Spring测试容器，不代表真实支付宝或真实7天观察。 */
@EnabledIfSystemProperty(named = "member.system.enabled", matches = "true")
class MemberProcessSystemTest {
    private static final String URL = System.getProperty("spring.datasource.url", "");
    private static final String REDIS_DATABASE = System.getProperty("spring.data.redis.database", "");
    private static final String REDIS_PORT = System.getProperty("spring.data.redis.port", "");
    private static final String USERNAME = System.getProperty("spring.datasource.username", "");
    private static final String PASSWORD = System.getProperty("spring.datasource.password", "");
    private static final LocalDateTime INITIAL = LocalDateTime.of(2026, 11, 4, 15, 0);
    private static final Path MATERIALS = Path.of(
            "D:/desktop/IdeaProjects/docs/文档/shiwushop/协作材料/开发/全量剩余事项/开发三/20261105-02");
    private static final Path TEST_MATERIALS = Path.of(
            "D:/desktop/IdeaProjects/docs/文档/shiwushop/协作材料/测试");
    private final JdbcTemplate jdbc = new JdbcTemplate(
            new DriverManagerDataSource(URL, USERNAME, PASSWORD));

    @Test
    void killedInstanceRestartsWithoutDoublePointsAndTwoWorkersRecoverExpiredLease() throws Exception {
        boolean development = URL.startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_member_dev3_20261105b?");
        boolean independent = URL.startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_member_test3_20261105b?");
        assertTrue(development || independent);
        assertEquals("28379", REDIS_PORT);
        assertEquals(development ? "3" : "11", REDIS_DATABASE);
        assertFalse(USERNAME.isBlank());
        Path evidence = Path.of(System.getProperty("member.system.evidence")).toAbsolutePath().normalize();
        assertTrue(evidence.startsWith(development ? MATERIALS : TEST_MATERIALS));
        Files.createDirectories(evidence);
        CommerceFixture fixture;
        long order, task;
        Map<String, String> notice;
        try (var original = new Instance(evidence, "initial", INITIAL, 86400000)) {
            fixture = new CommerceFixture(jdbc, original.base);
            try {
                order = PaymentHttpFixture.paidOrder(fixture, jdbc);
                notice = PaymentHttpFixture.notice(jdbc, order);
                var shipped = fixture.merchantClient.post(
                        "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment",
                        Map.of("carrierName", "顺丰", "trackingNo", "SFSYSTEM2026110401"));
                assertEquals(200, shipped.statusCode(), shipped.body());
                assertEquals(200, fixture.client.post("/orders/" + order + "/shipment/confirm", Map.of()).statusCode());
                task = jdbc.queryForObject(
                        "SELECT id FROM member_reward_tasks WHERE user_id=? AND reward_kind='GROWTH'", Long.class,
                        fixture.buyer.id());
                // SQL屏障确保两个恢复实例都就绪后再竞争；租约为故障夹具，并非声称杀在认领窗口。
                jdbc.update(
                        "UPDATE member_reward_tasks SET claim_token='system-abandoned',claim_until=?,next_attempt_at=? WHERE id=?",
                        INITIAL.plusSeconds(30), INITIAL.plusYears(1), task);
                assertEquals(1990,
                        count("SELECT available_point_units FROM member_accounts WHERE user_id=?", fixture.buyer.id()));
                assertEquals(0,
                        count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
                Files.writeString(evidence.resolve("恢复前状态.json"),
                        "{\"orderId\":" + order + ",\"taskId\":" + task + ",\"userId\":" + fixture.buyer.id() +
                                ",\"pointUnits\":1990,\"growth\":0,\"leaseFixture\":true}");
                original.process.destroyForcibly();
                assertTrue(original.process.waitFor(10, TimeUnit.SECONDS));
            } finally {
                fixture.client.close();
                fixture.merchantClient.close();
            }
        }
        var recoveryTime = INITIAL.plusDays(7).plusMinutes(1);
        try (var first = new Instance(evidence, "recovery-a", recoveryTime, 1000);
             var second = new Instance(evidence, "recovery-b", recoveryTime, 1000);
             var firstClient = CommerceFixture.login(first.base, fixture.buyer);
             var secondClient = CommerceFixture.login(second.base, fixture.buyer)) {
            assertEquals("success", firstClient.form("/payments/alipay/notify", notice).body());
            assertEquals("success", secondClient.form("/payments/alipay/notify", notice).body());
            assertEquals(1, count("SELECT COUNT(*) FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
            assertEquals(0, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
            jdbc.update("UPDATE member_reward_tasks SET next_attempt_at=? WHERE id=?", recoveryTime, task);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!"APPLIED".equals(
                    jdbc.queryForObject("SELECT status FROM member_reward_tasks WHERE id=?", String.class, task))
                    && System.nanoTime() < deadline)
                Thread.sleep(100);
            assertEquals("APPLIED",
                    jdbc.queryForObject("SELECT status FROM member_reward_tasks WHERE id=?", String.class, task));
            assertEquals(1, count("SELECT COUNT(*) FROM member_growth_entries WHERE user_id=?", fixture.buyer.id()));
            assertEquals(1, count("SELECT COUNT(*) FROM member_point_entries WHERE user_id=? AND event_kind='EARN'",
                    fixture.buyer.id()));
            assertEquals(1990,
                    count("SELECT available_point_units FROM member_accounts WHERE user_id=?", fixture.buyer.id()));
            assertEquals(new java.math.BigDecimal("19.90"),
                    jdbc.queryForObject("SELECT growth_amount FROM member_accounts WHERE user_id=?",
                            java.math.BigDecimal.class, fixture.buyer.id()));
            assertEquals(200, firstClient.get("/members/me").statusCode());
            assertEquals(200, secondClient.get("/members/me").statusCode());
            assertNull(
                    jdbc.queryForObject("SELECT claim_token FROM member_reward_tasks WHERE id=?", String.class, task));
            Files.writeString(evidence.resolve("恢复后状态.json"), "{\"orderId\":" + order + ",\"taskId\":" + task +
                    ",\"pointLots\":1,\"growthEntries\":1,\"pointUnits\":1990,\"growth\":19.90,\"state\":\"APPLIED\"}");
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static final class Instance implements AutoCloseable {
        final Process process;
        final String base;

        Instance(Path evidence, String label, LocalDateTime now, long scanDelay) throws Exception {
            int port = "recovery-b".equals(label) ? 18126 : 18125;
            base = "http://127.0.0.1:" + port;
            var log = evidence.resolve(label + "-console.log");
            var arguments = new ArrayList<>(
                    List.of("-Dmember.system.enabled=true", "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai",
                            "-Duser.home=" + System.getProperty("user.home"),
                            "-Xmx512m",
                            "-cp", System.getProperty("surefire.test.class.path"),
                            MemberSystemApplication.class.getName(),
                            "--server.address=127.0.0.1", "--server.port=" + port,
                            "--spring.profiles.active=test,verifyCode",
                            "--spring.config.import=optional:file:./target/member-system-no-local.yml",
                            "--spring.autoconfigure.exclude=",
                            "--spring.datasource.url=" + URL, "--spring.datasource.username=" + USERNAME,
                            "--spring.datasource.password=" + PASSWORD,
                            "--spring.datasource.hikari.maximum-pool-size=4", "--spring.data.redis.port=" + REDIS_PORT,
                            "--spring.data.redis.database=" + REDIS_DATABASE,
                            "--shop.auth.jwt.secret=" + System.getProperty("shop.auth.jwt.secret"),
                            "--shop.auth.jwt.cookie-path=/", "--shop.auth.jwt.cookie-secure=false",
                            "--shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                            "--shop.sms.lookup-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                            "--shop.member.enabled=true", "--shop.member.reward-start-at=2026-11-01T00:00:00",
                            "--shop.member.scan-delay-ms=" + scanDelay, "--member.system.now=" + now,
                            "--shop.shipment.rocket-enabled=false", "--shop.shipment.expiry-enabled=false",
                            "--shop.payment.expiry-scan-delay-ms=86400000",
                            "--shop.payment.refund-scan-delay-ms=86400000",
                            "--shop.aftersale.timeout-scan-delay-ms=86400000",
                            "--logging.file.name=" + evidence.resolve(label + "-app.log")));
            Path argFile = evidence.resolve(label + "-jvm.args");
            Files.write(argFile,
                    arguments.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
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
                        } catch (java.io.IOException starting) { /* 有界启动探测；超时保留进程日志后失败。 */ }
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
