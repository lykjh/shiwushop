package com.shiwushop.user;

import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Supplies a test-only session-bound proof; image matching is outside these login tests. */
public final class LoginTestRequest {
    private LoginTestRequest() {}

    public static MockHttpServletRequestBuilder login(CaptchaProofStore proofs, String username, String password) {
        MockHttpSession session = new MockHttpSession();
        String proof = proofs.issue("test-" + UUID.randomUUID(), session.getId());
        return post("/users/login").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                        + "\",\"captchaProof\":\"" + proof + "\"}");
    }
}
