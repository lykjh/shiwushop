package com.shiwushop.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

/** 防止 YAML 的开发集群配置覆盖隔离测试连接参数。 */
@SpringBootTest
class IsolatedDependenciesTest {
    @Autowired LettuceConnectionFactory redis;
    @Autowired DataSource database;

    @Test void usesDisposableDependencies() throws Exception {
        assertNull(redis.getClusterConfiguration(), "测试禁止连接开发 Redis 集群");
        assertEquals(16379, redis.getPort());
        try (var connection = database.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:13306/shiwushop"));
        }
    }
}
