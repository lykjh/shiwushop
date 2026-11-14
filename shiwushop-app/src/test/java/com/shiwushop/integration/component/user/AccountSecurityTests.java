package com.shiwushop.integration.component.user;

import com.shiwushop.support.LoginTestRequest;

import com.shiwushop.support.TestRegistration;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.user.dto.user.RegisterDTO;
import com.shiwushop.user.service.RegisterService;
import com.shiwushop.user.auth.JwtLogin;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountSecurityTests {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private RegisterService users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CaptchaProofStore proofs;
    @Autowired
    private StringRedisTemplate redis;
    private final List<String> createdAccounts = new ArrayList<>();

    @Test
    void offersCsrfTokenEndpoint() throws Exception {
        mvc.perform(get("/users/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString());
    }

    @Test
    void passwordChangeRevokesSessionAndOldPassword() throws Exception {
        String username = "auth_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        createdAccounts.add(username);
        Cookie session = login(username, "Shop123!");
        Cookie otherSession = login(username, "Shop123!");
        mvc.perform(get("/users/me").cookie(otherSession)).andExpect(status().isOk());
        mvc.perform(post("/users/password").cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong\",\"newPassword\":\"NewShop123!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users/password").cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Shop123!\",\"newPassword\":\"asdfg密123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fields.newPassword[0]").value("密码只能包含字母、数字和特定符号"));
        mvc.perform(get("/users/me").cookie(session)).andExpect(status().isOk());
        mvc.perform(post("/users/password").cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Shop123!\",\"newPassword\":\"NewShop123!\"}"))
                .andExpect(status().isOk());
        assertNotNull(jdbc.queryForObject("SELECT password_changed_at FROM user_accounts WHERE username=?",
                java.time.LocalDateTime.class, username));
        mvc.perform(get("/users/me").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").cookie(otherSession)).andExpect(status().isUnauthorized());
        mvc.perform(LoginTestRequest.login(proofs, username, "Shop123!"))
                .andExpect(status().isUnauthorized());
        assertNotNull(login(username, "NewShop123!"));
    }

    @Test
    void disabledOrVersionChangedAccountLosesAccess() throws Exception {
        String username = "auth_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        createdAccounts.add(username);
        Cookie first = login(username, "Shop123!");
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
        // Fixture SQL bypasses the service's cache invalidation.
        jdbc.update("UPDATE user_accounts SET auth_version=auth_version+1 WHERE username=?", username);
        redis.delete("shiwushop:auth:state:" + userId);
        mvc.perform(get("/users/me").cookie(first)).andExpect(status().isUnauthorized());
        Cookie second = login(username, "Shop123!");
        jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE username=?", username);
        redis.delete("shiwushop:auth:state:" + userId);
        mvc.perform(get("/users/me").cookie(second)).andExpect(status().isUnauthorized());
    }

    private Cookie login(String username, String password) throws Exception {
        Cookie session = mvc.perform(
                        LoginTestRequest.login(proofs, username, password))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(JwtLogin.COOKIE);
        assertNotNull(session);
        return session;
    }

    @AfterEach
    void cleanUp() {
        for (String username : createdAccounts) {
            Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?",
                    Long.class, username);
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
            redis.delete("shiwushop:auth:logins:" + userId);
        }
    }
}
