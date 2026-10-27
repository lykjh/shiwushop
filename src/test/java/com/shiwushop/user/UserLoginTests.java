package com.shiwushop.user;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.common.auth.CaptchaProofStore;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserLoginTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CaptchaProofStore proofs;

    @Test
    void activeAccountLogsInWithNewSchemaAndCreatesSession() throws Exception {
        String username = newUsername();
        TestRegistration.register(userService, proofs, new RegisterDTO(username, "Shop123!", null));

        var result = mockMvc.perform(LoginTestRequest.login(proofs, username, "Shop123!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username);
        assertEquals(userId, session.getAttribute("userId"));
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
        mockMvc.perform(post("/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Shop123!\"}"))
                .andExpect(status().isOk());

        MockHttpSession session = new MockHttpSession();
        String wrongSessionProof = proofs.issue("test-" + UUID.randomUUID(), session.getId());
        String body = "{\"username\":\"" + username + "\",\"password\":\"Shop123!\",\"captchaProof\":\"";
        mockMvc.perform(post("/users/login").session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON).content(body + wrongSessionProof + "\"}"))
                .andExpect(status().isBadRequest());

        String validProof = proofs.issue("test-" + UUID.randomUUID(), session.getId());
        mockMvc.perform(post("/users/login").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body + validProof + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/users/login").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body + validProof + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private String newUsername() {
        return "buyer_" + UUID.randomUUID().toString().replace("-", "");
    }

}
