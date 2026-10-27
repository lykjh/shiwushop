package com.shiwushop.user;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=",
        "spring.session.data.redis.namespace=shiwushop:session:live-test"
})
class RedisSessionLiveIT {
    @Autowired private CaptchaProofStore registrationProofs;
    private static final String RATE_PREFIX = "shiwushop:rate:login:test:" + UUID.randomUUID();

    @DynamicPropertySource
    static void ratePrefix(DynamicPropertyRegistry registry) {
        registry.add("shop.security.login.key-prefix", () -> RATE_PREFIX);
    }

    @Autowired private SessionRepository<? extends Session> repository;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sessionPersistsInRedisRepository() {
        verifyRepository(repository);
    }

    @Test
    void loginCookieUsesRedisSessionAndLogoutRemovesIt() throws Exception {
        String username = "redis_live_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            URI csrfUri = URI.create("http://127.0.0.1:" + port + "/users/csrf");
            HttpResponse<String> csrfResponse = client.send(HttpRequest.newBuilder(csrfUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrfResponse.statusCode());
            String token = json.readTree(csrfResponse.body()).path("data").asString();
            URI loginUri = URI.create("http://127.0.0.1:" + port + "/users/login");
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(loginUri)
                            .header("Content-Type", "application/json")
                            .header("X-CSRF-TOKEN", token)
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"username\":\"" + username + "\",\"password\":\"Shop123!\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            String cookieValue = HttpCookie.parse(login.headers().firstValue("Set-Cookie").orElseThrow()).stream()
                    .filter(cookie -> "MALLSESSION".equals(cookie.getName()))
                    .map(HttpCookie::getValue).findFirst().orElseThrow();
            String sessionId = new String(java.util.Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
            Session stored = repository.findById(sessionId);
            assertNotNull(stored);
            assertEquals(userId, stored.getAttribute("userId"));
            HttpResponse<String> me = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/users/me")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode());
            HttpResponse<String> logout = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/users/logout"))
                    .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, logout.statusCode());
            assertNull(repository.findById(sessionId));
        } finally {
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
    }

    @Test
    void repeatedLoginFailuresRequireCaptchaAcrossHttpRequests() throws Exception {
        String username = "missing_" + UUID.randomUUID().toString().replace("-", "");
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            HttpResponse<String> csrfResponse = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/users/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrfResponse.statusCode());
            String token = json.readTree(csrfResponse.body()).path("data").asString();
            URI loginUri = URI.create("http://127.0.0.1:" + port + "/users/login");
            for (int attempt = 1; attempt <= 6; attempt++) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(loginUri)
                                .header("Content-Type", "application/json")
                                .header("X-CSRF-TOKEN", token)
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"username\":\"" + username + "\",\"password\":\"wrong\"}"))
                                .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(attempt <= 5 ? 401 : 403, response.statusCode());
                if (attempt == 6) {
                    assertEquals("CAPTCHA_REQUIRED", json.readTree(response.body()).path("data").path("state").asString());
                }
            }
        }
    }

    private static <S extends Session> void verifyRepository(SessionRepository<S> repository) {
        S session = repository.createSession();
        session.setAttribute("userId", 42L);
        repository.save(session);
        try {
            Session loaded = repository.findById(session.getId());
            assertNotNull(loaded);
            assertEquals(Long.valueOf(42L), loaded.getAttribute("userId"));
        } finally {
            repository.deleteById(session.getId());
        }
    }
}
