package com.shiwushop.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaRateLimiter;
import com.shiwushop.common.auth.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptchaRateLimiterTests {
    @Test
    void freshSessionsShareTheWideExitBucket() {
        RateLimiter counters = mock(RateLimiter.class);
        when(counters.digest(anyString())).thenAnswer(call -> call.getArgument(0));
        AtomicInteger exitRequests = new AtomicInteger();
        when(counters.tokenBucketRetrySeconds(anyString(), eq(30L), eq(100L)))
                .thenAnswer(call -> exitRequests.incrementAndGet() > 2 ? 1L : 0L);
        CaptchaRateLimiter limiter = new CaptchaRateLimiter(counters, 1, 5, 30, 100,
                2, 10, 60, 120, 10, 300_000);

        HttpServletRequest first = request();
        HttpServletRequest second = request();
        HttpServletRequest third = request();
        limiter.checkGenerate(first);
        limiter.checkGenerate(second);
        BusinessException error = assertThrows(BusinessException.class, () -> limiter.checkGenerate(third));

        assertEquals(429, error.getCode());
        assertEquals(3, exitRequests.get());
    }

    @Test
    void repeatedVerificationFailuresBlockOnlyThatSession() {
        RateLimiter counters = mock(RateLimiter.class);
        when(counters.digest(anyString())).thenAnswer(call -> call.getArgument(0));
        CaptchaRateLimiter limiter = new CaptchaRateLimiter(counters, 1, 5, 30, 100,
                2, 10, 60, 120, 10, 300_000);
        MockHttpServletRequest blocked = request();
        String key = "shiwushop:captcha:rate:valid:failures:rolling:" + blocked.getSession().getId();
        when(counters.failureCount(key, 300_000)).thenReturn(10L);
        when(counters.failureRetrySeconds(key, 300_000)).thenReturn(60L);

        BusinessException error = assertThrows(BusinessException.class, () -> limiter.checkValid(blocked));
        assertEquals(429, error.getCode());
        limiter.checkValid(request());
        limiter.recordValidFailure(blocked.getSession().getId());
        verify(counters).recordFailure(key, 300_000, 10);
        verify(counters).tokenBucketRetrySeconds(
                "shiwushop:captcha:rate:valid:session:" + blocked.getSession().getId(), 2, 10);
        verify(counters, times(2)).tokenBucketRetrySeconds(
                "shiwushop:captcha:rate:valid:ip:203.0.113.10", 60, 120);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.getSession(true);
        return request;
    }
}
