package com.shiwushop.integration.account;

import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.maximum-pool-size=2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginHttpTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    TestAccounts fixtures;
    TestAccounts.Account account;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void prepare() {
        fixtures = new TestAccounts(jdbc);
        account = fixtures.create("USER", "Shop123!");
    }

    @AfterEach
    void cleanup() {
        redis.delete("shiwushop:auth:logins:" + account.id());
        redis.delete("shiwushop:auth:state:" + account.id());
        fixtures.close();
    }

    HttpAccountClient client() throws Exception {
        var c = new HttpAccountClient("http://127.0.0.1:" + port);
        c.csrf();
        return c;
    }

    @Test
    void correctPasswordIssuesJwtAndRotatesAnonymousCookie() throws Exception {
        try (var c = client()) {
            String anonymous = c.browserId();
            var response = c.post("/users/login",
                    Map.of("username", account.username(), "password", account.password()));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(account.username(), json.readTree(response.body()).path("data").path("username").asString());
            assertFalse(response.body().contains("password"));
            assertNotEquals(anonymous, c.browserId());
            assertNoSessionCookie(response);
            assertNotNull(c.authToken());
            assertEquals(1L, redis.opsForHash().size("shiwushop:auth:logins:" + account.id()));
            assertEquals(200, c.get("/users/me").statusCode());
        }
    }

    static Stream<String> wrongPasswords() {
        return Stream.of("wrong123", "shop123!", " Shop123!", "Shop123! ", "a".repeat(73));
    }

    @ParameterizedTest
    @MethodSource("wrongPasswords")
    void credentialFailuresHaveOneMessageAndNoIdentity(String password) throws Exception {
        try (var c = client()) {
            var response = c.post("/users/login", Map.of("username", account.username(), "password", password));
            assertEquals(401, response.statusCode(), response.body());
            assertEquals("账号或密码错误", json.readTree(response.body()).path("message").asString());
            assertNoSessionCookie(response);
            assertNull(c.authToken());
            assertEquals(401, c.get("/users/me").statusCode());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingOrBlankPasswordReturns400(String password) throws Exception {
        try (var c = client()) {
            Map<String, Object> body = new HashMap<>();
            body.put("username", account.username());
            body.put("password", password);
            assertEquals(400, c.post("/users/login", body).statusCode());
        }
    }

    @Test
    void disabledAndUnknownAccountsUseTheSameCredentialMessage() throws Exception {
        jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE user_id=?", account.id());
        try (var c = client()) {
            for (String name : List.of(account.username(), "absent" + account.username())) {
                var response = c.post("/users/login", Map.of("username", name, "password", account.password()));
                assertEquals(401, response.statusCode(), response.body());
                assertEquals("账号或密码错误", json.readTree(response.body()).path("message").asString());
            }
        }
    }

    @Test
    void storedUnicodePasswordStillAuthenticates() throws Exception {
        var legacy = fixtures.create("USER", "Shop密123!");
        try (var c = client()) {
            var response = c.post("/users/login", Map.of("username", legacy.username(), "password", legacy.password()));
            assertEquals(200, response.statusCode(), response.body());
        }
    }

    @Test
    void logoutRevokesJwtAndRotatesAnonymousCookie() throws Exception {
        try (var c = client()) {
            assertEquals(200,
                    c.post("/users/login", Map.of("username", account.username(), "password", account.password()))
                            .statusCode());
            c.csrf();
            String id = c.browserId();
            String jwt = c.authToken();
            assertNotNull(jwt);
            var logout = c.post("/users/logout", Map.of());
            assertEquals(200, logout.statusCode());
            assertNotEquals(id, c.browserId());
            assertNoSessionCookie(logout);
            assertNull(c.authToken());
            assertEquals(0L, redis.opsForHash().size("shiwushop:auth:logins:" + account.id()));
            assertEquals(401, c.get("/users/me").statusCode());
            assertEquals(401, c.replayAuthToken("/users/me", jwt).statusCode());
            assertEquals(200, c.post("/users/logout", Map.of()).statusCode());
        }
    }

    @Test
    void jwtDoesNotRequireBrowserCookieAndRejectsExpiredLoginRecord() throws Exception {
        try (var c = client()) {
            assertEquals(200,
                    c.post("/users/login", Map.of("username", account.username(), "password", account.password()))
                            .statusCode());
            String jwt = c.authToken();
            assertEquals(200, c.replayAuthToken("/users/me", jwt).statusCode());
            String key = "shiwushop:auth:logins:" + account.id();
            var hashes = redis.<String, String>opsForHash();
            String sid = hashes.keys(key).iterator().next();
            String[] fields = hashes.get(key, sid).split(":");
            fields[1] = Long.toString(java.time.Instant.now().minus(Duration.ofMinutes(31)).toEpochMilli());
            hashes.put(key, sid, String.join(":", fields));
            assertEquals(401, c.replayAuthToken("/users/me", jwt).statusCode());
        }
    }

    @Test
    void csrfFromAnotherBrowserOrPriorLoginIsRejected() throws Exception {
        try (var owner = client(); var other = client(); var raw = HttpClient.newHttpClient()) {
            var credentials = Map.of("username", account.username(), "password", account.password());
            String ownerCookie = owner.cookieHeader("/users/login");
            assertEquals(403, replayWrite(raw, "/users/login", ownerCookie, "invalid", credentials));
            String otherCookie = other.cookieHeader("/users/login");
            assertEquals(403, replayWrite(raw, "/users/login", otherCookie, owner.csrfToken(), credentials));
            assertEquals(200, owner.post("/users/login", credentials).statusCode());

            owner.csrf();
            String priorLoginCookie = owner.cookieHeader("/users/logout");
            String priorLoginToken = owner.csrfToken();
            assertEquals(200, owner.post("/users/logout", Map.of()).statusCode());
            assertEquals(403, replayWrite(raw, "/users/logout", priorLoginCookie, priorLoginToken, Map.of()));
            assertEquals(200, owner.post("/users/logout", Map.of()).statusCode());
        }
    }

    @Test
    void independentCsrfFetchesForOneBrowserKeepBothHeadersUsable() throws Exception {
        try (var browser = client(); var raw = HttpClient.newHttpClient()) {
            var uri = URI.create("http://127.0.0.1:" + port + "/users/csrf");
            String browserCookie = Arrays.stream(browser.cookieHeader("/users/csrf").split(";\\s*"))
                    .filter(value -> value.startsWith("MALLBROWSER=")).findFirst().orElseThrow();
            var bootstrap = HttpRequest.newBuilder(uri).header("Cookie", browserCookie).GET().build();
            var first = raw.sendAsync(bootstrap, HttpResponse.BodyHandlers.ofString());
            var second = raw.sendAsync(bootstrap, HttpResponse.BodyHandlers.ofString());
            var one = first.get();
            var two = second.get();
            assertEquals(200, one.statusCode());
            assertEquals(200, two.statusCode());
            String cookie = two.headers().allValues("Set-Cookie").stream()
                    .filter(value -> value.startsWith("MALLCSRF=")).findFirst().orElseThrow().split(";", 2)[0];
            for (var reply : List.of(one, two)) {
                String value = json.readTree(reply.body()).path("data").asString();
                assertEquals(400, replayWrite(raw, "/users/login", browserCookie + "; " + cookie, value, Map.of()));
            }
        }
    }

    private int replayWrite(HttpClient raw, String path, String cookies, String csrf, Map<String, ?> body)
            throws Exception {
        return raw.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", cookies).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static void assertNoSessionCookie(HttpResponse<?> response) {
        for (String cookie : response.headers().allValues("Set-Cookie")) {
            assertFalse(cookie.startsWith("MALLFLOW=") || cookie.startsWith("JSESSIONID="));
        }
    }
}
