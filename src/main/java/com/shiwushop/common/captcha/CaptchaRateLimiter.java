package com.shiwushop.common.captcha;

import com.shiwushop.common.auth.RateLimiter;

import com.shiwushop.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CaptchaRateLimiter {
    private static final String PREFIX = "shiwushop:captcha:rate:";
    private final RateLimiter limiter;
    private final long sessionRate;
    private final long sessionBurst;
    private final long ipRate;
    private final long ipBurst;
    private final long validSessionRate;
    private final long validSessionBurst;
    private final long validIpRate;
    private final long validIpBurst;
    private final long verifyFailureLimit;
    private final long verifyFailureWindowMs;

    public CaptchaRateLimiter(RateLimiter limiter,
            @Value("${shop.security.captcha.session-tokens-per-second}") long sessionRate,
            @Value("${shop.security.captcha.session-burst}") long sessionBurst,
            @Value("${shop.security.captcha.ip-tokens-per-second}") long ipRate,
            @Value("${shop.security.captcha.ip-burst}") long ipBurst,
            @Value("${shop.security.captcha.valid-session-tokens-per-second}") long validSessionRate,
            @Value("${shop.security.captcha.valid-session-burst}") long validSessionBurst,
            @Value("${shop.security.captcha.valid-ip-tokens-per-second}") long validIpRate,
            @Value("${shop.security.captcha.valid-ip-burst}") long validIpBurst,
            @Value("${shop.security.captcha.verify-failure-limit}") long verifyFailureLimit,
            @Value("${shop.security.captcha.verify-failure-window-ms}") long verifyFailureWindowMs) {
        if (sessionRate < 1 || sessionBurst < 1 || ipRate < 1 || ipBurst < 1
                || validSessionRate < 1 || validSessionBurst < 1 || validIpRate < 1 || validIpBurst < 1
                || verifyFailureLimit < 1 || verifyFailureWindowMs < 1) {
            throw new IllegalArgumentException("滑块额度参数必须大于零");
        }
        this.limiter = limiter;
        this.sessionRate = sessionRate;
        this.sessionBurst = sessionBurst;
        this.ipRate = ipRate;
        this.ipBurst = ipBurst;
        this.validSessionRate = validSessionRate;
        this.validSessionBurst = validSessionBurst;
        this.validIpRate = validIpRate;
        this.validIpBurst = validIpBurst;
        this.verifyFailureLimit = verifyFailureLimit;
        this.verifyFailureWindowMs = verifyFailureWindowMs;
    }

    public void checkGenerate(HttpServletRequest request) {
        checkBuckets("generate", request, sessionRate, sessionBurst, ipRate, ipBurst);
    }

    public void checkValid(HttpServletRequest request) {
        checkBuckets("valid", request, validSessionRate, validSessionBurst, validIpRate, validIpBurst);
        String key = verifyFailureKey(request.getSession(true).getId());
        if (limiter.failureCount(key, verifyFailureWindowMs) >= verifyFailureLimit) {
            reject(limiter.failureRetrySeconds(key, verifyFailureWindowMs));
        }
    }

    public void recordValidFailure(String sessionId) {
        limiter.recordFailure(verifyFailureKey(sessionId), verifyFailureWindowMs, verifyFailureLimit);
    }

    public void clearValidFailures(String sessionId) {
        limiter.clear(verifyFailureKey(sessionId));
    }

    private void checkBuckets(String action, HttpServletRequest request,
            long sessionRate, long sessionBurst, long ipRate, long ipBurst) {
        // 会话额度允许短时重试；出口桶只给生成资源设置宽松总量上界。
        String sessionId = request.getSession(true).getId();
        checkBucket(PREFIX + action + ":session:" + limiter.digest(sessionId), sessionRate, sessionBurst);
        checkBucket(PREFIX + action + ":ip:" + limiter.digest(request.getRemoteAddr()), ipRate, ipBurst);
    }

    private void checkBucket(String key, long rate, long burst) {
        long retry = limiter.tokenBucketRetrySeconds(key, rate, burst);
        if (retry > 0) reject(retry);
    }

    private String verifyFailureKey(String sessionId) {
        return PREFIX + "valid:failures:rolling:" + limiter.digest(sessionId);
    }

    private static void reject(long retrySeconds) {
        throw new BusinessException(429, "验证码请求过于频繁", Map.of("retryAfterSeconds", retrySeconds), null);
    }
}
