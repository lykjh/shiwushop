package com.shiwushop.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 只准备本测试拥有的账号；真实 HTTP 测试不能依赖请求线程之外的回滚事务。 */
public final class TestAccounts implements AutoCloseable {
    private static final AtomicLong NEXT = new AtomicLong(System.currentTimeMillis());
    private final JdbcTemplate jdbc;
    private final List<Long> ids = new ArrayList<>();
    public TestAccounts(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Account(long id, String username, String password) {}
    public Account create(String role, String password) {
        String username = "member" + NEXT.incrementAndGet();
        jdbc.update("INSERT INTO users(nickname) VALUES (?)", username);
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM users WHERE nickname=?", Long.class, username));
        ids.add(id);
        jdbc.update("INSERT INTO user_accounts(user_id,username,password_hash,role,status) VALUES (?,?,?,?,'ACTIVE')",
                id, username, new BCryptPasswordEncoder().encode(password), role);
        return new Account(id, username, password);
    }
    @Override public void close() {
        for (long id : ids) {
            jdbc.update("DELETE FROM user_accounts WHERE user_id=?", id);
            jdbc.update("DELETE FROM users WHERE id=?", id);
        }
    }
}
