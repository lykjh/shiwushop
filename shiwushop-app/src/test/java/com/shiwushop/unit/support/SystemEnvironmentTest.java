package com.shiwushop.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemEnvironmentTest {
    @Test
    void acceptsOnlyThisBatchSchemaAndMatchingRedisDatabase() {
        String prefix = "jdbc:mysql://127.0.0.1:24306/";
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_user_test1_20261105b", "127.0.0.1", 28379, 9);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_catalog_test2_20261105b", "127.0.0.1", 28379, 10);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_member_test3_20261105b", "127.0.0.1", 28379, 11);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_marketing_test3_20261105b", "127.0.0.1", 28379, 11);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_trade_test4_20261105b", "127.0.0.1", 28379, 12);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_acceptance_test_20261105b", "127.0.0.1", 28379, 14);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_marketing_test4_20261105b", "127.0.0.1", 28379, 12);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_trade_migration_test4_20261106", "127.0.0.1", 28379, 12);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_user_testqa_20261106", "127.0.0.1", 28379, 14);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_catalog_testqa_20261106", "127.0.0.1", 28379, 14);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_member_testqa_20261106", "127.0.0.1", 28379, 14);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_marketing_testqa_20261106", "127.0.0.1", 28379, 14);
        SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_trade_testqa_20261106", "127.0.0.1", 28379, 14);

        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_user_dev1_20261105b", "127.0.0.1", 28379, 9));
        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_user_test1_20261105b_extra", "127.0.0.1", 28379, 9));
        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget("jdbc:mysql://127.0.0.1:13306/shiwushop_user_test1_20261105b", "127.0.0.1", 28379, 9));
        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_user_test1_20261105b", "127.0.0.1", 28379, 10));
        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_user_test1_20261105b", "127.0.0.1", 27379, 9));
        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_user_test1_20261105b", "localhost", 28379, 9));
        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_marketing_test4_20261105b", "127.0.0.1", 28379, 11));
        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_trade_migration_test4_20261106", "127.0.0.1", 28379, 9));
        assertThrows(AssertionError.class, () -> SystemEnvironment.requireIsolatedTarget(prefix + "shiwushop_user_testqa_20261106", "127.0.0.1", 28379, 9));
    }
}
