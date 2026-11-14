package com.shiwushop.integration;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertThrows;

class IsolatedDependenciesTargetGuardTest {
    @Test
    void rejectsWrongTargetBeforeContextRefresh() {
        var environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:24306/shiwushop_user_test1_20261105b")
                .withProperty("spring.datasource.username", "test_user")
                .withProperty("spring.datasource.password", "test_password")
                .withProperty("spring.data.redis.host", "127.0.0.1")
                .withProperty("spring.data.redis.port", "28379")
                .withProperty("spring.data.redis.database", "9");
        var context = new GenericApplicationContext();
        context.setEnvironment(environment);
        var guard = new IsolatedDependenciesTest.TargetGuard();

        guard.initialize(context);
        environment.withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:24306/shiwushop_user_testqa_20261106")
                .withProperty("spring.data.redis.database", "14");
        guard.initialize(context);
        environment.withProperty("spring.data.redis.database", "0");
        assertThrows(AssertionError.class, () -> guard.initialize(context));
        environment.withProperty("spring.data.redis.database", "9")
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:13306/shiwushop_user_test1_20261105b");
        assertThrows(AssertionError.class, () -> guard.initialize(context));
    }
}
