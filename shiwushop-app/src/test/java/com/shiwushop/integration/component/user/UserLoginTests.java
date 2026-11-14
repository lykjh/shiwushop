package com.shiwushop.integration.component.user;

import com.shiwushop.support.LoginTestRequest;

import com.shiwushop.support.TestRegistration;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.user.dto.user.RegisterDTO;
import com.shiwushop.user.service.RegisterService;
import com.shiwushop.user.auth.JwtLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static com.shiwushop.support.MockBrowserFlow.withBrowserFlow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserLoginTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RegisterService userService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CaptchaProofStore proofs;

    @Test
    void activeAccountReceivesJwtWithoutCreatingSession() throws Exception {
        String username = newUsername();
        TestRegistration.register(userService, proofs, new RegisterDTO(username, "Shop123!", null));

        var result = mockMvc.perform(LoginTestRequest.login(proofs, username, "Shop123!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();
        assertNull(result.getRequest().getSession(false));
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
        var jwt = result.getResponse().getCookie(JwtLogin.COOKIE);
        assertNotNull(jwt);
        mockMvc.perform(get("/users/me").cookie(jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId));
    }

    @Test
    void existingAccountWithUnicodePasswordCanStillLogIn() throws Exception {
        String username = newUsername();
        TestRegistration.register(userService, proofs, new RegisterDTO(username, "Shop123!", null));
        jdbcTemplate.update("UPDATE user_accounts SET password_hash = ? WHERE username = ?",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("asdfg密123456"), username);

        mockMvc.perform(LoginTestRequest.login(proofs, username, "asdfg密123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    void invalidCredentialsAndDisabledAccountCannotLogIn() throws Exception {
        String username = newUsername();
        TestRegistration.register(userService, proofs, new RegisterDTO(username, "Shop123!", null));
        for (String[] credentials : new String[][]{
                {username, "wrong123"}, {"missing_" + username, "Shop123!"},
                {username, "X".repeat(73)}}) {
            mockMvc.perform(LoginTestRequest.login(proofs, credentials[0], credentials[1]))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("账号或密码错误"));
        }

        jdbcTemplate.update("UPDATE user_accounts SET status = 'DISABLED' WHERE username = ?", username);
        mockMvc.perform(LoginTestRequest.login(proofs, username, "Shop123!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    @Test
    void lowRiskLoginNeedsNoProofButSubmittedProofMustBeValidAndOneTime() throws Exception {
        String username = newUsername();
        TestRegistration.register(userService, proofs, new RegisterDTO(username, "Shop123!", null));
        mockMvc.perform(post("/users/login").with(withBrowserFlow(UUID.randomUUID().toString())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Shop123!\"}"))
                .andExpect(status().isOk());

        String owner = UUID.randomUUID().toString();
        String ownerProof = proofs.issue("test-" + UUID.randomUUID(), owner);
        String body = "{\"username\":\"" + username + "\",\"password\":\"Shop123!\",\"captchaProof\":\"";
        mockMvc.perform(post("/users/login").with(withBrowserFlow(UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON).content(body + ownerProof + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/users/login").with(withBrowserFlow(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body + ownerProof + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/users/login").with(withBrowserFlow(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body + ownerProof + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private String newUsername() {
        return "buyer_" + UUID.randomUUID().toString().replace("-", "");
    }
}
