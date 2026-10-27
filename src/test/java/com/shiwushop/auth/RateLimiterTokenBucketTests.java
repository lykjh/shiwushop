package com.shiwushop.auth;

import com.shiwushop.common.auth.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimiterTokenBucketTests {
    @Test
    void burstIsAtomicAndRefillsOverTime() throws Exception {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 6379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        String key = "shiwushop:test:register:bucket:" + UUID.randomUUID();
        try {
            RateLimiter limiter = new RateLimiter(redis);
            assertEquals(0, limiter.tokenBucketRetrySeconds(key, 10, 2));
            assertEquals(0, limiter.tokenBucketRetrySeconds(key, 10, 2));
            assertEquals(1, limiter.tokenBucketRetrySeconds(key, 10, 2));
            Thread.sleep(150);
            assertEquals(0, limiter.tokenBucketRetrySeconds(key, 10, 2));
        } finally {
            redis.delete(key);
            connection.destroy();
        }
    }
}
