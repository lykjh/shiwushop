package com.shiwushop.support;

import com.shiwushop.common.captcha.CaptchaProofStore;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Supplies a browser-bound proof; these login tests do not exercise image matching. */
public final class LoginTestRequest {
    private LoginTestRequest() {
    }

    public static MockHttpServletRequestBuilder login(CaptchaProofStore proofs, String username, String password) {
        String browser = UUID.randomUUID().toString();
        String proof = proofs.issue("test-" + UUID.randomUUID(), browser);
        return post("/users/login").with(MockBrowserFlow.withBrowserFlow(browser)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                        + "\",\"captchaProof\":\"" + proof + "\"}");
    }
}
