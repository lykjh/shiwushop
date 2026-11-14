package com.shiwushop.unit.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.auth.SessionUser.Identity;
import com.shiwushop.user.auth.AccountAuthCache;
import com.shiwushop.user.auth.JwtLogin;
import com.shiwushop.user.auth.LoginSessions;
import com.shiwushop.user.entity.status.AccountStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtLoginTest {
    private final LoginSessions sessions = mock(LoginSessions.class);
    private final AccountAuthCache accounts = mock(AccountAuthCache.class);
    private final Instant now = Instant.parse("2026-11-12T00:00:00Z");
    private final String secret = Base64.getEncoder().encodeToString(new byte[32]);
    private final JwtLogin jwt = create(now);

    @Test
    void signedCookieRequiresAnActiveRecordAndStopsWorkingAfterLogout() {
        when(accounts.current(42L)).thenReturn(new AccountAuthCache.State(AccountStatus.ACTIVE, 3));
        var response = new MockHttpServletResponse();
        jwt.start(42L, 3, new MockHttpServletRequest(), response);
        var id = ArgumentCaptor.forClass(Identity.class);
        verify(sessions).create(id.capture(), eq(now.plus(Duration.ofHours(8))));

        Cookie cookie = response.getCookie(JwtLogin.COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("/api", cookie.getPath());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var authHeaders = response.getHeaders("Set-Cookie");
        assertEquals(2, authHeaders.size());
        assertTrue(authHeaders.stream().anyMatch(header -> header.startsWith("MALLAUTH=" + cookie.getValue() + ";")
                && header.contains("Path=/assistant;") && header.contains("HttpOnly")));

        var request = new MockHttpServletRequest();
        request.setCookies(cookie);
        when(sessions.touch(id.getValue())).thenReturn(true, false);
        assertEquals(id.getValue(), jwt.authenticate(request));
        SessionUser.identify(request, id.getValue());
        jwt.revokeCurrent(request);
        verify(sessions).logout(id.getValue());
        assertNull(jwt.authenticate(request));

        var logout = new MockHttpServletResponse();
        jwt.clearCookie(logout);
        var expired = logout.getHeaders("Set-Cookie");
        assertEquals(2, expired.size());
        for (String path : new String[]{"/api", "/assistant"}) {
            assertTrue(expired.stream().anyMatch(header -> header.startsWith("MALLAUTH=;")
                    && header.contains("Path=" + path + ";") && header.contains("Max-Age=0")));
        }
    }

    @Test
    void rejectsTamperedExpiredAndOldVersionTokens() {
        when(accounts.current(42L)).thenReturn(new AccountAuthCache.State(AccountStatus.ACTIVE, 3));
        var response = new MockHttpServletResponse();
        jwt.start(42L, 3, new MockHttpServletRequest(), response);
        Cookie cookie = response.getCookie(JwtLogin.COOKIE);
        var request = new MockHttpServletRequest();
        String token = cookie.getValue();
        int signatureStart = token.lastIndexOf('.') + 1;
        String changed = token.substring(0, signatureStart)
                + (token.charAt(signatureStart) == 'A' ? 'B' : 'A') + token.substring(signatureStart + 1);
        request.setCookies(new Cookie(JwtLogin.COOKIE, changed));
        assertNull(jwt.authenticate(request));

        request.setCookies(cookie);
        assertNull(create(now.plus(Duration.ofHours(8))).authenticate(request));
        when(accounts.current(42L)).thenReturn(new AccountAuthCache.State(AccountStatus.ACTIVE, 4));
        assertNull(jwt.authenticate(request));
        verify(sessions, never()).touch(any());
    }

    @Test
    void neverSetsCookieWhenStorageFailsOrCredentialsBelongToAnOldVersion() {
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest();
        when(accounts.current(42L)).thenReturn(new AccountAuthCache.State(AccountStatus.ACTIVE, 4));
        assertEquals(401, assertThrows(BusinessException.class, () -> jwt.start(42L, 3, request, response)).getCode());
        verifyNoInteractions(sessions);
        doThrow(new BusinessException(503, "Redis unavailable")).when(sessions).create(any(), any());
        assertEquals(503, assertThrows(BusinessException.class, () -> jwt.start(42L, 4, request, response)).getCode());
        assertNull(response.getCookie(JwtLogin.COOKIE));
    }

    private JwtLogin create(Instant at) {
        return new JwtLogin(sessions, accounts, Clock.fixed(at, ZoneOffset.UTC), secret,
                Duration.ofHours(8), "/api", true);
    }
}
