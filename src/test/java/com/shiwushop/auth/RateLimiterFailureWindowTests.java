package com.shiwushop.auth;

import com.shiwushop.common.auth.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterFailureWindowTests {
    @Test
    void countsOnlyFailuresInsideTheLastWindow() throws Exception {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 6379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        String key = "shiwushop:test:failure:rolling:" + UUID.randomUUID();
        try {
            RateLimiter limiter = new RateLimiter(redis);
            assertEquals(1, limiter.recordFailure(key, 1_200, 2));
            Thread.sleep(700);
            assertEquals(2, limiter.recordFailure(key, 1_200, 2));
            assertEquals(2, limiter.failureCount(key, 1_200));
            assertTrue(limiter.failureRetrySeconds(key, 1_200) >= 1);
            Thread.sleep(650);
            assertEquals(1, limiter.failureCount(key, 1_200));
            assertEquals(2, limiter.recordFailure(key, 1_200, 2));
            assertEquals(2, redis.opsForZSet().size(key));
            limiter.clear(key);
            assertEquals(0, limiter.failureCount(key, 1_200));
        } finally {
            redis.delete(key);
            connection.destroy();
        }
    }
}
