package com.shiwushop.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;

import java.time.Duration;
import java.util.UUID;

final class SignedCsrfTokenRepository implements CsrfTokenRepository {
    static final String HEADER = "X-CSRF-Token";
    private final BrowserFlow flow;
    private final String cookieName;

    SignedCsrfTokenRepository(BrowserFlow flow) {
        this.flow = flow;
        this.cookieName = flow.cookieName("__Host-MALLCSRF", "MALLCSRF");
    }

    @Override
    public DeferredCsrfToken loadDeferredToken(HttpServletRequest request, HttpServletResponse response) {
        if (bootstrap(request)) {
            flow.getOrCreate(request, response);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        return CsrfTokenRepository.super.loadDeferredToken(request, response);
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        String binding = binding(request);
        if (binding == null)
            return token(UUID.randomUUID().toString());
        // The same browser and login get the same underlying token even on parallel bootstrap requests.
        String expiry = Long.toString(BrowserFlow.expiry(request));
        return token(expiry + "." + flow.sign("csrf", expiry + ":" + binding));
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // Reject invalid writes without silently replacing a token another tab may already be using.
        if (token != null && bootstrap(request))
            flow.write(response, cookieName, token.getToken(),
                    Duration.ofSeconds(BrowserFlow.expiry(request) - flow.now()));
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        String binding = binding(request);
        String value = BrowserFlow.cookie(request, cookieName);
        if (binding == null || value == null || !value.matches("[0-9]{1,12}\\.[A-Za-z0-9_-]{43}"))
            return null;
        int separator = value.indexOf('.');
        String expiry = value.substring(0, separator);
        if (Long.parseLong(expiry) <= flow.now() || !flow.valid("csrf", expiry + ":" + binding, value.substring(separator + 1)))
            return null;
        return token(value);
    }

    private static CsrfToken token(String value) {
        return new DefaultCsrfToken(HEADER, "_csrf", value);
    }

    private static String binding(HttpServletRequest request) {
        String id = BrowserFlow.currentId(request);
        if (id == null)
            return null;
        var login = SessionUser.current(request);
        return id + ":" + (login == null ? "anonymous" : login.sessionRef());
    }

    private static boolean bootstrap(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && (request.getContextPath() + "/users/csrf").equals(request.getRequestURI());
    }
}
