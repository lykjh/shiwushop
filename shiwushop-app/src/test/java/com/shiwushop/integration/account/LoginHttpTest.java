package com.shiwushop.integration.account;

import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.*;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "spring.session.data.redis.namespace=shiwushop:session:login-http"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionRepository<? extends Session> sessions;
    TestAccounts fixtures;
    TestAccounts.Account account;
    final ObjectMapper json = new ObjectMapper();
    @BeforeEach void prepare() { fixtures = new TestAccounts(jdbc); account = fixtures.create("USER", "Shop123!"); }
    @AfterEach void cleanup() { fixtures.close(); }
    HttpAccountClient client() throws Exception { var c = new HttpAccountClient("http://127.0.0.1:" + port); c.csrf(); return c; }
    @Test void correctPasswordPersistsIdentityInRedisAndRotatesCookie() throws Exception {
        try (var c = client()) {
            String anonymous = c.sessionId();
            var response = c.post("/users/login", Map.of("username", account.username(), "password", account.password()));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(account.username(), json.readTree(response.body()).path("data").path("username").asString());
            assertFalse(response.body().contains("password")); assertNotEquals(anonymous, c.sessionId());
            assertNull(sessions.findById(anonymous));
            assertEquals(account.id(), (Long) sessions.findById(c.sessionId()).getAttribute("userId"));
            assertEquals(200, c.get("/users/me").statusCode());
        }
    }
    static Stream<String> wrongPasswords() { return Stream.of("wrong123", "shop123!", " Shop123!", "Shop123! ", "a".repeat(73)); }
    @ParameterizedTest @MethodSource("wrongPasswords")
    void credentialFailuresHaveOneMessageAndNoIdentity(String password) throws Exception {
        try (var c = client()) {
            var response = c.post("/users/login", Map.of("username", account.username(), "password", password));
            assertEquals(401, response.statusCode(), response.body());
            assertEquals("账号或密码错误", json.readTree(response.body()).path("message").asString());
            assertNull(sessions.findById(c.sessionId()).getAttribute("userId"));
            assertEquals(401, c.get("/users/me").statusCode());
        }
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", "   "})
    void missingOrBlankPasswordReturns400(String password) throws Exception {
        try (var c = client()) {
            Map<String,Object> body = new HashMap<>(); body.put("username", account.username()); body.put("password", password);
            assertEquals(400, c.post("/users/login", body).statusCode());
        }
    }
    @Test void disabledAndUnknownAccountsUseTheSameCredentialMessage() throws Exception {
        jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE user_id=?", account.id());
        try (var c = client()) {
            for (String name : List.of(account.username(), "absent" + account.username())) {
                var response = c.post("/users/login", Map.of("username", name, "password", account.password()));
                assertEquals(401, response.statusCode(), response.body());
                assertEquals("账号或密码错误", json.readTree(response.body()).path("message").asString());
            }
        }
    }
    @Test void storedUnicodePasswordStillAuthenticates() throws Exception {
        var legacy = fixtures.create("USER", "Shop密123!");
        try (var c = client()) {
            var response = c.post("/users/login", Map.of("username", legacy.username(), "password", legacy.password()));
            assertEquals(200, response.statusCode(), response.body());
        }
    }
    @Test void logoutRemovesRedisSessionAndRejectsFurtherAccess() throws Exception {
        try (var c = client()) {
            assertEquals(200, c.post("/users/login", Map.of("username", account.username(), "password", account.password())).statusCode());
            c.csrf(); String id = c.sessionId();
            assertEquals(200, c.post("/users/logout", Map.of()).statusCode());
            assertNull(sessions.findById(id)); assertEquals(401, c.get("/users/me").statusCode());
        }
    }
    @Test void expiredRedisSessionCannotAccessProtectedResource() throws Exception {
        try (var c = client()) {
            assertEquals(200, c.post("/users/login", Map.of("username", account.username(), "password", account.password())).statusCode());
            expire(sessions, c.sessionId());
            assertEquals(401, c.get("/users/me").statusCode());
        }
    }
    private static <S extends Session> void expire(SessionRepository<S> repository, String id) {
        S session = repository.findById(id); assertNotNull(session);
        session.setLastAccessedTime(java.time.Instant.now().minusSeconds(120));
        session.setMaxInactiveInterval(Duration.ofSeconds(1)); repository.save(session);
    }
}
