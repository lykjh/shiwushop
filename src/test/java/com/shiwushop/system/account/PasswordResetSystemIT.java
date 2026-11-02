package com.shiwushop.system.account;

import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.support.*;
import com.shiwushop.user.auth.PhoneLookup;
import com.shiwushop.user.auth.SmsChallengeStore;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 独立部署双实例，无 Spring 测试容器；验证码仅在专用隔离 Redis 准备，不发送收费短信。 */
class PasswordResetSystemIT {
    final PhoneLookup lookup = new PhoneLookup("QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=");
    final PrivacyCipher privacy = new PrivacyCipher("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=");
    final ObjectMapper json = new ObjectMapper();
    final org.springframework.jdbc.core.JdbcTemplate jdbc = SystemEnvironment.database();
    TestAccounts fixtures;
    TestAccounts.Account account;
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    String base, peer, phone, digest;
    SmsChallengeStore challenges;

    @BeforeEach void prepare() {
        base = SystemEnvironment.base(); peer = System.getProperty("system.peer-url");
        assertEquals("http://127.0.0.1:18081", base); assertEquals("http://127.0.0.1:18082", peer);
        connection = new LettuceConnectionFactory("127.0.0.1", 26379); connection.afterPropertiesSet(); connection.start();
        redis = new StringRedisTemplate(connection); challenges = new SmsChallengeStore(redis, 60, 3600, 300, 10, 5);
        fixtures = new TestAccounts(jdbc); account = fixtures.create("USER", "Shop123!");
        phone = "139" + String.format("%08d", System.nanoTime() % 100_000_000); digest = lookup.digest(phone);
        jdbc.update("INSERT INTO user_private(user_id,phone_ciphertext,phone_lookup_hash,key_version) VALUES(?,?,?,1)",
                account.id(), privacy.encrypt(account.id(), "login-phone", phone), digest);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM user_private WHERE user_id=?", account.id()); fixtures.close();
        String prefix = "shiwushop:sms:{" + digest + "}";
        redis.delete(List.of(prefix + ":cooldown", prefix + ":sends", prefix + ":password-reset:code", "shiwushop:auth:state:" + account.id()));
        connection.destroy();
    }

    /** 只模拟运营商已成功交付的验证码，后续 HTTP 校验、Session、事务及撤销均由部署实例执行。 */
    String verifyPreparedCode(HttpAccountClient owner) throws Exception {
        challenges.reserve(digest, "password-reset", "delivery-confirmed", lookup.codeMac("password-reset", digest, "123456"));
        challenges.activate(digest, "password-reset", "delivery-confirmed");
        var verified = owner.post("/users/password/reset/verify", Map.of("phone", phone, "code", "123456"));
        assertEquals(200, verified.statusCode(), verified.body());
        return json.readTree(verified.body()).path("data").path("resetTicket").asString();
    }

    @Test void ticketFromFirstInstanceResetsOnSecondAndRevokesBothLoginSessions() throws Exception {
        try (var owner = CommerceFixture.login(base, account); var second = CommerceFixture.login(peer, account);
             var raw = HttpClient.newHttpClient()) {
            owner.csrf(); String ticket = verifyPreparedCode(owner);
            String csrf = json.readTree(owner.get("/users/csrf").body()).path("data").asString();
            String cookie = Base64.getEncoder().encodeToString(owner.sessionId().getBytes(StandardCharsets.UTF_8));
            var reset = raw.send(HttpRequest.newBuilder(URI.create(peer + "/users/password/reset"))
                    .header("Cookie", "MALLSESSION=" + cookie).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("resetTicket", ticket, "newPassword", "Shop456!")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reset.statusCode(), reset.body());
            assertEquals(401, owner.get("/users/me").statusCode()); assertEquals(401, second.get("/users/me").statusCode());
            owner.csrf();
            assertEquals(401, owner.post("/users/password/reset", Map.of("resetTicket", ticket, "newPassword", "Shop789!")).statusCode());
            assertEquals(401, owner.post("/users/login", Map.of("username", account.username(), "password", account.password())).statusCode());
            assertEquals(200, owner.post("/users/login", Map.of("username", account.username(), "password", "Shop456!")).statusCode());
            assertEquals(1, jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, account.id()));
            System.out.println("D04_CROSS_INSTANCE verify_A=200 reset_B=200 old_sessions=401 replay=401 old_password=401 new_password=200 version=1");
        }
    }

    @Test void redisOutageRejectsResetAndSameProofWorksAfterRecovery() throws Exception {
        try (var owner = new HttpAccountClient(base)) {
            owner.csrf(); String ticket = verifyPreparedCode(owner);
            var body = Map.<String,Object>of("resetTicket", ticket, "newPassword", "Shop456!");
            try {
                docker("stop");
                var denied = owner.post("/users/password/reset", body);
                assertEquals(503, denied.statusCode(), denied.body());
                assertTrue(new BCryptPasswordEncoder().matches(account.password(), jdbc.queryForObject(
                        "SELECT password_hash FROM user_accounts WHERE user_id=?", String.class, account.id())));
                assertEquals(0, jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, account.id()));
            } finally { docker("start"); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            int status = 503;
            while (status == 503 && System.nanoTime() < deadline) {
                var recovered = owner.post("/users/password/reset", body); status = recovered.statusCode();
                if (status == 503) Thread.sleep(200);
            }
            assertEquals(200, status); assertEquals(401, owner.get("/users/me").statusCode());
            System.out.println("D04_REDIS_OUTAGE reset=503 password_unchanged=true recovered_same_proof=200 no_auto_login=401");
        }
    }

    /** 故障注入只允许停止本任务的专用容器，不能触及业务 Redis 或共享的 16379 测试服务。 */
    void docker(String operation) throws Exception {
        String container = System.getProperty("system.redis-container");
        assertEquals("shiwushop-d04-redis", container);
        Process process = new ProcessBuilder("docker", operation, container).redirectErrorStream(true).start();
        assertTrue(process.waitFor(25, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
    }
}
