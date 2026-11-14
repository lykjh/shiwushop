package com.shiwushop.integration;

import com.shiwushop.support.SystemEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.ContextConfiguration;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/** 防止 YAML 的开发集群配置覆盖隔离测试连接参数。 */
@SpringBootTest
@ContextConfiguration(initializers = IsolatedDependenciesTest.TargetGuard.class)
class IsolatedDependenciesTest {
    public static class TargetGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var environment = context.getEnvironment();
            SystemEnvironment.requireIsolatedTarget(environment.getProperty("spring.datasource.url"),
                    environment.getProperty("spring.data.redis.host"),
                    environment.getProperty("spring.data.redis.port", Integer.class),
                    environment.getProperty("spring.data.redis.database", Integer.class));
            assertFalse(environment.getProperty("spring.datasource.username", "").isBlank());
            assertFalse(environment.getProperty("spring.datasource.password", "").isBlank());
            assertNull(environment.getProperty("spring.data.redis.cluster.nodes"));
        }
    }

    @Autowired
    LettuceConnectionFactory redis;
    @Autowired
    DataSource database;
    @Autowired
    ConfigurableApplicationContext application;

    @Test
    void usesDisposableDependencies() throws Exception {
        assertFalse(application.containsBean("springSessionRepositoryFilter"));
        assertNull(redis.getClusterConfiguration(), "测试禁止连接开发 Redis 集群");
        try (var connection = database.getConnection()) {
            SystemEnvironment.requireIsolatedTarget(connection.getMetaData().getURL(),
                    redis.getHostName(), redis.getPort(), redis.getDatabase());
        }
    }
}
