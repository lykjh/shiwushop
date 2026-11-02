package com.shiwushop.unit.auth;

import com.shiwushop.common.auth.SessionDependencyFailureFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class SessionDependencyFailureFilterTest {
    final SessionDependencyFailureFilter filter = new SessionDependencyFailureFilter(new ObjectMapper());

    @Test void connectionFailureBeforeControllerReturns503WithoutDispatchingAgain() throws Exception {
        var response = new MockHttpServletResponse(); response.setHeader("X-Request-Id", "request-123");
        filter.doFilter(new MockHttpServletRequest(), response, (request, reply) -> {
            throw new RedisConnectionFailureException("private-host-secret");
        });
        assertEquals(503, response.getStatus());
        assertEquals(503, new ObjectMapper().readTree(response.getContentAsString()).path("code").asInt());
        assertEquals("request-123", response.getHeader("X-Request-Id"));
        assertFalse(response.getContentAsString().contains("private-host-secret"));
    }
    @Test void wrappedSaveFailureReplacesBufferedSuccessAndPreservesSecurityHeaders() throws Exception {
        var response = new MockHttpServletResponse(); response.setHeader("X-Frame-Options", "DENY");
        filter.doFilter(new MockHttpServletRequest(), response, (request, reply) -> {
            reply.setContentLength(2); reply.getOutputStream().write("OK".getBytes());
            throw new ServletException(new QueryTimeoutException("Redis unavailable"));
        });
        assertEquals(503, response.getStatus()); assertNull(response.getHeader("Content-Length"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertFalse(response.getContentAsString().contains("OK"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
    @Test void unrelatedFailureIsNotRelabelledAsRedisOutage() {
        var error = new IllegalStateException("business bug");
        assertSame(error, assertThrows(IllegalStateException.class, () -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), (request, reply) -> {throw error;})));
    }
    @Test void alreadyCommittedResponsePropagatesFailureInsteadOfWritingAnotherBody() throws Exception {
        var response = new MockHttpServletResponse(); response.setCommitted(true);
        var error = new RedisConnectionFailureException("offline");
        assertSame(error, assertThrows(RedisConnectionFailureException.class, () -> filter.doFilter(
                new MockHttpServletRequest(), response, (request, reply) -> {throw error;})));
        assertEquals("", response.getContentAsString());
    }
}
