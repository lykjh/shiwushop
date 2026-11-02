package com.shiwushop.system.account;

import com.shiwushop.support.HttpAccountClient;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 对独立进程进行系统验证；显式指定 system.base-url 才可运行，无内嵌 Spring 容器。 */
class AccountSessionSystemIT {
    private final String username = "buyer" + System.currentTimeMillis();
    private long id;
    private String base;
    private Connection database;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void prepareIsolatedAccount() throws Exception {
        base = System.getProperty("system.base-url");
        assertNotNull(base, "请先部署隔离实例并指定 -Dsystem.base-url=http://127.0.0.1:18081");
        assertTrue(base.matches("http://127\\.0\\.0\\.1:[0-9]+"), "本地系统测试只连接回环地址");
        // 固定隔离端口，避免运行测试时误写开发库或生产库。
        database = DriverManager.getConnection("jdbc:mysql://127.0.0.1:13306/shiwushop", "shiwushop", "test-shiwushop");
        try (var insert = database.prepareStatement("INSERT INTO users(nickname) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, "张明"); insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) { assertTrue(keys.next()); id = keys.getLong(1); }
        }
        try (var insert = database.prepareStatement("INSERT INTO user_accounts(user_id,username,password_hash,role,status) VALUES (?,?,?,'USER','ACTIVE')")) {
            insert.setLong(1, id); insert.setString(2, username);
            insert.setString(3, new BCryptPasswordEncoder().encode("Shop123!")); insert.executeUpdate();
        }
    }
    @AfterEach void removeOnlyOwnFixture() throws Exception {
        if (database == null) return;
        try (Connection connection = database; var account = connection.prepareStatement("DELETE FROM user_accounts WHERE user_id=?");
             var profile = database.prepareStatement("DELETE FROM users WHERE id=?")) {
            account.setLong(1, id); account.executeUpdate(); profile.setLong(1, id); profile.executeUpdate();
        }
    }
    @Test void loginMeChangePasswordAndLogoutAcrossRealRequests() throws Exception {
        try (var client = new HttpAccountClient(base)) {
            client.csrf();
            String anonymous = client.sessionId();
            var login = client.post("/users/login", Map.of("username", username, "password", "Shop123!"));
            assertEquals(200, login.statusCode(), login.body());
            assertNotEquals(anonymous, client.sessionId());
            var me = client.get("/users/me");
            assertEquals(200, me.statusCode());
            assertEquals(id, json.readTree(me.body()).path("data").path("id").asLong());
            client.csrf();
            var change = client.post("/users/password", Map.of("oldPassword", "Shop123!", "newPassword", "Shop456!"));
            assertEquals(200, change.statusCode(), change.body());
            assertEquals(401, client.get("/users/me").statusCode());
            client.csrf();
            assertEquals(401, client.post("/users/login", Map.of("username", username, "password", "Shop123!")).statusCode());
            var relogin = client.post("/users/login", Map.of("username", username, "password", "Shop456!"));
            assertEquals(200, relogin.statusCode(), relogin.body());
            client.csrf();
            assertEquals(200, client.post("/users/logout", Map.of()).statusCode());
            assertEquals(401, client.get("/users/me").statusCode());
        }
    }
}
