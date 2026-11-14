package com.shiwushop.integration.redis.auth;

import com.shiwushop.common.auth.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimiterTokenBucketTests {
    @Test
    void burstIsAtomicAndRefillsOverTime() throws Exception {
        assertEquals("127.0.0.1", System.getProperty("spring.data.redis.host"));
        assertEquals(28379, Integer.getInteger("spring.data.redis.port"));
        assertEquals(9, Integer.getInteger("spring.data.redis.database"));
        LettuceConnectionFactory connection = new LettuceConnectionFactory(System.getProperty("spring.data.redis.host"), Integer.getInteger("spring.data.redis.port"));
        connection.setDatabase(Integer.getInteger("spring.data.redis.database"));
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
