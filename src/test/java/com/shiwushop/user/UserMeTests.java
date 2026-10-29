package com.shiwushop.user;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.service.UserService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserMeTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private UserAccountMapper userAccountMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CaptchaProofStore proofs;
    @Autowired private StringRedisTemplate redis;

    @Test
    void loginRestoresCurrentUserAndLogoutEndsAccess() throws Exception {
        String username = "buyer_" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, proofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET role = 'MERCHANT' WHERE username = ?", username);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);

        MockHttpSession session = (MockHttpSession) mockMvc.perform(
                        LoginTestRequest.login(proofs, username, "Shop123!"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);

        mockMvc.perform(get("/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.role").value("MERCHANT"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        userAccountMapper.update(new LambdaUpdateWrapper<UserAccount>()
                .eq(UserAccount::getUserId, userId)
                .set(UserAccount::getStatus, "DISABLED"));
        // Fixture SQL bypasses the service's cache invalidation.
        redis.delete("shiwushop:auth:state:" + userId);
        mockMvc.perform(get("/users/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("请重新登录"));

        mockMvc.perform(post("/users/logout").session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingOrStaleSessionReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());

        MockHttpSession stale = new MockHttpSession();
        stale.setAttribute("userId", Long.MAX_VALUE);
        mockMvc.perform(get("/users/me").session(stale))
                .andExpect(status().isUnauthorized());

        MockHttpSession staleForCsrf = new MockHttpSession();
        staleForCsrf.setAttribute("userId", Long.MAX_VALUE);
        mockMvc.perform(get("/users/csrf").session(staleForCsrf))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}
