package com.shiwushop.support;

import com.shiwushop.common.auth.BrowserFlow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

/** Direct service and MVC fixtures supply the browser ID normally verified by the servlet filter. */
public final class MockBrowserFlow {
    private MockBrowserFlow() {}

    public static void bind(HttpServletRequest request) {
        bind(request, UUID.randomUUID().toString());
    }

    public static void bind(HttpServletRequest request, String id) {
        request.setAttribute(BrowserFlow.class.getName() + ".id", id);
    }

    public static RequestPostProcessor withBrowserFlow(String id) {
        return request -> {
            bind(request, id);
            return request;
        };
    }
}
