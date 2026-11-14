package com.shiwushop.support;

import com.shiwushop.common.auth.SessionUser;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Supplies a verified request identity to business-layer MVC tests. */
public final class MockLogin {
    private MockLogin() {}

    public static RequestPostProcessor loginAs(Long userId) {
        return request -> {
            SessionUser.identify(request, new SessionUser.Identity(userId, 0, "mock-login"));
            return request;
        };
    }

    public static RequestPostProcessor withMockLogin(MockHttpSession fixture) {
        return request -> {
            if (fixture.getAttribute("userId") instanceof Long userId)
                return loginAs(userId).postProcessRequest(request);
            return request;
        };
    }
}
