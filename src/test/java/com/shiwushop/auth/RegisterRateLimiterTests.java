package com.shiwushop.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.common.auth.RateLimiter;
import com.shiwushop.account.auth.RegisterRateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegisterRateLimiterTests {
    @Test
    void exhaustedBucketReturnsRetryAfter() {
        RateLimiter tokens = mock(RateLimiter.class);
        when(tokens.digest(anyString())).thenReturn("source-digest");
        when(tokens.tokenBucketRetrySeconds("shiwushop:rate:register:bucket:source-digest", 20, 100))
                .thenReturn(1L);

        RegisterRateLimiter registration = new RegisterRateLimiter(tokens, "shiwushop:rate:register", 20, 100);
        BusinessException error = assertThrows(BusinessException.class,
                () -> registration.check("203.0.113.10"));
        assertEquals(429, error.getCode());
        assertEquals("1", new GlobalExceptionHandler().handle(error).getHeaders().getFirst("Retry-After"));
    }
}
