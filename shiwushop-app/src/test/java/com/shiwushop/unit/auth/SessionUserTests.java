package com.shiwushop.unit.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.SessionUser;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionUserTests {
    @Test
    void ignoresLegacySessionIdentityAndReadsVerifiedRequestIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertNull(SessionUser.currentId(request));
        assertNull(request.getSession(false));

        request.getSession().setAttribute("userId", 7L);
        assertNull(SessionUser.currentId(request));
        SessionUser.identify(request, new SessionUser.Identity(7L, 3, "verified"));
        assertEquals(7L, SessionUser.currentId(request));
        assertEquals(3, SessionUser.require(request).authVersion());
    }

    @Test
    void requiresLoginWhenSessionIdIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(401, assertThrows(BusinessException.class,
                () -> SessionUser.requireId(request)).getCode());
        SessionUser.identify(request, new SessionUser.Identity(7L, 3, "verified"));
        assertEquals(7L, SessionUser.requireId(request));
    }

    @Test
    void requiresNonNullUserIdFromServices() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> SessionUser.requireId((Long) null));
        assertEquals(401, error.getCode());
        assertEquals("请先登录", error.getMessage());
        assertEquals(7L, SessionUser.requireId(7L));
    }
}
