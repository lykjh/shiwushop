package com.shiwushop.user;

import com.shiwushop.account.auth.PhoneLookup;
import com.shiwushop.account.entity.status.AccountRole;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.entity.user.UserProfile;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.mapper.user.UserProfileMapper;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.account.service.SmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=",
        "spring.session.data.redis.namespace=shiwushop:session:sms-live-test",
        "shop.sms.lookup-key-base64=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "shop.privacy.key-v1-base64=QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
})
class SmsHttpSessionLiveTests {
    @Autowired private UserProfileMapper profiles;
    @Autowired private UserAccountMapper accounts;
    @Autowired private PhoneLookup lookup;
    @Autowired private PrivacyCipher privacy;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private SessionRepository<? extends Session> sessions;
    @MockitoBean private SmsSender sender;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void csrfAndRealRedisSessionProtectSmsLogin() throws Exception {
        String phone = "138" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        UserProfile profile = new UserProfile();
        profiles.insertEmpty(profile);
        Long userId = profile.getId();
        String username = "sms_live_" + UUID.randomUUID().toString().replace("-", "");
        UserAccount account = new UserAccount();
        account.setUserId(userId);
        account.setUsername(username);
        account.setPasswordHash(new BCryptPasswordEncoder().encode("Shop123!"));
        account.setRole(AccountRole.USER);
        account.setStatus(AccountStatus.ACTIVE);
        accounts.insert(account);
        jdbc.update("INSERT INTO user_private (user_id, phone_ciphertext, phone_lookup_hash, key_version) VALUES (?,?,?,1)",
                userId, privacy.encrypt(userId, "login-phone", phone), lookup.digest(phone));

        AtomicReference<String> code = new AtomicReference<>();
        when(sender.configured()).thenReturn(true);
        doAnswer(call -> { code.set(call.getArgument(1)); return null; })
                .when(sender).send(anyString(), anyString());
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            URI sendUri = uri("/users/sms/send");
            String body = "{\"phone\":\"" + phone + "\"}";
            assertEquals(403, client.send(post(sendUri, body, null), HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(uri("/users/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrf.statusCode());
            String token = json.readTree(csrf.body()).path("data").asString();
            assertEquals(200, client.send(post(sendUri, body, token), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertNotNull(code.get());

            HttpResponse<String> login = client.send(post(uri("/users/sms/login"),
                    "{\"phone\":\"" + phone + "\",\"code\":\"" + code.get() + "\"}", token),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            assertEquals(userId.longValue(), json.readTree(login.body()).path("data").path("id").asLong());
            String cookieValue = HttpCookie.parse(login.headers().firstValue("Set-Cookie").orElseThrow()).stream()
                    .filter(cookie -> "MALLSESSION".equals(cookie.getName()))
                    .map(HttpCookie::getValue).findFirst().orElseThrow();
            String sessionId = new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
            assertEquals(userId, jdbc.queryForObject("SELECT user_id FROM user_private WHERE phone_lookup_hash=?",
                    Long.class, lookup.digest(phone)));
            assertEquals(200, client.send(HttpRequest.newBuilder(uri("/users/me")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, client.send(post(uri("/users/sms/login"),
                    "{\"phone\":\"" + phone + "\",\"code\":\"" + code.get() + "\"}", token),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(userId, sessions.findById(sessionId).getAttribute("userId"));
        } finally {
            jdbc.update("DELETE FROM user_private WHERE user_id=?", userId);
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
            redis.delete("shiwushop:auth:state:" + userId);
            String prefix = "shiwushop:sms:{" + lookup.digest(phone) + "}";
            redis.delete(List.of(prefix + ":sends", prefix + ":cooldown", prefix + ":login:code"));
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpRequest post(URI uri, String body, String csrf) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json");
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
