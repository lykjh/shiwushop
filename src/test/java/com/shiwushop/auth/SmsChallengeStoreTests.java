package com.shiwushop.auth;

import com.shiwushop.account.auth.SmsChallengeStore;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmsChallengeStoreTests {
    @Test
    void codeIsPurposeBoundAndConsumedOnlyOnceUnderConcurrency() throws Exception {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 16379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        SmsChallengeStore store = new SmsChallengeStore(redis, 60, 3600, 300, 10, 5);
        String digest = UUID.randomUUID().toString().replace("-", "");
        try {
            store.reserve(digest, "login", "one", "correct-mac");
            assertThat(store.consume(digest, "login", "correct-mac")).isFalse();
            store.activate(digest, "login", "one");
            assertThat(store.consume(digest, "bind", "correct-mac")).isFalse();
            assertThat(store.consume(digest, "login", "wrong-mac")).isFalse();

            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> { start.await(); return store.consume(digest, "login", "correct-mac"); }));
                }
                start.countDown();
                assertThat(results.get(0).get() ^ results.get(1).get()).isTrue();
            }
            assertThat(store.consume(digest, "login", "correct-mac")).isFalse();
        } finally {
            redis.delete(keys(digest, "login"));
            redis.delete(keys(digest, "bind"));
            connection.destroy();
        }
    }

    @Test
    void cooldownAndWrongAttemptLimitRejectFurtherUse() {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", 16379);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        SmsChallengeStore store = new SmsChallengeStore(redis, 60, 3600, 300, 10, 2);
        String digest = UUID.randomUUID().toString().replace("-", "");
        try {
            store.reserve(digest, "login", "one", "correct-mac");
            assertThatThrownBy(() -> store.reserve(digest, "login", "two", "other-mac"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(429);
            store.activate(digest, "login", "one");
            assertThat(store.consume(digest, "login", "wrong")).isFalse();
            assertThat(store.consume(digest, "login", "wrong")).isFalse();
            assertThat(store.consume(digest, "login", "correct-mac")).isFalse();
        } finally {
            redis.delete(keys(digest, "login"));
            connection.destroy();
        }
    }

    private static List<String> keys(String digest, String purpose) {
        String prefix = "shiwushop:sms:{" + digest + "}";
        return List.of(prefix + ":" + purpose + ":code", prefix + ":sends", prefix + ":cooldown");
    }
}
