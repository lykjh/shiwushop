package com.shiwushop.integration.account;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.user.auth.PhoneLookup;
import com.shiwushop.user.auth.SmsChallengeStore;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.SmsSender;
import com.shiwushop.user.service.PhoneBindingService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 HTTP、MySQL、Redis 和 Session；仅替换收费短信，故障用例单独注明注入边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.session.data.redis.namespace=shiwushop:session:password-reset-http",
        "shop.sms.lookup-key-base64=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "shop.privacy.key-v1-base64=QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PhoneLookup lookup;
    @Autowired PrivacyCipher privacy;
    @Autowired PhoneBindingService phoneBindings;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.mybatis.spring.SqlSessionTemplate sql;
    @MockitoBean SmsSender sender;
    @MockitoSpyBean UserAccountMapper accounts;
    @MockitoSpyBean SmsChallengeStore challenges;
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<String> code = new AtomicReference<>();
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    TestAccounts fixtures;
    TestAccounts.Account account;
    String phone, digest, prefix, base;

    @BeforeEach void prepare() {
        base = "http://127.0.0.1:" + port;
        fixtures = new TestAccounts(jdbc); account = fixtures.create("USER", "Shop123!");
        phone = "139" + String.format("%08d", System.nanoTime() % 100_000_000);
        digest = lookup.digest(phone); prefix = "shiwushop:sms:{" + digest + "}";
        jdbc.update("INSERT INTO user_private(user_id,phone_ciphertext,phone_lookup_hash,key_version) VALUES(?,?,?,1)",
                account.id(), privacy.encrypt(account.id(), "login-phone", phone), digest);
        when(sender.configured()).thenReturn(true);
        doAnswer(call -> {code.set(call.getArgument(1)); return null;}).when(sender).send(anyString(), anyString());
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM user_private WHERE user_id=?", account.id());
        fixtures.close(); redis.delete("shiwushop:auth:state:" + account.id());
        redis.delete(List.of(prefix + ":cooldown", prefix + ":sends", prefix + ":password-reset:code",
                prefix + ":entry:code", prefix + ":login:code"));
    }

    /** 每个客户端都从服务器取得自己的 Cookie 和 CSRF，不从别的请求复制验证状态。 */
    String obtainTicket(HttpAccountClient client) throws Exception {
        client.csrf();
        var sent = client.post("/users/password/reset/send", Map.of("phone", phone));
        assertEquals(200, sent.statusCode(), sent.body());
        var verified = client.post("/users/password/reset/verify", Map.of("phone", phone, "code", code.get()));
        assertEquals(200, verified.statusCode(), verified.body());
        assertEquals(300, json.readTree(verified.body()).path("data").path("expiresInSeconds").asInt());
        assertFalse(verified.body().contains(phone)); assertFalse(verified.body().contains(account.username()));
        return json.readTree(verified.body()).path("data").path("resetTicket").asString();
    }

    @Test void smsLoggedUserCanResetWithoutOldPasswordAndAllOldSessionsLoseAccess() throws Exception {
        try (var owner = new HttpAccountClient(base); var second = new HttpAccountClient(base)) {
            owner.csrf();
            assertEquals(200, owner.post("/users/sms/send", Map.of("phone", phone)).statusCode());
            assertEquals(200, owner.post("/users/sms/login", Map.of("phone", phone, "code", code.get())).statusCode());
            second.csrf();
            assertEquals(200, second.post("/users/login", Map.of("username", account.username(), "password", account.password())).statusCode());
            redis.delete(prefix + ":cooldown");
            String ticket = obtainTicket(owner);
            var reset = owner.post("/users/password/reset", Map.of("resetTicket", ticket, "newPassword", "Shop456!"));
            assertEquals(200, reset.statusCode(), reset.body());
            assertEquals(401, owner.get("/users/me").statusCode());
            assertEquals(401, second.get("/users/me").statusCode());
            assertEquals(1, jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, account.id()));
            String hash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE user_id=?", String.class, account.id());
            assertTrue(encoder.matches("Shop456!", hash)); assertFalse(encoder.matches(account.password(), hash));
            owner.csrf();
            assertEquals(401, owner.post("/users/password/reset", Map.of("resetTicket", ticket, "newPassword", "Shop789!")).statusCode());
            assertEquals(401, owner.post("/users/login", Map.of("username", account.username(), "password", account.password())).statusCode());
            assertEquals(200, owner.post("/users/login", Map.of("username", account.username(), "password", "Shop456!")).statusCode());
        }
    }

    @Test void crossSessionAndRegistrationProofsAreRejectedWithoutSpendingOwnersTicket() throws Exception {
        try (var owner = new HttpAccountClient(base); var stranger = new HttpAccountClient(base)) {
            String ticket = obtainTicket(owner); stranger.csrf();
            var body = Map.<String,Object>of("resetTicket", ticket, "newPassword", "Shop456!");
            assertEquals(401, stranger.post("/users/password/reset", body).statusCode());
            assertEquals(401, owner.post("/users/sms/entry/register", Map.of("phone", phone,
                    "registrationTicket", ticket, "password", "Shop456!")).statusCode());
            assertEquals(200, owner.post("/users/password/reset", body).statusCode());
        }
    }

    @Test void firstUseRegistrationTicketCannotResetExistingPassword() throws Exception {
        jdbc.update("DELETE FROM user_private WHERE user_id=?", account.id());
        try (var client = new HttpAccountClient(base)) {
            client.csrf();
            assertEquals(200, client.post("/users/sms/entry/send", Map.of("phone", phone)).statusCode());
            var verified = client.post("/users/sms/entry/verify", Map.of("phone", phone, "code", code.get()));
            assertEquals(200, verified.statusCode(), verified.body());
            String registration = json.readTree(verified.body()).path("data").path("registrationTicket").asString();
            assertEquals(401, client.post("/users/password/reset", Map.of("resetTicket", registration, "newPassword", "Shop456!")).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, account.id()));
        }
    }

    @Test void expiredSmsChallengeCannotIssueTicket() throws Exception {
        try (var client = new HttpAccountClient(base)) {
            client.csrf();
            assertEquals(200, client.post("/users/password/reset/send", Map.of("phone", phone)).statusCode());
            redis.expire(prefix + ":password-reset:code", java.time.Duration.ZERO);
            assertEquals(401, client.post("/users/password/reset/verify", Map.of("phone", phone, "code", code.get())).statusCode());
        }
    }

    @Test void resetLogsContainAuditEventButNoCredentialsOrPersonalPhone() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        try (var client = new HttpAccountClient(base)) {
            String ticket = obtainTicket(client);
            assertEquals(200, client.post("/users/password/reset", Map.of("resetTicket", ticket, "newPassword", "Shop456!")).statusCode());
            String messages = captured.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(messages.contains("action=password_reset"));
            for (String secret : List.of(phone, code.get(), ticket, "Shop456!", "MALLSESSION=")) assertFalse(messages.contains(secret));
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    @Test void loginCodeCannotBeUsedAsResetCodeAndResetCodeCannotLogin() throws Exception {
        try (var client = new HttpAccountClient(base)) {
            client.csrf();
            assertEquals(200, client.post("/users/sms/send", Map.of("phone", phone)).statusCode());
            assertEquals(401, client.post("/users/password/reset/verify", Map.of("phone", phone, "code", code.get())).statusCode());
            assertEquals(200, client.post("/users/sms/login", Map.of("phone", phone, "code", code.get())).statusCode());
            client.csrf(); redis.delete(prefix + ":cooldown");
            assertEquals(200, client.post("/users/password/reset/send", Map.of("phone", phone)).statusCode());
            var resetCode = Map.<String,Object>of("phone", phone, "code", code.get());
            client.post("/users/logout", Map.of()); client.csrf();
            assertEquals(401, client.post("/users/sms/login", resetCode).statusCode());
            assertEquals(200, client.post("/users/password/reset/verify", resetCode).statusCode());
            assertEquals(401, client.post("/users/password/reset/verify", resetCode).statusCode());
        }
    }

    @Test void unboundOrDisabledPhoneReturnsSameSendResponseWithoutSmsOrNewAccount() throws Exception {
        try (var client = new HttpAccountClient(base)) {
            client.csrf(); jdbc.update("DELETE FROM user_private WHERE user_id=?", account.id());
            var unbound = client.post("/users/password/reset/send", Map.of("phone", phone));
            assertEquals(200, unbound.statusCode()); verify(sender, never()).send(anyString(), anyString());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_private WHERE phone_lookup_hash=?", Integer.class, digest));
            assertEquals(401, client.post("/users/password/reset/verify", Map.of("phone", phone, "code", "123456")).statusCode());
            jdbc.update("INSERT INTO user_private(user_id,phone_ciphertext,phone_lookup_hash,key_version) VALUES(?,?,?,1)",
                    account.id(), privacy.encrypt(account.id(), "login-phone", phone), digest);
            jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE user_id=?", account.id());
            redis.delete(prefix + ":cooldown");
            var disabled = client.post("/users/password/reset/send", Map.of("phone", phone));
            assertEquals(unbound.statusCode(), disabled.statusCode()); assertEquals(unbound.body(), disabled.body());
            verify(sender, never()).send(anyString(), anyString());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"phone", "version", "disabled"})
    void bindingStatusAndVersionChangesAfterVerificationPreventReset(String change) throws Exception {
        try (var client = new HttpAccountClient(base)) {
            String ticket = obtainTicket(client);
            switch (change) {
                case "phone" -> jdbc.update("UPDATE user_private SET phone_lookup_hash=NULL WHERE user_id=?", account.id());
                case "version" -> jdbc.update("UPDATE user_accounts SET auth_version=auth_version+1 WHERE user_id=?", account.id());
                case "disabled" -> jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE user_id=?", account.id());
            }
            var reset = client.post("/users/password/reset", Map.of("resetTicket", ticket, "newPassword", "Shop456!"));
            assertEquals(401, reset.statusCode(), reset.body());
            assertTrue(encoder.matches(account.password(), jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE user_id=?", String.class, account.id())));
        }
    }

    @Test void fiveIncorrectCodesInvalidateChallengeAndCooldownReturnsRetryAfter() throws Exception {
        try (var client = new HttpAccountClient(base)) {
            client.csrf();
            assertEquals(200, client.post("/users/password/reset/send", Map.of("phone", phone)).statusCode());
            var limited = client.post("/users/password/reset/send", Map.of("phone", phone));
            assertEquals(429, limited.statusCode()); assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
            String wrong = code.get().equals("000000") ? "000001" : "000000";
            for (int i = 0; i < 5; i++) assertEquals(401, client.post("/users/password/reset/verify", Map.of("phone", phone, "code", wrong)).statusCode());
            assertEquals(401, client.post("/users/password/reset/verify", Map.of("phone", phone, "code", code.get())).statusCode());
        }
    }

    @ParameterizedTest @ValueSource(ints = {7, 8, 72, 73, 1000})
    void passwordLengthBoundaryIsEnforcedOverRealHttp(int length) throws Exception {
        try (var client = new HttpAccountClient(base)) {
            String ticket = obtainTicket(client); String password = "a".repeat(length);
            var response = client.post("/users/password/reset", Map.of("resetTicket", ticket, "newPassword", password));
            boolean valid = length == 8 || length == 72;
            assertEquals(valid ? 200 : 400, response.statusCode(), response.body());
            String hash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE user_id=?", String.class, account.id());
            assertTrue(encoder.matches(valid ? password : account.password(), hash));
        }
    }

    @RepeatedTest(5) void simultaneousRequestsUsingOneTicketCommitOnlyOnePasswordChange() throws Exception {
        try (var owner = new HttpAccountClient(base); var raw = HttpClient.newHttpClient();
             var pool = Executors.newFixedThreadPool(2)) {
            String ticket = obtainTicket(owner);
            var csrfResponse = owner.get("/users/csrf");
            String csrf = json.readTree(csrfResponse.body()).path("data").asString();
            String cookie = Base64.getEncoder().encodeToString(owner.sessionId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var start = new CyclicBarrier(2);
            List<Future<Integer>> results = new ArrayList<>();
            for (String password : List.of("Shop456!", "Shop789!")) {
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return raw.send(HttpRequest.newBuilder(URI.create(base + "/users/password/reset"))
                            .header("Cookie", "MALLSESSION=" + cookie).header("X-CSRF-TOKEN", csrf)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("resetTicket", ticket, "newPassword", password)))).build(),
                            HttpResponse.BodyHandlers.ofString()).statusCode();
                }));
            }
            var statuses = new ArrayList<Integer>(); for (var result : results) statuses.add(result.get(15, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
            assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 401), statuses.toString());
            assertEquals(1, jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, account.id()));
        }
    }

    @Test void realPhoneRebindingCommitsWhileResetWaitsForAccountLock() throws Exception {
        String nextPhone = "138" + phone.substring(3); String nextDigest = lookup.digest(nextPhone);
        String nextPrefix = "shiwushop:sms:{" + nextDigest + "}";
        challenges.reserve(nextDigest, "bind", "rebind-delivery", lookup.codeMac("bind", nextDigest, "654321"));
        challenges.activate(nextDigest, "bind", "rebind-delivery");
        var reachedAccountLock = new CountDownLatch(1);
        Thread coordinator = Thread.currentThread();
        UserAccountMapper realMapper = sql.getMapper(UserAccountMapper.class);
        doAnswer(call -> {
            if (Thread.currentThread() != coordinator) reachedAccountLock.countDown();
            return realMapper.lockById(call.getArgument(0));
        }).when(accounts).lockById(account.id());
        try (var client = new HttpAccountClient(base); var pool = Executors.newSingleThreadExecutor()) {
            String ticket = obtainTicket(client);
            var pending = new AtomicReference<Future<Integer>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE user_id=? FOR UPDATE", Long.class, account.id());
                pending.set(pool.submit(() -> client.post("/users/password/reset", Map.of("resetTicket", ticket,
                        "newPassword", "Shop456!")).statusCode()));
                assertTrue(assertDoesNotThrow(() -> reachedAccountLock.await(5, TimeUnit.SECONDS)));
                phoneBindings.bind(account.id(), account.password(), nextPhone, "654321");
            });
            assertEquals(401, pending.get().get(15, TimeUnit.SECONDS));
            assertEquals(nextDigest, jdbc.queryForObject("SELECT phone_lookup_hash FROM user_private WHERE user_id=?", String.class, account.id()));
            assertTrue(encoder.matches(account.password(), jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE user_id=?", String.class, account.id())));
        } finally { redis.delete(List.of(nextPrefix + ":cooldown", nextPrefix + ":sends", nextPrefix + ":bind:code")); }
    }

    @Test void smsFailureDoesNotLeaveUsableCode() throws Exception {
        try (var client = new HttpAccountClient(base)) {
            client.csrf(); doThrow(new BusinessException(503, "短信渠道暂时不可用")).when(sender).send(anyString(), anyString());
            assertEquals(503, client.post("/users/password/reset/send", Map.of("phone", phone)).statusCode());
            assertFalse(Boolean.TRUE.equals(redis.hasKey(prefix + ":password-reset:code")));
        }
    }

    @Test void redisChallengeFailureReturns503WithoutChangingPassword() throws Exception {
        try (var client = new HttpAccountClient(base)) {
            client.csrf();
            doThrow(new DataAccessResourceFailureException("isolated fault")).when(challenges).consume(eq(digest), eq("password-reset"), anyString());
            assertEquals(503, client.post("/users/password/reset/verify", Map.of("phone", phone, "code", "123456")).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, account.id()));
        }
    }

    @Test void databaseFailureRollsBackPasswordAndAuthCacheBlock() throws Exception {
        try (var client = new HttpAccountClient(base)) {
            String ticket = obtainTicket(client);
            doThrow(new DataAccessResourceFailureException("isolated fault")).when(accounts).changePassword(eq(account.id()), anyInt(), anyString());
            assertEquals(503, client.post("/users/password/reset", Map.of("resetTicket", ticket, "newPassword", "Shop456!")).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, account.id()));
            assertTrue(encoder.matches(account.password(), jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE user_id=?", String.class, account.id())));
            assertNull(redis.opsForValue().get("shiwushop:auth:state:" + account.id()));
            assertEquals(401, client.post("/users/password/reset", Map.of("resetTicket", ticket, "newPassword", "Shop789!")).statusCode());
        }
    }
}
