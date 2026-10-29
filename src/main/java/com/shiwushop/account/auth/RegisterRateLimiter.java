package com.shiwushop.account.auth;

import com.shiwushop.common.auth.RateLimiter;

import com.shiwushop.common.BusinessException;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RegisterRateLimiter {

    private final RateLimiter limiter;
    private final String keyPrefix;
    private final long tokensPerSecond;
    private final long burst;

    public RegisterRateLimiter(RateLimiter limiter,
                               @Value("${shop.security.register.key-prefix}") String keyPrefix,
                               @Value("${shop.security.register.tokens-per-second}") long tokensPerSecond,
                               @Value("${shop.security.register.burst}") long burst) {
        if (tokensPerSecond < 1 || burst < 1) {
            throw new IllegalArgumentException("注册令牌桶参数必须大于零");
        }
        this.limiter = limiter;
        this.keyPrefix = keyPrefix;
        this.tokensPerSecond = tokensPerSecond;
        this.burst = burst;
    }

    public void check(String remoteAddress) {
        long retry = limiter.tokenBucketRetrySeconds(
                keyPrefix + ":bucket:" + limiter.digest(remoteAddress), tokensPerSecond, burst);
        if (retry > 0) {
            throw new BusinessException(429, "注册请求过于频繁，请稍后再试",
                    Map.of("retryAfterSeconds", retry), null);
        }
    }
}
