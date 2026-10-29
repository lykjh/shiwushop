package com.shiwushop.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.GlobalExceptionHandler;
import com.shiwushop.account.auth.AuthRiskLimiter;
import com.shiwushop.common.auth.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthRiskLimiterTests {
    private final Map<String, Long> counts = new HashMap<>();
    private final Map<String, long[]> volumes = new HashMap<>();
    private final RateLimiter limiter = mock(RateLimiter.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private AuthRiskLimiter risk;

    @BeforeEach
    void setUp() {
        // 用内存计数验证规则，不依赖运行中的 Redis。
        when(limiter.digest(anyString())).thenAnswer(call -> Integer.toHexString(call.<String>getArgument(0).hashCode()));
        when(limiter.increment(anyString(), anyLong())).thenAnswer(call ->
                counts.merge(call.getArgument(0), 1L, Long::sum));
        when(limiter.recordFailure(anyString(), anyLong(), anyLong())).thenAnswer(call ->
                counts.merge(call.getArgument(0), 1L, Long::sum));
        when(limiter.failureCount(anyString(), anyLong())).thenAnswer(call ->
                counts.getOrDefault(call.getArgument(0), 0L));
        when(limiter.failureRetrySeconds(anyString(), anyLong())).thenReturn(60L);
        when(limiter.count(anyString())).thenAnswer(call -> counts.getOrDefault(call.getArgument(0), 0L));
        when(limiter.checkAuthRisk(anyList(), anyBoolean(), anyLong(), anyLong(), anyString())).thenAnswer(call -> {
            List<String> keys = call.getArgument(0);
            boolean register = call.getArgument(1);
            long[] volume = volumes.computeIfAbsent(keys.get(0), ignored -> new long[2]);
            volume[0]++;
            String attemptKey = keys.get(8) + ":" + call.<String>getArgument(4);
            long attempts = register ? counts.merge(attemptKey, 1L, Long::sum) : 0;
            return new RateLimiter.AuthRiskSnapshot(1, volume[0], volume[1],
                    counts.getOrDefault(keys.get(1), 0L), counts.getOrDefault(keys.get(2), 0L),
                    counts.getOrDefault(keys.get(3), 0L),
                    register ? 0 : counts.getOrDefault(keys.get(4), 0L),
                    register ? counts.getOrDefault(keys.get(5), 0L) : 0,
                    register ? counts.getOrDefault(keys.get(6), 0L) : 0,
                    register ? counts.getOrDefault(keys.get(7), 0L) : 0, attempts, 60);
        });
        doAnswer(call -> {
            List<String> keys = call.getArgument(0);
            volumes.computeIfAbsent(keys.get(0), ignored -> new long[2])[1]++;
            for (int i = 1; i < keys.size(); i++) counts.merge(keys.get(i), 1L, Long::sum);
            return null;
        }).when(limiter).recordAuthFailure(anyList(), anyLong(), any(long[].class), any(long[].class));
        doAnswer(call -> {
            List<String> keys = call.getArgument(0);
            keys.forEach(key -> counts.merge(key, 1L, Long::sum));
            return null;
        }).when(limiter).recordAuthSuccess(anyList(), anyLong());
        doAnswer(call -> {
            List<String> keys = call.getArgument(0);
            keys.forEach(counts::remove);
            return null;
        }).when(limiter).clearAuthRisk(anyList());
        doAnswer(call -> {
            counts.remove(call.<String>getArgument(0));
            return null;
        }).when(limiter).clear(anyString());
        risk = new AuthRiskLimiter(limiter, metrics, 5, 5, 30, 5, 256, 5, 30, 12, 60, 120, 0.5);
    }

    @Test
    void distinctSessionsSharingIpAndUaAreNotChallengedForSuccessfulLogins() {
        for (int i = 0; i < 125; i++) {
            MockHttpServletRequest request = request();
            assertFalse(risk.loginRequiresCaptcha("buyer" + i, request));
            risk.recordLoginSuccess("buyer" + i, request);
        }
    }

    @Test
    void independentDevicesOnOneIpAndUaDoNotShareCaptchaChallenge() {
        for (int i = 0; i < 40; i++) {
            MockHttpServletRequest request = request("device-number-" + i);
            assertFalse(risk.registerRequiresCaptcha(request));
            risk.recordRegisterSuccess(request);
        }
        assertFalse(risk.registerRequiresCaptcha(request("device-number-40")));
    }

    @Test
    void sameDeviceAcrossSessionsNeedsCaptchaAfterFiveSuccessfulRegistrations() {
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = request("repeat-device-12345");
            assertFalse(risk.registerRequiresCaptcha(request));
            risk.recordRegisterSuccess(request);
        }
        assertTrue(risk.registerRequiresCaptcha(request("repeat-device-12345")));
        assertFalse(risk.registerRequiresCaptcha(request("another-device-12345")));
    }

    @Test
    void sharedSuccessBurstChallengesOnlyRepeatingDevice() {
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = request("distinct-device-" + i);
            assertFalse(risk.registerRequiresCaptcha(request));
            risk.recordRegisterSuccess(request);
        }
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = request("repeating-device-12345");
            assertFalse(risk.registerRequiresCaptcha(request));
            risk.recordRegisterSuccess(request);
        }
        assertTrue(risk.registerRequiresCaptcha(request("repeating-device-12345")));
        assertFalse(risk.registerRequiresCaptcha(request("innocent-device-12345")));
    }

    @Test
    void sharedFailureBurstChallengesOnlyDeviceWithRepeatedFailures() {
        risk = new AuthRiskLimiter(limiter, metrics, 5, 5, 30, 5, 256, 5, 30, 12, 3, 3, 0.5);
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = request("failed-repeat-device");
            assertFalse(risk.registerRequiresCaptcha(request));
            risk.recordRegisterFailure(request);
        }
        MockHttpServletRequest another = request("failed-once-device");
        assertFalse(risk.registerRequiresCaptcha(another));
        risk.recordRegisterFailure(another);

        assertFalse(risk.registerRequiresCaptcha(request("innocent-device-12345")));
        assertTrue(risk.registerRequiresCaptcha(request("failed-repeat-device")));
    }

    @Test
    void malformedDeviceSignalIsRejected() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Device-Fingerprint", "bad fingerprint");
        BusinessException error = assertThrows(BusinessException.class,
                () -> risk.registerRequiresCaptcha(request));
        assertEquals(400, error.getCode());
    }

    @Test
    void outcomeRecordingFailureDoesNotTurnCommittedRegistrationIntoError() {
        doAnswer(call -> { throw new BusinessException(503, "Redis 暂时不可用"); })
                .when(limiter).recordAuthSuccess(anyList(), anyLong());
        assertDoesNotThrow(() -> risk.recordRegisterSuccess(request("device-1234567890")));
        assertEquals(1.0, metrics.get("shiwushop.auth.register.outcome_record_error").counter().count());
    }

    @Test
    void accountFailuresAcrossSessionsRequireCaptchaAndSuccessClearsThem() {
        for (int i = 0; i < 5; i++) {
            risk.recordLoginFailure("buyer", request());
        }
        MockHttpServletRequest next = request();
        assertTrue(risk.loginRequiresCaptcha("buyer", next));
        risk.recordLoginSuccess("buyer", next);
        assertFalse(risk.loginRequiresCaptcha("buyer", request()));
        assertEquals(5.0, metrics.get("shiwushop.auth.login.failure").counter().count());
        assertEquals(1.0, metrics.get("shiwushop.auth.login.success").counter().count());
    }

    @Test
    void registrationFailuresDoNotBlockLoginInTheSameSession() {
        MockHttpServletRequest request = request();
        for (int i = 0; i < 30; i++) {
            risk.recordRegisterFailure(request);
        }
        assertFalse(risk.loginRequiresCaptcha("buyer", request));
    }

    @Test
    void changingIpDoesNotClearTheSessionsLoginFailures() {
        MockHttpServletRequest request = request();
        for (int i = 0; i < 5; i++) {
            risk.recordLoginFailure("buyer" + i, request);
        }
        request.setRemoteAddr("198.51.100.2");
        assertTrue(risk.loginRequiresCaptcha("new-buyer", request));
    }

    @Test
    void sixthRegistrationAttemptInOneSessionRequiresCaptcha() {
        MockHttpServletRequest request = request();
        for (int i = 0; i < 5; i++) {
            assertFalse(risk.registerRequiresCaptcha(request));
        }
        assertTrue(risk.registerRequiresCaptcha(request));
        assertEquals(1.0, metrics.get("shiwushop.auth.risk.triggered")
                .tag("rule", "register_attempts").counter().count());
    }

    @Test
    void manyFailuresFromOneIpAndUaDoNotChallengeAnUnrelatedDevice() {
        for (int i = 0; i < 60; i++) {
            MockHttpServletRequest request = request("failed-device-" + i);
            request.addHeader("X-Forwarded-For", "192.0.2." + (i + 1));
            assertFalse(risk.registerRequiresCaptcha(request));
            risk.recordRegisterFailure(request);
        }
        MockHttpServletRequest next = request("innocent-device-12345");
        next.addHeader("X-Forwarded-For", "192.0.2.250");
        assertFalse(risk.registerRequiresCaptcha(next));
    }

    @Test
    void requestVolumeAndFailureRatioTogetherRequireCaptcha() {
        risk = new AuthRiskLimiter(limiter, metrics, 5, 5, 30, 5, 256, 5, 30, 12, 1_000, 120, 0.5);
        for (int i = 0; i < 120; i++) {
            MockHttpServletRequest request = request();
            assertFalse(risk.loginRequiresCaptcha("buyer" + i, request));
            if (i < 61) {
                risk.recordLoginFailure("buyer" + i, request);
            }
        }
        assertFalse(risk.loginRequiresCaptcha("new-buyer", request()));
        for (int i = 0; i < 2; i++) risk.recordLoginFailure("new-buyer", request());
        assertTrue(risk.loginRequiresCaptcha("new-buyer", request()));
    }

    @Test
    void changingUaVersionKeepsOneSharedRiskBucket() {
        Set<String> volumeKeys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.10");
            request.addHeader("User-Agent", "Mozilla/5.0 Chrome/" + i + ".0");
            risk.loginRequiresCaptcha("buyer" + i, request);
        }
        volumeKeys.addAll(volumes.keySet());
        assertEquals(1, volumeKeys.size());
    }

    @Test
    void thirtySessionFailuresReturn429WithRetryAfter() {
        MockHttpServletRequest request = request();
        for (int i = 0; i < 30; i++) {
            risk.recordRegisterFailure(request);
        }
        BusinessException error = assertThrows(BusinessException.class,
                () -> risk.registerRequiresCaptcha(request));
        assertEquals(429, error.getCode());
        assertEquals("60", new GlobalExceptionHandler().handle(error).getHeaders().getFirst("Retry-After"));
        assertEquals(1.0, metrics.get("shiwushop.auth.risk.triggered")
                .tag("rule", "session_block").counter().count());
    }

    @Test
    void thirtyLoginFailuresInOneMinuteReturnShort429() {
        MockHttpServletRequest request = request();
        for (int i = 0; i < 30; i++) {
            risk.recordLoginFailure("buyer", request);
        }

        BusinessException error = assertThrows(BusinessException.class,
                () -> risk.loginRequiresCaptcha("buyer", request));
        assertEquals(429, error.getCode());
        assertEquals("60", new GlobalExceptionHandler().handle(error).getHeaders().getFirst("Retry-After"));
    }

    @Test
    void redisFailureReturns503InsteadOfAllowingAuthentication() {
        StringRedisTemplate unavailable = mock(StringRedisTemplate.class);
        RedisConnectionFailureException offline = new RedisConnectionFailureException("offline");
        when(unavailable.opsForValue()).thenThrow(offline);

        BusinessException error = assertThrows(BusinessException.class,
                () -> new RateLimiter(unavailable).count("shiwushop:auth-risk:test"));
        assertEquals(503, error.getCode());
        assertEquals(503, new GlobalExceptionHandler().handleDataAccess(offline).getStatusCode().value());
    }

    @Test
    void emptyRedisScriptResultFailsClosedForRiskCheckAndFailureUpdate() {
        RateLimiter unavailable = new RateLimiter(mock(StringRedisTemplate.class));
        List<String> keys = List.of("shiwushop:test:{auth-risk}:volume", "shiwushop:test:{auth-risk}:failure");
        assertEquals(503, assertThrows(BusinessException.class,
                () -> unavailable.checkAuthRisk(keys, false, 300_000, 16, "session")).getCode());
        assertEquals(503, assertThrows(BusinessException.class,
                () -> unavailable.recordAuthFailure(keys, -1,
                        new long[]{300_000}, new long[]{5})).getCode());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("User-Agent", "shared-browser");
        return request;
    }

    private MockHttpServletRequest request(String fingerprint) {
        MockHttpServletRequest request = request();
        request.addHeader("X-Device-Fingerprint", fingerprint + "-browser");
        return request;
    }
}
