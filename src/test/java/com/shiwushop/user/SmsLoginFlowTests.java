package com.shiwushop.user;

import com.shiwushop.account.auth.PhoneLookup;
import com.shiwushop.account.entity.status.AccountRole;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.entity.user.UserProfile;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.mapper.user.UserProfileMapper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "shop.sms.lookup-key-base64=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "shop.privacy.key-v1-base64=QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
})
@AutoConfigureMockMvc
class SmsLoginFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private UserProfileMapper profiles;
    @Autowired private UserAccountMapper accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PhoneLookup lookup;
    @Autowired private StringRedisTemplate redis;
    @MockitoBean private SmsSender sender;

    private final Map<String, String> sentCodes = new ConcurrentHashMap<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<String> phones = new ArrayList<>();
    private final String remoteAddress = "sms-test-" + UUID.randomUUID();

    @BeforeEach
    void captureSmsWithoutCallingProvider() {
        when(sender.configured()).thenReturn(true);
        doAnswer(call -> {
            sentCodes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(sender).send(anyString(), anyString());
    }

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM user_private WHERE user_id=?", userId);
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
            redis.delete("shiwushop:auth:state:" + userId);
        }
        for (String phone : phones) {
            String digest = lookup.digest(phone);
            String prefix = "shiwushop:sms:{" + digest + "}";
            redis.delete(List.of(prefix + ":sends", prefix + ":cooldown"));
            for (String purpose : List.of("bind", "login")) {
                redis.delete(prefix + ":" + purpose + ":code");
            }
        }
    }

    @Test
    void bindThenLoginCreatesExistingSessionAndRejectsReplay() throws Exception {
        Long userId = createUser();
        String phone = nextPhone();
        MockHttpSession existing = (MockHttpSession) mvc.perform(post("/users/login").with(csrf())
                        .remoteAddress(remoteAddress).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username(userId) + "\",\"password\":\"Shop123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);

        mvc.perform(post("/users/phone/bind/send").with(csrf()).session(existing)
                        .remoteAddress(remoteAddress).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        String bindingCode = sentCodes.get(phone);
        mvc.perform(post("/users/sms/login").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, bindingCode)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/users/phone/bind/confirm").with(csrf()).session(existing)
                        .remoteAddress(remoteAddress).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Shop123!\",\"phone\":\"" + phone
                                + "\",\"code\":\"" + bindingCode + "\"}"))
                .andExpect(status().isOk());
        assertThat(existing.isInvalid()).isTrue();
        assertThat(jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, userId))
                .isEqualTo(1);

        redis.delete("shiwushop:sms:{" + lookup.digest(phone) + "}:cooldown");
        mvc.perform(post("/users/sms/send").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        String loginCode = sentCodes.get(phone);
        var result = mvc.perform(post("/users/sms/login").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, loginCode)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(userId)).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getAttribute("userId")).isEqualTo(userId);
        assertThat(session.getAttribute("authVersion")).isEqualTo(1);
        mvc.perform(get("/users/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/users/sms/login").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, loginCode)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownNumberGetsGenericSendResultWithoutSmsOrSession() throws Exception {
        String phone = nextPhone();
        mvc.perform(post("/users/sms/send").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(sender, never()).send(eq(phone), anyString());
        mvc.perform(post("/users/sms/login").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, "123456")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uniqueBindingRejectsSecondAccountAndWrongCodesExhaustChallenge() throws Exception {
        Long first = createUser();
        Long second = createUser();
        String phone = nextPhone();
        bind(first, phone);
        redis.delete("shiwushop:sms:{" + lookup.digest(phone) + "}:cooldown");
        mvc.perform(post("/users/phone/bind/send").with(csrf()).session(session(second))
                        .remoteAddress(remoteAddress).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/users/phone/bind/confirm").with(csrf()).session(session(second))
                        .remoteAddress(remoteAddress).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Shop123!\",\"phone\":\"" + phone
                                + "\",\"code\":\"" + sentCodes.get(phone) + "\"}"))
                .andExpect(status().isConflict());

        redis.delete("shiwushop:sms:{" + lookup.digest(phone) + "}:cooldown");
        mvc.perform(post("/users/sms/send").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        String correct = sentCodes.get(phone);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/users/sms/login").with(csrf()).remoteAddress(remoteAddress)
                            .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, "000000".equals(correct) ? "000001" : "000000")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/users/sms/login").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(codeBody(phone, correct)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void providerFailureLeavesNoUsableCodeAndDisabledAccountGetsNoSms() throws Exception {
        Long userId = createUser();
        String phone = nextPhone();
        doAnswer(call -> {
            sentCodes.put(call.getArgument(0), call.getArgument(1));
            throw new BusinessException(503, "短信渠道暂时不可用");
        }).when(sender).send(anyString(), anyString());
        mvc.perform(post("/users/phone/bind/send").with(csrf()).session(session(userId))
                        .remoteAddress(remoteAddress).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isServiceUnavailable());
        String digest = lookup.digest(phone);
        assertThat(redis.hasKey("shiwushop:sms:{" + digest + "}:bind:code")).isFalse();

        redis.delete("shiwushop:sms:{" + digest + "}:cooldown");
        doAnswer(call -> {
            sentCodes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(sender).send(anyString(), anyString());
        bind(userId, phone);
        sentCodes.clear();
        clearInvocations(sender);
        jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE user_id=?", userId);
        redis.delete("shiwushop:sms:{" + digest + "}:cooldown");
        mvc.perform(post("/users/sms/send").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        verify(sender, never()).send(eq(phone), anyString());
        assertThat(sentCodes).isEmpty();
    }

    @Test
    void invalidPhoneNeverCallsSmsProvider() throws Exception {
        mvc.perform(post("/users/sms/send").with(csrf()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        verify(sender, never()).send(anyString(), anyString());
    }

    private void bind(Long userId, String phone) throws Exception {
        mvc.perform(post("/users/phone/bind/send").with(csrf()).session(session(userId))
                        .remoteAddress(remoteAddress).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/users/phone/bind/confirm").with(csrf()).session(session(userId))
                        .remoteAddress(remoteAddress).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Shop123!\",\"phone\":\"" + phone
                                + "\",\"code\":\"" + sentCodes.get(phone) + "\"}"))
                .andExpect(status().isOk());
    }

    private Long createUser() {
        UserProfile profile = new UserProfile();
        profiles.insertEmpty(profile);
        UserAccount account = new UserAccount();
        account.setUserId(profile.getId());
        account.setUsername("sms_" + UUID.randomUUID().toString().replace("-", ""));
        account.setPasswordHash(new BCryptPasswordEncoder().encode("Shop123!"));
        account.setRole(AccountRole.USER);
        account.setStatus(AccountStatus.ACTIVE);
        accounts.insert(account);
        userIds.add(profile.getId());
        return profile.getId();
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        session.setAttribute("authVersion", jdbc.queryForObject(
                "SELECT auth_version FROM user_accounts WHERE user_id=?", Integer.class, userId));
        return session;
    }

    private String nextPhone() {
        String phone = "139" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        phones.add(phone);
        return phone;
    }

    private String username(Long userId) {
        return jdbc.queryForObject("SELECT username FROM user_accounts WHERE user_id=?", String.class, userId);
    }

    private String codeBody(String phone, String code) {
        return "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}";
    }
}
