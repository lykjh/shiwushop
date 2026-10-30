package com.shiwushop.account.auth;

import com.shiwushop.common.auth.RateLimiter;

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

    /** 从配置注入各项挑战阈值，并校验跨 Session 跟踪容量至少为一条。 */
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

    /** 将规范化用户名摘要作为失败历史键，换 Session 或出口也保留同账号信号。 */
    private String accountKey(String username) {
        return PREFIX + "account:fail:rolling:5m:" + rateLimiter.digest(username.toLowerCase(Locale.ROOT));
    }

    /** 根据当前 Session ID 生成摘要键；原始会话标识不写入 Redis 键名。 */
    private String sessionKey(HttpServletRequest request) {
        // Session 失败历史跟随 Session，客户端换 IP 不会重置这条信号。
        return PREFIX + "session:" + rateLimiter.digest(request.getSession(true).getId());
    }

    /** 将请求出口与归一化浏览器类别合并为共享流量信号，不保存完整 IP 或 UA。 */
    private String ipUaKey(HttpServletRequest request){
        String ua=request.getHeader("User-Agent");
        if(ua==null)ua="";
        String family = browserFamily(ua);
        String source=request.getRemoteAddr()+"\0"+family;
        return PREFIX + "ip-ua:" + rateLimiter.digest(source);
    }

    /** 把易变的 UA 字符串归并为有限的浏览器类别，限制风险键的数量。 */
    private static String browserFamily(String ua) {
        String normalized = ua.toLowerCase(Locale.ROOT);
        if (normalized.contains("edg/") || normalized.contains("edgios/")) return "edge";
        if (normalized.contains("opr/") || normalized.contains("opera/")) return "opera";
        if (normalized.contains("chrome/") || normalized.contains("crios/")) return "chrome";
        if (normalized.contains("firefox/") || normalized.contains("fxios/")) return "firefox";
        if (normalized.contains("safari/")) return "safari";
        return "other";
    }

    /** 校验可选设备信号格式并生成摘要键；该客户端信号只辅助挑战判断。 */
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

    /**
     * 记录一次密码认证失败，同时更新账号、Session 和共享出口的滚动失败历史。
     * 同一风险事件交给底层限流器原子写入，并记录登录失败指标。
     */
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

    /**
     * 记录一次注册风险失败；有设备信号时额外累计设备维度的失败历史。
     * 用户名冲突等正常业务错误不应调用此方法。
     */
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

    /**
     * 记录共享出口和可选设备的注册成功次数，供重复注册风险判断使用。
     * 账号已经提交后，统计服务故障只记错误指标，不把成功响应改为失败。
     */
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

    /** 清除该账号及当前 Session 的登录失败历史，并累计登录成功指标。 */
    public void recordLoginSuccess(String username, HttpServletRequest request) {
        rateLimiter.clearAuthRisk(List.of(accountKey(username),
                sessionKey(request) + ":login:fail:rolling:5m",
                sessionKey(request) + ":login:fail:rolling:1m"));
        metrics.counter("shiwushop.auth.login.success").increment();
    }

    /** 读取登录风险快照，按账号、会话与共享失败信号决定是否要求滑块或短时拒绝。 */
    public boolean loginRequiresCaptcha(String username, HttpServletRequest request) {
        RateLimiter.AuthRiskSnapshot risk = snapshot("login", accountKey(username), request);
        return requiresCaptcha("login", risk);
    }

    /**
     * 读取注册风险快照，先判断失败与 Session 尝试，再结合可选设备信号判断重复注册。
     * 共享出口信号只有与当前设备的重复行为同时出现才提高挑战要求。
     */
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

    /**
     * 一次性读取并更新某类操作的风险快照，使用占位键维持底层脚本固定的键位布局。
     * 将快照的时间桶留在 request 中，供随后记录失败时写入对应时间段。
     */
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

    /** 构造登录或注册的一分钟入口流量键。 */
    private String volumeKey(String action, HttpServletRequest request) {
        return ipUaKey(request) + ":" + action + ":volume:1m";
    }

    /** 取出本次风险快照的时间桶；未读取快照时返回 -1 交由底层处理。 */
    private long requestBucket(String action, HttpServletRequest request) {
        Object bucket = request.getAttribute(PREFIX + action + ":bucket");
        return bucket instanceof Long value ? value : -1;
    }

    /**
     * 应用登录与注册共用的失败规则：会话高频失败返回 429，其余风险要求滑块。
     * 共享出口信号必须叠加账号或当前 Session 风险，避免误伤同出口的其他用户。
     */
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

    /** 记录触发规则的固定名称指标，并把“需要滑块”返回给调用方。 */
    private boolean challenge(String rule) {
        metrics.counter("shiwushop.auth.risk.triggered", "rule", rule).increment();
        return true;
    }
}
