package com.shiwushop.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** 系统层不创建应用容器；只连接已部署的回环测试实例和固定隔离数据库。 */
public final class SystemEnvironment {
    private SystemEnvironment() {}
    public static String base() {
        String base=System.getProperty("system.base-url"); assertNotNull(base,"必须先启动独立测试实例并指定 system.base-url");
        assertTrue(base.matches("http://127\\.0\\.0\\.1:[0-9]+"));return base;
    }
    public static JdbcTemplate database(){
        String url=System.getProperty("system.datasource.url","jdbc:mysql://127.0.0.1:13306/shiwushop");
        // 系统验证仅接入隔离测试端口，禁止把清理用例指向开发库或生产库。
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/shiwushop(?:_gaps_[a-z]+)?"));
        return new JdbcTemplate(new DriverManagerDataSource(url,"shiwushop","test-shiwushop"));
    }
}
