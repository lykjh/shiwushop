package com.shiwushop.user;

import com.shiwushop.common.captcha.CaptchaProofStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserRegisterTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CaptchaProofStore proofs;
    @Autowired private MeterRegistry metrics;
    private final String remoteAddress = "test-" + UUID.randomUUID();

    @Test
    void registrationWritesProfileAndActiveAccount() throws Exception {
        String username = "buyer_" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(register(username, "Shop123!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Map<String, Object> account = jdbcTemplate.queryForMap("""
                SELECT a.user_id, a.password_hash, a.role, a.status, u.nickname
                FROM user_accounts a JOIN users u ON u.id = a.user_id
                WHERE a.username = ?
                """, username);
        assertTrue(new BCryptPasswordEncoder().matches("Shop123!", (String) account.get("password_hash")));
        assertTrue("USER".equals(account.get("role")) && "ACTIVE".equals(account.get("status")));
    }

    @Test
    void duplicateUsernameReturnsConflict() throws Exception {
        String username = "buyer_" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(register(username, "Shop123!"))
                .andExpect(status().isOk());
        double failuresBefore = metrics.counter("shiwushop.auth.register.failure").count();
        mockMvc.perform(register(username, "Shop123!"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("账号已存在"));
        assertEquals(failuresBefore, metrics.counter("shiwushop.auth.register.failure").count());
    }

    @Test
    void sixIndependentBuyersCanRegisterBehindOneIp() throws Exception {
        for (int i = 0; i < 6; i++) {
            String username = "buyer_" + UUID.randomUUID().toString().replace("-", "");
            mockMvc.perform(register(username, "Shop123!"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void invalidUsernameAndOversizedPasswordAreRejected() throws Exception {
        double failuresBefore = metrics.counter("shiwushop.auth.register.failure").count();
        mockMvc.perform(register("含空格 account", "Shop123!"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(register("valid_user", "密".repeat(25)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码不得超过72字节"));
        assertEquals(failuresBefore, metrics.counter("shiwushop.auth.register.failure").count());
    }

    @Test
    void missingProofRequiresCaptcha() throws Exception {
        String username = "buyer_" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/users/register").remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(username, "Shop123!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));
    }

    @Test
    void registrationProofCannotBeReusedOrUsedByAnotherSession() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        String proof = proofs.issue("register-" + UUID.randomUUID(), owner.getId());
        String first = "buyer_" + UUID.randomUUID().toString().replace("-", "");
        String second = "buyer_" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/users/register").session(new MockHttpSession()).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(body(first, "Shop123!", proof)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/users/register").session(owner).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(body(first, "Shop123!", proof)))
                .andExpect(status().isBadRequest());

        String freshProof = proofs.issue("register-" + UUID.randomUUID(), owner.getId());
        mockMvc.perform(post("/users/register").session(owner).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(body(first, "Shop123!", freshProof)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/users/register").session(owner).remoteAddress(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON).content(body(second, "Shop123!", freshProof)))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder register(String username, String password) {
        MockHttpSession session = new MockHttpSession();
        String proof = proofs.issue("register-" + UUID.randomUUID(), session.getId());
        return post("/users/register").session(session).remoteAddress(remoteAddress)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(username, password, proof));
    }

    private String body(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private String body(String username, String password, String proof) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"captchaProof\":\"" + proof + "\"}";
    }
}
