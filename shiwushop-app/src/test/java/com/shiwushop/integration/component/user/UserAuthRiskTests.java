package com.shiwushop.integration.component.user;

import com.shiwushop.support.TestRegistration;

import com.shiwushop.common.captcha.CaptchaProofStore;
import com.shiwushop.user.dto.user.RegisterDTO;
import com.shiwushop.user.service.RegisterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static com.shiwushop.support.MockBrowserFlow.withBrowserFlow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserAuthRiskTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RegisterService users;
    @Autowired
    private CaptchaProofStore proofs;
    private final String registerIp = "risk-test-" + UUID.randomUUID();

    @Test
    void lowRiskLoginNeedsNoProofAndJwtSurvivesIpChange() throws Exception {
        String username = username();
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        String session = UUID.randomUUID().toString();

        var jwt = login(session, username, "Shop123!", null, "198.51.100.10")
                .andExpect(status().isOk()).andReturn().getResponse()
                .getCookie(com.shiwushop.user.auth.JwtLogin.COOKIE);
        mockMvc.perform(get("/users/me").cookie(jwt)
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
            login(UUID.randomUUID().toString(), username, "Shop123!", null, "198.51.100.30")
                    .andExpect(status().isOk());
        }
    }

    @Test
    void accountFailuresAcrossBrowsersRequireBoundOneTimeProof() throws Exception {
        String username = username();
        TestRegistration.register(users, proofs, new RegisterDTO(username, "Shop123!", null));
        for (int i = 0; i < 5; i++) {
            login(UUID.randomUUID().toString(), username, "wrong", null, "198.51.100.20")
                    .andExpect(status().isUnauthorized());
        }

        String session = UUID.randomUUID().toString();
        login(session, username, "Shop123!", null, "198.51.100.20")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));

        String otherSessionProof = proofs.issue("risk-" + UUID.randomUUID(), UUID.randomUUID().toString());
        login(session, username, "Shop123!", otherSessionProof, "198.51.100.20")
                .andExpect(status().isBadRequest());

        String proof = proofs.issue("risk-" + UUID.randomUUID(), session);
        login(session, username, "Shop123!", proof, "198.51.100.20")
                .andExpect(status().isOk());
        login(session, username, "Shop123!", proof, "198.51.100.20")
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyRegistrationInOneSessionRequiresFreshProof() throws Exception {
        String session = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            register(session, username(), proofFor(session))
                    .andExpect(status().isOk());
        }
        String sixth = username();
        register(session, sixth, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));

        String proof = proofs.issue("risk-" + UUID.randomUUID(), session);
        register(session, sixth, proof)
                .andExpect(status().isOk());
    }

    @Test
    void repeatedRegistrationAcrossSessionsDoesNotBlockAnotherDeviceWithProof() throws Exception {
        String fingerprint = "same-browser-device-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            String session = UUID.randomUUID().toString();
            register(session, username(), proofFor(session), fingerprint)
                    .andExpect(status().isOk());
        }

        String nextSession = UUID.randomUUID().toString();
        String nextUsername = username();
        register(nextSession, nextUsername, null, fingerprint)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));

        String otherSession = UUID.randomUUID().toString();
        register(otherSession, username(), proofFor(otherSession), "other-browser-device-" + UUID.randomUUID())
                .andExpect(status().isOk());

        String proof = proofs.issue("risk-" + UUID.randomUUID(), nextSession);
        register(nextSession, nextUsername, proof, fingerprint)
                .andExpect(status().isOk());
    }

    @Test
    void invalidProofsAcrossSessionsDoNotBlockAnotherDeviceWithProof() throws Exception {
        String fingerprint = "invalid-proof-device-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            register(UUID.randomUUID().toString(), username(), "invalid", fingerprint)
                    .andExpect(status().isBadRequest());
        }
        register(UUID.randomUUID().toString(), username(), null, fingerprint)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.state").value("CAPTCHA_REQUIRED"));
        String otherSession = UUID.randomUUID().toString();
        register(otherSession, username(), proofFor(otherSession), "unrelated-device-" + UUID.randomUUID())
                .andExpect(status().isOk());
    }

    private ResultActions login(String session, String username, String password,
                                String proof, String ip) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\""
                + (proof == null ? "" : ",\"captchaProof\":\"" + proof + "\"") + "}";
        return mockMvc.perform(post("/users/login").with(withBrowserFlow(session))
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header("User-Agent", "risk-integration-test")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions register(String session, String username, String proof) throws Exception {
        return register(session, username, proof, "session-device-" + session);
    }

    private ResultActions register(String session, String username, String proof,
                                   String fingerprint) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"Shop123!\""
                + (proof == null ? "" : ",\"captchaProof\":\"" + proof + "\"") + "}";
        return mockMvc.perform(post("/users/register").with(withBrowserFlow(session))
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

    private String proofFor(String session) {
        return proofs.issue("risk-" + UUID.randomUUID(), session);
    }
}
