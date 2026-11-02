package com.shiwushop.integration.account;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离 MySQL/Redis + 真 Tomcat 端口；滑块 proof 用真实存储签发，不伪造业务服务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "spring.session.data.redis.namespace=shiwushop:session:register-http"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegisterHttpTest {
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());
    @LocalServerPort int port;
    @Autowired CaptchaProofStore proofs;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final Set<String> createdNames = new HashSet<>();
    private String buyer() { String name = "buyer" + SEQUENCE.incrementAndGet(); createdNames.add(name); return name; }
    private HttpAccountClient client() throws Exception {
        var client = new HttpAccountClient("http://127.0.0.1:" + port); client.csrf(); return client;
    }
    private HttpResponse<String> register(HttpAccountClient client, String username, String password) throws Exception {
        String proof = proofs.issue("boundary" + SEQUENCE.incrementAndGet(), client.sessionId());
        Map<String, Object> body = new HashMap<>();
        body.put("username", username); body.put("password", password); body.put("captchaProof", proof);
        return client.post("/users/register", body);
    }
    @AfterEach void cleanOnlyCreatedAccounts() {
        for (String name : createdNames) {
            List<Long> ids = jdbc.queryForList("SELECT user_id FROM user_accounts WHERE username=?", Long.class, name);
            for (Long id : ids) {
                jdbc.update("DELETE FROM user_accounts WHERE user_id=?", id);
                jdbc.update("DELETE FROM users WHERE id=?", id);
            }
        }
    }
    static Stream<String> validPasswords() { return Stream.of("Shop123!", "a".repeat(8), "a".repeat(72), "12345678", "!~Aa1234"); }
    static Stream<String> invalidPasswords() { return Stream.of(null, "", "        ", "short12", "a".repeat(73), "a".repeat(1000),
            "Shop密123!", "Shop😀123!", " Shop123!", "Shop123! ", "Shop\t123!"); }

    @ParameterizedTest @MethodSource("validPasswords")
    void persistsMatchingBcryptAndDefaultIdentity(String password) throws Exception {
        String name = buyer();
        try (var client = client()) {
            var response = register(client, name, password);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(200, json.readTree(response.body()).path("code").asInt());
            assertFalse(response.body().contains("password"));
            var account = jdbc.queryForMap("SELECT a.password_hash,a.role,a.status FROM user_accounts a JOIN users u ON u.id=a.user_id WHERE username=?", name);
            String hash = (String) account.get("password_hash");
            assertNotEquals(password, hash);
            assertTrue(hash.startsWith("$2"));
            assertTrue(new BCryptPasswordEncoder().matches(password, hash));
            assertEquals("USER", account.get("role")); assertEquals("ACTIVE", account.get("status"));
        }
    }
    @ParameterizedTest @MethodSource("invalidPasswords")
    void rejectsInvalidPasswordWithoutPersistingAccount(String password) throws Exception {
        String name = buyer();
        try (var client = client()) {
            var response = register(client, name, password);
            assertEquals(400, response.statusCode(), response.body());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username=?", Integer.class, name));
        }
    }
    @Test void duplicateNameRollsBackTheNewProfile() throws Exception {
        String name = buyer();
        try (var client = client()) {
            assertEquals(200, register(client, name, "Shop123!").statusCode());
            Long before = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
            var response = register(client, name, "Shop123!");
            assertEquals(409, response.statusCode(), response.body());
            assertEquals("账号已存在", json.readTree(response.body()).path("message").asString());
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class));
        }
    }
    @Test void missingCsrfIsRejectedBeforeAccountCreation() throws Exception {
        String name = buyer();
        try (var client = client()) {
            var response = client.post("/users/register", Map.of("username", name, "password", "Shop123!"), false);
            assertEquals(403, response.statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username=?", Integer.class, name));
        }
    }
    @Test void missingProofReturnsCaptchaState() throws Exception {
        try (var client = client()) {
            var response = client.post("/users/register", Map.of("username", buyer(), "password", "Shop123!"));
            assertEquals(403, response.statusCode());
            assertEquals("CAPTCHA_REQUIRED", json.readTree(response.body()).path("data").path("state").asString());
        }
    }
    @Test void proofCannotBeReplayedAfterRegistration() throws Exception {
        try (var client = client()) {
            String proof = proofs.issue("boundary" + SEQUENCE.incrementAndGet(), client.sessionId());
            assertEquals(200, client.post("/users/register", Map.of("username", buyer(), "password", "Shop123!", "captchaProof", proof)).statusCode());
            assertEquals(400, client.post("/users/register", Map.of("username", buyer(), "password", "Shop123!", "captchaProof", proof)).statusCode());
        }
    }
    @Test void concurrentDuplicateNameHasOneWinner() throws Exception {
        String name = buyer();
        try (var first = client(); var second = client(); var pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            var a = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return register(first, name, "Shop123!").statusCode(); });
            var b = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return register(second, name, "Shop123!").statusCode(); });
            assertEquals(List.of(200, 409), Stream.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)).sorted().toList());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username=?", Integer.class, name));
        }
    }
    @Test void usernameMinimumAndMaximumLengthsAreStoredWithoutTruncation() throws Exception {
        String shortName;
        do {
            shortName = "z" + String.format("%03d", SEQUENCE.incrementAndGet() % 1000);
        } while (jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username=?", Integer.class, shortName) > 0);
        String longName = "buyer" + "a".repeat(82) + SEQUENCE.incrementAndGet();
        assertEquals(4, shortName.length()); assertEquals(100, longName.length());
        createdNames.add(shortName); createdNames.add(longName);
        try (var client = client()) {
            for (String name : List.of(shortName, longName)) {
                var response = register(client, name, "Shop123!");
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(name, jdbc.queryForObject("SELECT username FROM user_accounts WHERE username=?", String.class, name));
            }
        }
    }
    static Stream<String> invalidUsernames() {
        return Stream.of(null, "", "    ", "abc", "a".repeat(101), "buyer zhang", "买家张明", "buyer@shop", " buyer_zhang");
    }
    @ParameterizedTest @MethodSource("invalidUsernames")
    void rejectsInvalidUsernameAtTheRealHttpBoundary(String name) throws Exception {
        try (var client = client()) {
            var response = register(client, name, "Shop123!");
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(json.readTree(response.body()).path("data").path("fields").has("username"));
        }
    }
    @Test void independentSessionsCanRegisterBehindTheSameExit() throws Exception {
        for (int index = 0; index < 6; index++) {
            try (var client = client()) {
                var response = register(client, buyer(), "Shop123!");
                assertEquals(200, response.statusCode(), response.body());
            }
        }
    }
    @Test void anotherSessionCannotUseTheProof() throws Exception {
        try (var owner = client(); var stranger = client()) {
            String proof = proofs.issue("boundary" + SEQUENCE.incrementAndGet(), owner.sessionId());
            String name = buyer();
            var response = stranger.post("/users/register", Map.of("username", name, "password", "Shop123!", "captchaProof", proof));
            assertEquals(400, response.statusCode(), response.body());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username=?", Integer.class, name));
        }
    }

    @Test void usernameCaseMatchesTheDatabaseUniqueConstraintAndLoginLookup() throws Exception {
        String name = buyer(); String uppercase = name.toUpperCase(Locale.ROOT);
        try (var client = client()) {
            assertEquals(200, register(client, name, "Shop123!").statusCode());
            assertEquals(409, register(client, uppercase, "Shop123!").statusCode());
            var login = client.post("/users/login", Map.of("username", uppercase, "password", "Shop123!"));
            assertEquals(200, login.statusCode(), login.body());
            assertEquals(name, json.readTree(login.body()).path("data").path("username").asString());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE username=?", Integer.class, uppercase));
        }
    }
}
