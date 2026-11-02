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

    /** 在图像生成前同时核对 Session 和出口的突发额度。 */
    public void checkGenerate(HttpServletRequest request) {
        checkBuckets("generate", request, sessionRate, sessionBurst, ipRate, ipBurst);
    }

    /** 验证滑块轨迹前核对请求额度和该 Session 的连续失败次数。 */
    public void checkValid(HttpServletRequest request) {
        checkBuckets("valid", request, validSessionRate, validSessionBurst, validIpRate, validIpBurst);
        String key = verifyFailureKey(request.getSession(true).getId());
        if (limiter.failureCount(key, verifyFailureWindowMs) >= verifyFailureLimit) {
            reject(limiter.failureRetrySeconds(key, verifyFailureWindowMs));
        }
    }

    /** 在滚动窗口中记录一次轨迹验证失败。 */
    public void recordValidFailure(String sessionId) {
        limiter.recordFailure(verifyFailureKey(sessionId), verifyFailureWindowMs, verifyFailureLimit);
    }

    /** 滑块验证成功后清除该 Session 的失败记录。 */
    public void clearValidFailures(String sessionId) {
        limiter.clear(verifyFailureKey(sessionId));
    }

    /** 为生成和校验分别检查会话桶与出口桶，避免两种成本共用额度。 */
    private void checkBuckets(String action, HttpServletRequest request,
            long sessionRate, long sessionBurst, long ipRate, long ipBurst) {
        // 会话额度允许短时重试；出口桶只给生成资源设置宽松总量上界。
        String sessionId = request.getSession(true).getId();
        checkBucket(PREFIX + action + ":session:" + limiter.digest(sessionId), sessionRate, sessionBurst);
        checkBucket(PREFIX + action + ":ip:" + limiter.digest(request.getRemoteAddr()), ipRate, ipBurst);
    }

    /** 扣除一个令牌；额度耗尽时返回可重试时间。 */
    private void checkBucket(String key, long rate, long burst) {
        long retry = limiter.tokenBucketRetrySeconds(key, rate, burst);
        if (retry > 0) reject(retry);
    }

    /** 对 Session ID 求摘要构造滑块校验失败键，Redis 中不存原值。 */
    private String verifyFailureKey(String sessionId) {
        return PREFIX + "valid:failures:rolling:" + limiter.digest(sessionId);
    }

    /** 用 HTTP 429 和重试秒数报告滑块额度耗尽。 */
    private static void reject(long retrySeconds) {
        throw new BusinessException(429, "验证码请求过于频繁", Map.of("retryAfterSeconds", retrySeconds), null);
    }
}
