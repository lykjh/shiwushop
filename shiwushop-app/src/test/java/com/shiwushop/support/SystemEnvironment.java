package com.shiwushop.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 系统层不创建应用容器；只连接已部署的回环测试实例和固定隔离数据库。 */
public final class SystemEnvironment {
    private static final Pattern TEST_URL = Pattern.compile("jdbc:mysql://127\\.0\\.0\\.1:24306/([a-z0-9_]+)(?:\\?.*)?");
    private static final Map<String, Integer> REDIS_DATABASES = Map.ofEntries(
            Map.entry("shiwushop_user_test1_20261105b", 9),
            Map.entry("shiwushop_catalog_test2_20261105b", 10),
            Map.entry("shiwushop_member_test3_20261105b", 11),
            Map.entry("shiwushop_marketing_test3_20261105b", 11),
            Map.entry("shiwushop_trade_test4_20261105b", 12),
            Map.entry("shiwushop_acceptance_test_20261105b", 14),
            Map.entry("shiwushop_marketing_test4_20261105b", 12),
            Map.entry("shiwushop_trade_migration_test4_20261106", 12),
            Map.entry("shiwushop_user_testqa_20261106", 14),
            Map.entry("shiwushop_catalog_testqa_20261106", 14),
            Map.entry("shiwushop_member_testqa_20261106", 14),
            Map.entry("shiwushop_marketing_testqa_20261106", 14),
            Map.entry("shiwushop_trade_testqa_20261106", 14));

    private SystemEnvironment() {
    }

    public static String base() {
        isolatedUrl();
        String base = System.getProperty("system.base-url");
        assertNotNull(base, "必须先启动独立测试实例并指定 system.base-url");
        assertTrue(base.matches("http://127\\.0\\.0\\.1:[0-9]+"));
        return base;
    }

    public static JdbcTemplate database() {
        String url = isolatedUrl();
        String username = System.getProperty("system.datasource.username");
        String password = System.getProperty("system.datasource.password");
        assertFalse(username == null || username.isBlank(), "必须显式指定 system.datasource.username");
        assertFalse(password == null || password.isBlank(), "必须显式指定 system.datasource.password");
        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    private static String isolatedUrl() {
        String url = System.getProperty("system.datasource.url");
        requireIsolatedTarget(url, System.getProperty("spring.data.redis.host"),
                Integer.getInteger("spring.data.redis.port"), Integer.getInteger("spring.data.redis.database"));
        return url;
    }

    public static void requireIsolatedTarget(String url, String redisHost, Integer redisPort, Integer redisDatabase) {
        Matcher matcher = TEST_URL.matcher(url == null ? "" : url);
        assertTrue(matcher.matches(), "必须指定本批 127.0.0.1:24306 的精确测试库");
        Integer expectedDatabase = REDIS_DATABASES.get(matcher.group(1));
        assertNotNull(expectedDatabase, "库名不属于本批测试白名单");
        assertEquals("127.0.0.1", redisHost);
        assertEquals(Integer.valueOf(28379), redisPort);
        assertEquals(expectedDatabase, redisDatabase);
    }
}
