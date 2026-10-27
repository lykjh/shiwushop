package com.shiwushop.user;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.common.auth.CaptchaProofStore;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountSecurityTests {
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CaptchaProofStore proofs;
    @Autowired private StringRedisTemplate redis;
    private final List<String> createdAccounts = new ArrayList<>();

    @Test
    void csrfRejectsInvalidTokenAndOffersTokenEndpoint() throws Exception {
        mvc.perform(get("/users/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString());
        mvc.perform(post("/users/logout").with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void passwordChangeRevokesSessionAndOldPassword() throws Exception {
        String username = "auth_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        createdAccounts.add(username);
        MockHttpSession session = login(username, "Shop123!");
        MockHttpSession otherSession = login(username, "Shop123!");
        mvc.perform(get("/users/me").session(otherSession)).andExpect(status().isOk());
        mvc.perform(post("/users/password").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong\",\"newPassword\":\"NewShop123!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/users/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/users/password").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Shop123!\",\"newPassword\":\"NewShop123!\"}"))
                .andExpect(status().isOk());
        assertNotNull(jdbc.queryForObject("SELECT password_changed_at FROM user_accounts WHERE username=?",
                java.time.LocalDateTime.class, username));
        mvc.perform(get("/users/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").session(otherSession)).andExpect(status().isUnauthorized());
        mvc.perform(LoginTestRequest.login(proofs, username, "Shop123!"))
                .andExpect(status().isUnauthorized());
        assertNotNull(login(username, "NewShop123!"));
    }

    @Test
    void disabledOrVersionChangedAccountLosesAccess() throws Exception {
        String username = "auth_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        createdAccounts.add(username);
        MockHttpSession first = login(username, "Shop123!");
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username=?", Long.class, username);
        // Fixture SQL bypasses the service's cache invalidation.
        jdbc.update("UPDATE user_accounts SET auth_version=auth_version+1 WHERE username=?", username);
        redis.delete("shiwushop:auth:state:" + userId);
        mvc.perform(get("/users/me").session(first)).andExpect(status().isUnauthorized());
        MockHttpSession second = login(username, "Shop123!");
        jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE username=?", username);
        redis.delete("shiwushop:auth:state:" + userId);
        mvc.perform(get("/users/me").session(second)).andExpect(status().isUnauthorized());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(
                        LoginTestRequest.login(proofs, username, password))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
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
        }
    }

}
