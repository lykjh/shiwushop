package com.shiwushop.integration.http.user;

import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisSessionLiveIT {
    private static final String RATE_PREFIX = "shiwushop:rate:login:test:" + UUID.randomUUID();

    @DynamicPropertySource
    static void ratePrefix(DynamicPropertyRegistry registry) {
        registry.add("shop.security.login.key-prefix", () -> RATE_PREFIX);
    }

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @LocalServerPort
    private int port;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anonymousCsrfDoesNotCreateRedisSessionOrSessionCookie() throws Exception {
        try (var client = new HttpAccountClient("http://127.0.0.1:" + port)) {
            var response = client.get("/users/csrf");
            assertEquals(200, response.statusCode(), response.body());
            assertNotNull(client.browserId());
            assertTrue(response.headers().allValues("Set-Cookie").stream()
                    .noneMatch(cookie -> cookie.startsWith("MALLFLOW=") || cookie.startsWith("JSESSIONID=")));
            try (var keys = redis.scan(ScanOptions.scanOptions().match("shiwushop:anonymous-session:*").count(50).build())) {
                assertFalse(keys.hasNext());
            }
            try (var keys = redis.scan(ScanOptions.scanOptions().match("spring:session:*").count(50).build())) {
                assertFalse(keys.hasNext());
            }
        }
    }

    @Test
    void logoutRemovesRedisLoginAndRejectsOriginalJwt() throws Exception {
        try (var fixtures = new TestAccounts(jdbc); var client = new HttpAccountClient("http://127.0.0.1:" + port)) {
            var account = fixtures.create("USER", "Shop123!");
            String key = "shiwushop:auth:logins:" + account.id();
            try {
                client.csrf();
                assertEquals(200, client.post("/users/login",
                        Map.of("username", account.username(), "password", account.password())).statusCode());
                String token = client.authToken();
                assertNotNull(token);
                String browserId = client.browserId();
                assertEquals(1L, redis.opsForHash().size(key));
                assertEquals(200, client.get("/users/me").statusCode());
                assertEquals(200, client.post("/users/logout", Map.of()).statusCode());
                assertNotEquals(browserId, client.browserId());
                assertEquals(0L, redis.opsForHash().size(key));
                assertEquals(401, client.replayAuthToken("/users/me", token).statusCode());
            } finally {
                redis.delete(key);
                redis.delete("shiwushop:auth:state:" + account.id());
            }
        }
    }

    @Test
    void repeatedLoginFailuresRequireCaptchaAcrossHttpRequests() throws Exception {
        String username = "missing_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = new HttpAccountClient("http://127.0.0.1:" + port)) {
            for (int attempt = 1; attempt <= 6; attempt++) {
                var response = client.post("/users/login", Map.of("username", username, "password", "wrong"));
                assertEquals(attempt <= 5 ? 401 : 403, response.statusCode());
                if (attempt == 6) {
                    assertEquals("CAPTCHA_REQUIRED",
                            json.readTree(response.body()).path("data").path("state").asString());
                }
            }
        }
    }

}
