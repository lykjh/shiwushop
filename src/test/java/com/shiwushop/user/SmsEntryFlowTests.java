package com.shiwushop.user;

import com.shiwushop.account.auth.PhoneLookup;
import com.shiwushop.account.auth.PhoneRegistrationTicketStore;
import com.shiwushop.account.mapper.user.PhoneBindingMapper;
import com.shiwushop.account.service.SmsSender;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "shop.sms.lookup-key-base64=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "shop.privacy.key-v1-base64=QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=",
        "spring.datasource.hikari.maximum-pool-size=2"
})
@AutoConfigureMockMvc
class SmsEntryFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PhoneLookup lookup;
    @Autowired private PhoneBindingMapper bindings;
    @Autowired private PhoneRegistrationTicketStore tickets;
    @MockitoBean private SmsSender sender;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> sentCodes = new ConcurrentHashMap<>();
    private final List<String> phones = new ArrayList<>();

    @BeforeEach
    void interceptSms() {
        when(sender.configured()).thenReturn(true);
        doAnswer(call -> {
            sentCodes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(sender).send(anyString(), anyString());
    }

    @AfterEach
    void cleanUp() {
        for (String phone : phones) {
            String digest = lookup.digest(phone);
            Long userId = bindings.findUserId(digest);
            if (userId != null) {
                jdbc.update("DELETE FROM user_private WHERE user_id=?", userId);
                jdbc.update("DELETE FROM user_accounts WHERE user_id=?", userId);
                jdbc.update("DELETE FROM users WHERE id=?", userId);
                redis.delete("shiwushop:auth:state:" + userId);
            }
            String prefix = "shiwushop:sms:{" + digest + "}";
            redis.delete(List.of(prefix + ":sends", prefix + ":cooldown", prefix + ":entry:code"));
        }
        redis.delete("shiwushop:sms:entry:global");
    }

    @Test
    void firstUseRequiresPasswordThenCreatesAccountBindingAndLoginSession() throws Exception {
        String phone = nextPhone();
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/users/sms/entry/send").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(phoneBody(phone)))
                .andExpect(status().isOk());
        String code = sentCodes.get(phone);
        assertThat(code).matches("[0-9]{6}");
        var verify = mvc.perform(post("/users/sms/entry/verify").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SET_PASSWORD_REQUIRED"))
                .andReturn();
        String ticket = json.readTree(verify.getResponse().getContentAsString())
                .path("data").path("registrationTicket").asString();
        assertThat(ticket).hasSize(43);
        assertThat(session.getAttribute("userId")).isNull();
        String originalSessionId = session.getId();
        mvc.perform(post("/users/sms/entry/verify").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, code)))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/users/sms/entry/register").session(new MockHttpSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody(phone, ticket, "Shop123!")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/users/sms/entry/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("13800000000", ticket, "Shop123!")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/users/sms/entry/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody(phone, ticket, "short")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users/sms/entry/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(phone, ticket, "中".repeat(30))))
                .andExpect(status().isBadRequest());
        var registered = mvc.perform(post("/users/sms/entry/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody(phone, ticket, "Shop123!")))
                .andExpect(status().isOk()).andReturn();
        long userId = json.readTree(registered.getResponse().getContentAsString()).path("data").path("id").asLong();
        String username = json.readTree(registered.getResponse().getContentAsString())
                .path("data").path("username").asString();
        assertThat(username).matches("sw[a-z0-9]{20}");
        assertThat(bindings.findUserId(lookup.digest(phone))).isEqualTo(userId);
        assertThat(new BCryptPasswordEncoder().matches("Shop123!", jdbc.queryForObject(
                "SELECT password_hash FROM user_accounts WHERE user_id=?", String.class, userId))).isTrue();
        assertThat(session.getAttribute("userId")).isEqualTo(userId);
        mvc.perform(get("/users/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phoneMasked")
                        .value(phone.substring(0, 3) + "****" + phone.substring(7)));
        assertThatThrownBy(() -> tickets.consume(ticket, lookup.digest(phone), originalSessionId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void boundNumberLogsInAndDisabledAccountCannotBeRecreated() throws Exception {
        String phone = nextPhone();
        MockHttpSession first = new MockHttpSession();
        String ticket = verifyUnbound(phone, first);
        mvc.perform(post("/users/sms/entry/register").session(first).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody(phone, ticket, "Shop123!")))
                .andExpect(status().isOk());
        Long userId = bindings.findUserId(lookup.digest(phone));
        clearCooldown(phone);
        MockHttpSession second = new MockHttpSession();
        send(phone, second);
        mvc.perform(post("/users/sms/entry/verify").session(second).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, sentCodes.get(phone))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("LOGGED_IN"))
                .andExpect(jsonPath("$.data.id").value(userId));
        assertThat(second.getAttribute("userId")).isEqualTo(userId);

        jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE user_id=?", userId);
        redis.delete("shiwushop:auth:state:" + userId);
        clearCooldown(phone);
        MockHttpSession third = new MockHttpSession();
        send(phone, third);
        mvc.perform(post("/users/sms/entry/verify").session(third).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, sentCodes.get(phone))))
                .andExpect(status().isUnauthorized());
        assertThat(bindings.findUserId(lookup.digest(phone))).isEqualTo(userId);
    }

    @Test
    void onlyOneConcurrentConsumerCanUseRegistrationTicket() throws Exception {
        String ticket = tickets.issue("phone-digest", "session-id");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    tickets.consume(ticket, "phone-digest", "session-id");
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            })).toList();
            ready.await();
            go.countDown();
            assertThat(attempts.get(0).get() != attempts.get(1).get()).isTrue();
        }
    }

    @Test
    void unavailableSmsProviderReturns503WithoutIssuingCode() throws Exception {
        String phone = nextPhone();
        when(sender.configured()).thenReturn(false);
        mvc.perform(post("/users/sms/entry/send").session(new MockHttpSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(phoneBody(phone)))
                .andExpect(status().isServiceUnavailable());
        assertThat(sentCodes).doesNotContainKey(phone);
        assertThat(redis.hasKey("shiwushop:sms:{" + lookup.digest(phone) + "}:entry:code")).isFalse();
    }

    @Test
    void exhaustedGlobalBudgetCancelsPhoneReservationBeforeSending() throws Exception {
        String phone = nextPhone();
        redis.opsForHash().putAll("shiwushop:sms:entry:global", Map.of(
                "tokens", "0", "updated", Long.toString(System.currentTimeMillis() + 60_000)));
        mvc.perform(post("/users/sms/entry/send").session(new MockHttpSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(phoneBody(phone)))
                .andExpect(status().isTooManyRequests());
        String prefix = "shiwushop:sms:{" + lookup.digest(phone) + "}";
        assertThat(redis.hasKey(prefix + ":cooldown")).isFalse();
        assertThat(redis.hasKey(prefix + ":entry:code")).isFalse();
        assertThat(redis.opsForZSet().size(prefix + ":sends")).isZero();
        assertThat(sentCodes).doesNotContainKey(phone);
    }

    @Test
    void twoVerifiedTicketsForOnePhoneCreateOnlyOneAccount() throws Exception {
        String phone = nextPhone();
        MockHttpSession first = new MockHttpSession();
        String firstTicket = verifyUnbound(phone, first);
        clearCooldown(phone);
        MockHttpSession second = new MockHttpSession();
        String secondTicket = verifyUnbound(phone, second);
        int usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        mvc.perform(post("/users/sms/entry/register").session(first).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(phone, firstTicket, "Shop123!")))
                .andExpect(status().isOk());
        mvc.perform(post("/users/sms/entry/register").session(second).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(phone, secondTicket, "Shop123!")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_private WHERE phone_lookup_hash=?",
                Integer.class, lookup.digest(phone))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(usersBefore + 1);
    }

    private String verifyUnbound(String phone, MockHttpSession session) throws Exception {
        send(phone, session);
        var result = mvc.perform(post("/users/sms/entry/verify").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, sentCodes.get(phone))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SET_PASSWORD_REQUIRED"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .path("data").path("registrationTicket").asString();
    }

    private void send(String phone, MockHttpSession session) throws Exception {
        mvc.perform(post("/users/sms/entry/send").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(phoneBody(phone)))
                .andExpect(status().isOk());
    }

    private void clearCooldown(String phone) {
        redis.delete("shiwushop:sms:{" + lookup.digest(phone) + "}:cooldown");
    }

    private String nextPhone() {
        String phone = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        phones.add(phone);
        return phone;
    }

    private String phoneBody(String phone) { return "{\"phone\":\"" + phone + "\"}"; }
    private String codeBody(String phone, String code) {
        return "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}";
    }
    private String registerBody(String phone, String ticket, String password) {
        return "{\"phone\":\"" + phone + "\",\"registrationTicket\":\"" + ticket
                + "\",\"password\":\"" + password + "\"}";
    }
}
