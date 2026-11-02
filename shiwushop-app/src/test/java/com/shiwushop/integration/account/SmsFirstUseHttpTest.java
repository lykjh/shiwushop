package com.shiwushop.integration.account;

import com.shiwushop.user.auth.PhoneLookup;
import com.shiwushop.user.service.SmsSender;
import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 仅替换收费短信发送适配器；HTTP、验证码消费、票据、事务和 Redis Session 均真实执行。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.session.data.redis.namespace=shiwushop:session:first-use-http",
        "shop.sms.lookup-key-base64=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "shop.privacy.key-v1-base64=QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SmsFirstUseHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PhoneLookup lookup;
    @MockitoBean SmsSender sender;
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<String> code = new AtomicReference<>();
    String phone;
    @BeforeEach void prepare() {
        phone = "139" + String.format("%08d", System.nanoTime() % 100_000_000);
        when(sender.configured()).thenReturn(true);
        doAnswer(call -> {code.set(call.getArgument(1)); return null;}).when(sender).send(anyString(), anyString());
    }
    @AfterEach void cleanup() {
        String digest = lookup.digest(phone);
        var ids = jdbc.queryForList("SELECT user_id FROM user_private WHERE phone_lookup_hash=?", Long.class, digest);
        for (long id : ids) {
            jdbc.update("DELETE FROM user_private WHERE user_id=?", id);
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", id);
            jdbc.update("DELETE FROM users WHERE id=?", id);
            redis.delete("shiwushop:auth:state:" + id);
        }
        String prefix = "shiwushop:sms:{" + digest + "}";
        redis.delete(List.of(prefix + ":sends", prefix + ":cooldown", prefix + ":entry:code"));
    }
    @Test void firstUseThenRepeatLoginKeepsOneAccountAndRejectsCrossSessionAndReplay() throws Exception {
        String base = "http://127.0.0.1:" + port;
        try (var client = new HttpAccountClient(base); var other = new HttpAccountClient(base)) {
            client.csrf(); other.csrf();
            assertEquals(403, client.post("/users/sms/entry/send", Map.of("phone", phone), false).statusCode());
            var send = client.post("/users/sms/entry/send", Map.of("phone", phone));
            assertEquals(200, send.statusCode(), send.body()); assertNotNull(code.get());
            assertFalse(send.body().contains(code.get()));
            var verify = client.post("/users/sms/entry/verify", Map.of("phone", phone, "code", code.get()));
            assertEquals(200, verify.statusCode(), verify.body());
            var data = json.readTree(verify.body()).path("data");
            assertEquals("SET_PASSWORD_REQUIRED", data.path("state").asString());
            String ticket = data.path("registrationTicket").asString();
            var registration = Map.<String,Object>of("phone", phone, "registrationTicket", ticket, "password", "Shop123!");
            assertEquals(401, other.post("/users/sms/entry/register", registration).statusCode());
            assertEquals(401, client.post("/users/sms/entry/verify", Map.of("phone", phone, "code", code.get())).statusCode());
            String oldSession = client.sessionId();
            var created = client.post("/users/sms/entry/register", registration);
            assertEquals(200, created.statusCode(), created.body());
            long id = json.readTree(created.body()).path("data").path("id").asLong();
            assertNotEquals(oldSession, client.sessionId());
            assertTrue(new BCryptPasswordEncoder().matches("Shop123!", jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE user_id=?", String.class, id)));
            var me = client.get("/users/me"); assertEquals(200, me.statusCode());
            assertEquals(phone.substring(0,3) + "****" + phone.substring(7), json.readTree(me.body()).path("data").path("phoneMasked").asString());
            assertFalse(me.body().contains(phone));
            client.csrf(); assertEquals(200, client.post("/users/logout", Map.of()).statusCode()); client.csrf();
            assertEquals(401, client.post("/users/sms/entry/register", registration).statusCode());
            // 只解除自有手机号的发送冷却，避免为此功能验证等待一分钟。
            redis.delete("shiwushop:sms:{" + lookup.digest(phone) + "}:cooldown");
            assertEquals(200, client.post("/users/sms/entry/send", Map.of("phone", phone)).statusCode());
            var login = client.post("/users/sms/entry/verify", Map.of("phone", phone, "code", code.get()));
            assertEquals(200, login.statusCode(), login.body());
            assertEquals("LOGGED_IN", json.readTree(login.body()).path("data").path("state").asString());
            assertEquals(id, json.readTree(login.body()).path("data").path("id").asLong());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_private WHERE phone_lookup_hash=?", Integer.class, lookup.digest(phone)));
        }
    }
}
