package com.shiwushop.common.auth;

import com.shiwushop.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Component
public class AuthRiskLimiter {
    private static final long WINDOW_MS = 300_000;
    private static final String PREFIX = "shiwushop:auth-risk:{auth-risk}:";
    private static final String UNUSED_ZSET = PREFIX + "unused:zset";
    private static final String UNUSED_STRING = PREFIX + "unused:string";

    private final RateLimiter rateLimiter;
    private final MeterRegistry metrics;
    private final long accountFailures;
    private final long sessionFailures;
    private final long sessionBlockFailures;
    private final long sessionRegisterAttempts;
    private final long maxTrackedSessions;
    private final long deviceRegisterSuccesses;
    private final long ipUaRegisterSuccesses;
    private final long ipUaRegisterRequestsPerMinute;
    private final long ipUaFailures;
    private final long ipUaRequestsPerMinute;
    private final double ipUaFailureRatio;

    public AuthRiskLimiter(
            RateLimiter rateLimiter,
            MeterRegistry metrics,
            @Value("${shop.security.auth-risk.account-failures}") long accountFailures,
            @Value("${shop.security.auth-risk.session-failures}") long sessionFailures,
            @Value("${shop.security.auth-risk.session-block-failures}") long sessionBlockFailures,
            @Value("${shop.security.auth-risk.session-register-attempts}") long sessionRegisterAttempts,
            @Value("${shop.security.auth-risk.max-tracked-sessions-per-exit}") long maxTrackedSessions,
            @Value("${shop.security.auth-risk.device-register-successes}") long deviceRegisterSuccesses,
            @Value("${shop.security.auth-risk.ip-ua-register-successes}") long ipUaRegisterSuccesses,
            @Value("${shop.security.auth-risk.ip-ua-register-requests-per-minute}") long ipUaRegisterRequestsPerMinute,
            @Value("${shop.security.auth-risk.ip-ua-failures}") long ipUaFailures,
            @Value("${shop.security.auth-risk.ip-ua-requests-per-minute}") long ipUaRequestsPerMinute,
            @Value("${shop.security.auth-risk.ip-ua-failure-ratio}") double ipUaFailureRatio) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.accountFailures = accountFailures;
        this.sessionFailures = sessionFailures;
        this.sessionBlockFailures = sessionBlockFailures;
        this.sessionRegisterAttempts = sessionRegisterAttempts;
        if (maxTrackedSessions < 1) throw new IllegalArgumentException("Session 风控记录上限必须大于零");
        this.maxTrackedSessions = maxTrackedSessions;
        this.deviceRegisterSuccesses = deviceRegisterSuccesses;
        this.ipUaRegisterSuccesses = ipUaRegisterSuccesses;
        this.ipUaRegisterRequestsPerMinute = ipUaRegisterRequestsPerMinute;
        this.ipUaFailures = ipUaFailures;
        this.ipUaRequestsPerMinute = ipUaRequestsPerMinute;
        this.ipUaFailureRatio = ipUaFailureRatio;
    }

    private String accountKey(String username) {
        return PREFIX + "account:fail:rolling:5m:" + rateLimiter.digest(username.toLowerCase(Locale.ROOT));
    }

    private String sessionKey(HttpServletRequest request) {
        // Session 失败历史跟随 Session，客户端换 IP 不会重置这条信号。
        return PREFIX + "session:" + rateLimiter.digest(request.getSession(true).getId());
    }

    private String ipUaKey(HttpServletRequest request){
        String ua=request.getHeader("User-Agent");
        if(ua==null)ua="";
        String family = browserFamily(ua);
        String source=request.getRemoteAddr()+"\0"+family;
        return PREFIX + "ip-ua:" + rateLimiter.digest(source);
    }

    private static String browserFamily(String ua) {
        String normalized = ua.toLowerCase(Locale.ROOT);
        if (normalized.contains("edg/") || normalized.contains("edgios/")) return "edge";
        if (normalized.contains("opr/") || normalized.contains("opera/")) return "opera";
        if (normalized.contains("chrome/") || normalized.contains("crios/")) return "chrome";
        if (normalized.contains("firefox/") || normalized.contains("fxios/")) return "firefox";
        if (normalized.contains("safari/")) return "safari";
        return "other";
    }

    private String deviceKey(HttpServletRequest request) {
        String fingerprint = request.getHeader("X-Device-Fingerprint");
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        if (!fingerprint.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new BusinessException(400, "设备信号格式错误");
        }
        // 客户端信号可伪造，仅用作挑战线索；Redis 键不保存原值。
        return PREFIX + "device:" + rateLimiter.digest(fingerprint);
    }

    public void recordLoginFailure(String username, HttpServletRequest request){
        String session = sessionKey(request) + ":login:fail:rolling";
        String shared = ipUaKey(request) + ":login:fail:rolling";
        rateLimiter.recordAuthFailure(List.of(
                volumeKey("login", request), accountKey(username),
                session + ":5m", session + ":1m", shared + ":5m"),
                requestBucket("login", request),
                new long[]{WINDOW_MS, WINDOW_MS, 60_000, WINDOW_MS},
                new long[]{accountFailures, sessionFailures, sessionBlockFailures, ipUaFailures});
        metrics.counter("shiwushop.auth.login.failure").increment();
    }

    public void recordRegisterFailure(HttpServletRequest request){
        String session = sessionKey(request) + ":register:fail:rolling";
        String shared = ipUaKey(request) + ":register:fail:rolling";
        List<String> keys = new ArrayList<>(List.of(volumeKey("register", request),
                session + ":5m", session + ":1m", shared + ":5m"));
        List<Long> limits = new ArrayList<>(List.of(sessionFailures, sessionBlockFailures, ipUaFailures));
        List<Long> windows = new ArrayList<>(List.of(WINDOW_MS, 60_000L, WINDOW_MS));
        String device = deviceKey(request);
        if (device != null) {
            keys.add(device + ":register:fail:rolling:5m");
            windows.add(WINDOW_MS);
            limits.add(sessionFailures);
        }
        rateLimiter.recordAuthFailure(keys, requestBucket("register", request),
                windows.stream().mapToLong(Long::longValue).toArray(),
                limits.stream().mapToLong(Long::longValue).toArray());
        metrics.counter("shiwushop.auth.register.failure").increment();
    }

    public void recordRegisterSuccess(HttpServletRequest request) {
        try {
            List<String> keys = new ArrayList<>(List.of(ipUaKey(request) + ":register:success:5m"));
            String device = deviceKey(request);
            if (device != null) {
                keys.add(device + ":register:success:5m");
            }
            rateLimiter.recordAuthSuccess(keys, WINDOW_MS);
        } catch (BusinessException e) {
            // 账号已提交成功；事后统计故障不把成功注册改报为失败。
            if (e.getCode() != 503) {
                throw e;
            }
            metrics.counter("shiwushop.auth.register.outcome_record_error").increment();
        }
        metrics.counter("shiwushop.auth.register.success").increment();
    }

    public void recordLoginSuccess(String username, HttpServletRequest request) {
        rateLimiter.clearAuthRisk(List.of(accountKey(username),
                sessionKey(request) + ":login:fail:rolling:5m",
                sessionKey(request) + ":login:fail:rolling:1m"));
        metrics.counter("shiwushop.auth.login.success").increment();
    }

    public boolean loginRequiresCaptcha(String username, HttpServletRequest request) {
        RateLimiter.AuthRiskSnapshot risk = snapshot("login", accountKey(username), request);
        return requiresCaptcha("login", risk);
    }

    public boolean registerRequiresCaptcha(HttpServletRequest request) {
        RateLimiter.AuthRiskSnapshot risk = snapshot("register", null, request);
        if (requiresCaptcha("register", risk)) {
            return true;
        }
        if (risk.sessionAttempts() > sessionRegisterAttempts) {
            return challenge("register_attempts");
        }
        String device = deviceKey(request);
        if (device == null) {
            // shortcut: 尚无前端设备信号时只保留 Session 风控；前端接入后验证跨 Session 识别。
            metrics.counter("shiwushop.auth.register.device_signal_missing").increment();
            return false;
        }
        if (risk.deviceSuccesses() >= deviceRegisterSuccesses
                || risk.deviceFailures() >= sessionFailures) {
            return challenge("device_register_repeated");
        }
        // 共享出口的高流量只有结合当前设备的重复注册才提高挑战要求。
        if (risk.requests() > ipUaRegisterRequestsPerMinute
                && risk.sharedSuccesses() >= ipUaRegisterSuccesses
                && risk.deviceSuccesses() >= 2) {
            return challenge("device_register_shared_success_burst");
        }
        return risk.requests() > ipUaRequestsPerMinute
                && risk.failures() >= ipUaFailures
                && (double) risk.failures() / risk.requests() >= ipUaFailureRatio
                && risk.deviceFailures() >= 2
                && challenge("device_register_shared_failure_burst");
    }

    private RateLimiter.AuthRiskSnapshot snapshot(String action, String account, HttpServletRequest request) {
        String session = sessionKey(request);
        String shared = ipUaKey(request);
        String device = "register".equals(action) ? deviceKey(request) : null;
        RateLimiter.AuthRiskSnapshot risk = rateLimiter.checkAuthRisk(List.of(
                volumeKey(action, request),
                session + ":" + action + ":fail:rolling:5m",
                session + ":" + action + ":fail:rolling:1m",
                shared + ":" + action + ":fail:rolling:5m",
                account == null ? UNUSED_ZSET : account,
                device == null ? UNUSED_ZSET : device + ":register:fail:rolling:5m",
                device == null ? UNUSED_STRING : device + ":register:success:5m",
                shared + ":register:success:5m",
                shared + ":register:session-attempts",
                shared + ":register:session-attempt-index"),
                "register".equals(action), WINDOW_MS, maxTrackedSessions,
                rateLimiter.digest(request.getSession(true).getId()));
        request.setAttribute(PREFIX + action + ":bucket", risk.bucket());
        return risk;
    }

    private String volumeKey(String action, HttpServletRequest request) {
        return ipUaKey(request) + ":" + action + ":volume:1m";
    }

    private long requestBucket(String action, HttpServletRequest request) {
        Object bucket = request.getAttribute(PREFIX + action + ":bucket");
        return bucket instanceof Long value ? value : -1;
    }

    private boolean requiresCaptcha(String action, RateLimiter.AuthRiskSnapshot risk) {
        if (risk.sessionBlockFailures() >= sessionBlockFailures) {
            // 规则名是固定值，指标标签不会包含账号、IP、UA 或 Cookie。
            metrics.counter("shiwushop.auth.risk.triggered", "rule", "session_block").increment();
            throw new BusinessException(429, "尝试过于频繁，请稍后再试",
                    Map.of("retryAfterSeconds", risk.sessionRetrySeconds()), null);
        }
        if ("login".equals(action) && risk.accountFailures() >= accountFailures) {
            return challenge("account_failures");
        }
        if (risk.sessionFailures() >= sessionFailures) {
            return challenge("session_failures");
        }
        // 共享出口只提高已有账号或 Session 风险，避免连带挑战其他用户。
        boolean localRisk = risk.accountFailures() >= 2 || risk.sessionFailures() >= 2;
        if ("login".equals(action)
                && localRisk && risk.sharedFailures() >= ipUaFailures) {
            return challenge("ip_ua_failures");
        }
        return "login".equals(action) && localRisk
                && risk.requests() > ipUaRequestsPerMinute
                && (double) risk.failures() / risk.requests() >= ipUaFailureRatio
                && challenge("ip_ua_failure_ratio");
    }

    private boolean challenge(String rule) {
        metrics.counter("shiwushop.auth.risk.triggered", "rule", rule).increment();
        return true;
    }
}
