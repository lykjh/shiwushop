package com.shiwushop.user;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.service.RegisterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserAuthRiskTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private RegisterService users;
    @Autowired private CaptchaProofStore proofs;
    private final String registerIp = "risk-test-" + UUID.randomUUID();

    @Test
    void lowRiskLoginNeedsNoProofAndSessionSurvivesIpChange() throws Exception {
        String username = username();
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        MockHttpSession session = new MockHttpSession();

        login(session, username, "Shop123!", null, "198.51.100.10")
                .andExpect(status().isOk());
        mockMvc.perform(get("/users/me").session(session)
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.11");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    void elevenSuccessfulLoginsDoNotConsumeAccountFailureAllowance() throws Exception {
        String username = username();
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        for (int i = 0; i < 11; i++) {
            login(new MockHttpSession(), username, "Shop123!", null, "198.51.100.30")
                    .andExpect(status().isOk());
        }
    }

    @Test
    void accountFailuresAcrossSessionsRequireBoundOneTimeProof() throws Exception {
        String username = username();
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        for (int i = 0; i < 5; i++) {
            login(new MockHttpSession(), username, "wrong", null, "198.51.100.20")
                    .andExpect(status().isUnauthorized());
        }

        MockHttpSession session = new MockHttpSession();
        login(session, username, "Shop123!", null, "198.51.100.20")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));

        String otherSessionProof = proofs.issue("risk-" + UUID.randomUUID(), new MockHttpSession().getId());
        login(session, username, "Shop123!", otherSessionProof, "198.51.100.20")
                .andExpect(status().isBadRequest());

        String proof = proofs.issue("risk-" + UUID.randomUUID(), session.getId());
        login(session, username, "Shop123!", proof, "198.51.100.20")
                .andExpect(status().isOk());
        login(session, username, "Shop123!", proof, "198.51.100.20")
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyRegistrationInOneSessionRequiresFreshProof() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 5; i++) {
            register(session, username(), proofFor(session))
                    .andExpect(status().isOk());
        }
        String sixth = username();
        register(session, sixth, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));

        String proof = proofs.issue("risk-" + UUID.randomUUID(), session.getId());
        register(session, sixth, proof)
                .andExpect(status().isOk());
    }

    @Test
    void repeatedRegistrationAcrossSessionsDoesNotBlockAnotherDeviceWithProof() throws Exception {
        String fingerprint = "same-browser-device-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            MockHttpSession session = new MockHttpSession();
            register(session, username(), proofFor(session), fingerprint)
                    .andExpect(status().isOk());
        }

        MockHttpSession nextSession = new MockHttpSession();
        String nextUsername = username();
        register(nextSession, nextUsername, null, fingerprint)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));

        MockHttpSession otherSession = new MockHttpSession();
        register(otherSession, username(), proofFor(otherSession), "other-browser-device-" + UUID.randomUUID())
                .andExpect(status().isOk());

        String proof = proofs.issue("risk-" + UUID.randomUUID(), nextSession.getId());
        register(nextSession, nextUsername, proof, fingerprint)
                .andExpect(status().isOk());
    }

    @Test
    void invalidProofsAcrossSessionsDoNotBlockAnotherDeviceWithProof() throws Exception {
        String fingerprint = "invalid-proof-device-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            register(new MockHttpSession(), username(), "invalid", fingerprint)
                    .andExpect(status().isBadRequest());
        }
        register(new MockHttpSession(), username(), null, fingerprint)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));
        MockHttpSession otherSession = new MockHttpSession();
        register(otherSession, username(), proofFor(otherSession), "unrelated-device-" + UUID.randomUUID())
                .andExpect(status().isOk());
    }

    private ResultActions login(MockHttpSession session, String username, String password,
                                String proof, String ip) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\""
                + (proof == null ? "" : ",\"captchaProof\":\"" + proof + "\"") + "}";
        return mockMvc.perform(post("/users/login").session(session)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header("User-Agent", "risk-integration-test")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions register(MockHttpSession session, String username, String proof) throws Exception {
        return register(session, username, proof, "session-device-" + session.getId());
    }

    private ResultActions register(MockHttpSession session, String username, String proof,
                                   String fingerprint) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"Shop123!\""
                + (proof == null ? "" : ",\"captchaProof\":\"" + proof + "\"") + "}";
        return mockMvc.perform(post("/users/register").session(session)
                .with(request -> {
                    request.setRemoteAddr(registerIp);
                    return request;
                })
                .header("User-Agent", "risk-registration-test")
                .header("X-Device-Fingerprint", fingerprint)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String username() {
        return "risk_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String proofFor(MockHttpSession session) {
        return proofs.issue("risk-" + UUID.randomUUID(), session.getId());
    }
}
