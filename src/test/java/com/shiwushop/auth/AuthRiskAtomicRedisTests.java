package com.shiwushop.auth;

import com.shiwushop.account.auth.AuthRiskLimiter;
import com.shiwushop.common.auth.RateLimiter;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRiskAtomicRedisTests {
    @Test
    void oneFailureUpdatesEverySignalAndTheSameMinuteRatio() {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 6379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        String prefix = "shiwushop:test:{auth-risk}:" + UUID.randomUUID() + ":";
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) keys.add(prefix + i);
        try {
            for (String key : keys) assertEquals(SlotHash.getSlot(keys.getFirst()), SlotHash.getSlot(key));
            RateLimiter limiter = new RateLimiter(redis);
            RateLimiter.AuthRiskSnapshot first = limiter.checkAuthRisk(keys, false, 300_000, 256, "session");
            assertEquals(1, first.requests());
            assertEquals(0, first.failures());
            limiter.recordAuthFailure(List.of(keys.get(0), keys.get(4), keys.get(1),
                    keys.get(2), keys.get(3)), first.bucket(),
                    new long[]{300_000, 300_000, 60_000, 300_000},
                    new long[]{5, 5, 30, 60});
            RateLimiter.AuthRiskSnapshot second = limiter.checkAuthRisk(keys, false, 300_000, 256, "session");
            assertEquals(2, second.requests());
            assertEquals(1, second.failures());
            assertEquals(1, second.accountFailures());
            assertEquals(1, second.sessionFailures());
            assertEquals(1, second.sessionBlockFailures());
            assertEquals(1, second.sharedFailures());
            limiter.clearAuthRisk(List.of(keys.get(4), keys.get(1), keys.get(2)));
            assertEquals(0, limiter.failureCount(keys.get(4), 300_000));
        } finally {
            redis.delete(keys);
            connection.destroy();
        }
    }

    @Test
    void wrongTypeIsRejectedBeforeAnyFailureWrite() {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 6379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        String prefix = "shiwushop:test:{auth-risk}:" + UUID.randomUUID() + ":";
        List<String> keys = List.of(prefix + "volume", prefix + "account", prefix + "wrong");
        try {
            redis.opsForValue().set(keys.get(2), "wrong type");
            RateLimiter limiter = new RateLimiter(redis);
            assertThrows(RuntimeException.class, () -> limiter.recordAuthFailure(keys, -1,
                    new long[]{300_000, 300_000}, new long[]{5, 5}));
            assertEquals(0, limiter.failureCount(keys.get(1), 300_000));
            assertEquals(false, redis.hasKey(keys.get(0)));
        } finally {
            redis.delete(keys);
            connection.destroy();
        }
    }

    @Test
    void registrationAttemptsStayBoundedAcrossRotatingSessions() {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 6379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        String prefix = "shiwushop:test:{auth-risk}:" + UUID.randomUUID() + ":";
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) keys.add(prefix + i);
        try {
            RateLimiter limiter = new RateLimiter(redis);
            for (int i = 1; i <= 6; i++) {
                assertEquals(i, limiter.checkAuthRisk(keys, true, 300_000, 16, "one-session").sessionAttempts());
            }
            for (int i = 0; i < 100; i++) {
                limiter.checkAuthRisk(keys, true, 300_000, 16, "rotated-" + i);
            }
            assertEquals(16, redis.opsForHash().size(keys.get(8)));
            assertEquals(16, redis.opsForZSet().size(keys.get(9)));
            limiter.recordAuthSuccess(List.of(keys.get(7)), 300_000);
            assertEquals(1, limiter.checkAuthRisk(keys, true, 300_000, 16, "new-session").sharedSuccesses());
        } finally {
            redis.delete(keys);
            connection.destroy();
        }
    }

    @Test
    void rotatingUaAndCookieKeepOnlyThreeCleanRegistrationRiskKeys() {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 6379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        RateLimiter limiter = new RateLimiter(redis);
        String address = "198.18.test." + UUID.randomUUID();
        String shared = "shiwushop:auth-risk:{auth-risk}:ip-ua:"
                + limiter.digest(address + "\0chrome");
        List<String> keys = List.of(shared + ":register:volume:1m",
                shared + ":register:session-attempts",
                shared + ":register:session-attempt-index");
        try {
            long memoryBefore;
            long keysBefore;
            try (var connectionView = connection.getConnection()) {
                memoryBefore = Long.parseLong(connectionView.serverCommands()
                        .info("memory").getProperty("used_memory"));
                keysBefore = connectionView.serverCommands().dbSize();
            }
            AuthRiskLimiter risk = new AuthRiskLimiter(limiter, new SimpleMeterRegistry(),
                     5, 5, 30, 5, 16, 5, 30, 12, 60, 120, 0.5);
            for (int i = 0; i < 500; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.setRemoteAddr(address);
                request.addHeader("User-Agent", "Mozilla/5.0 Chrome/" + i + ".0");
                risk.registerRequiresCaptcha(request);
            }
            for (String key : keys) assertEquals(true, redis.hasKey(key));
            assertEquals(16, redis.opsForHash().size(keys.get(1)));
            assertEquals(16, redis.opsForZSet().size(keys.get(2)));
            try (var connectionView = connection.getConnection()) {
                long memoryAfter = Long.parseLong(connectionView.serverCommands()
                        .info("memory").getProperty("used_memory"));
                long keysAfter = connectionView.serverCommands().dbSize();
                RedisClient client = RedisClient.create("redis://127.0.0.1:6379");
                long trackedBytes = 0;
                try (var direct = client.connect()) {
                    for (String key : keys) trackedBytes += direct.sync().memoryUsage(key);
                } finally {
                    client.shutdown();
                }
                System.out.printf("rotating 500 sessions: riskKeys=3 dbKeysDelta=%d trackedKeyBytes=%dB nodeMemoryDelta=%dB%n",
                        keysAfter - keysBefore, trackedBytes, memoryAfter - memoryBefore);
            }
        } finally {
            redis.delete(keys);
            connection.destroy();
        }
    }

    @Test
    void concurrentFailuresLeaveAllRelatedCountsEqual() throws Exception {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 6379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        String prefix = "shiwushop:test:{auth-risk}:" + UUID.randomUUID() + ":";
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) keys.add(prefix + i);
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            RateLimiter limiter = new RateLimiter(redis);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                tasks.add(workers.submit(() -> {
                    start.await();
                    RateLimiter.AuthRiskSnapshot snapshot = limiter.checkAuthRisk(keys, false, 300_000, 256, "session");
                    limiter.recordAuthFailure(List.of(keys.get(0), keys.get(4), keys.get(1),
                            keys.get(2), keys.get(3)), snapshot.bucket(),
                            new long[]{300_000, 300_000, 60_000, 300_000},
                            new long[]{50, 50, 50, 50});
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get();
            RateLimiter.AuthRiskSnapshot finalState = limiter.checkAuthRisk(keys, false, 300_000, 256, "session");
            assertEquals(41, finalState.requests());
            assertEquals(40, finalState.failures());
            assertEquals(40, finalState.accountFailures());
            assertEquals(40, finalState.sessionFailures());
            assertEquals(40, finalState.sessionBlockFailures());
            assertEquals(40, finalState.sharedFailures());
            assertTrue(finalState.sessionRetrySeconds() >= 1 && finalState.sessionRetrySeconds() <= 60);
        } finally {
            redis.delete(keys);
            connection.destroy();
        }
    }
}
