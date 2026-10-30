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

    /** 读取注册入口的令牌补充速率和突发容量，并拒绝无效配置。 */
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

    /**
     * 对单个出口的注册请求量使用令牌桶保护；额度耗尽时返回 429 和重试秒数。
     * 此处控制入口资源消耗，不把出口 IP 当作独立注册者身份。
     */
    public void check(String remoteAddress) {
        long retry = limiter.tokenBucketRetrySeconds(
                keyPrefix + ":bucket:" + limiter.digest(remoteAddress), tokensPerSecond, burst);
        if (retry > 0) {
            throw new BusinessException(429, "注册请求过于频繁，请稍后再试",
                    Map.of("retryAfterSeconds", retry), null);
        }
    }
}
