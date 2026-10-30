package com.shiwushop.account.auth;

import com.shiwushop.common.BusinessException;


import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AccountAuthCache {
    private static final Logger log = LoggerFactory.getLogger(AccountAuthCache.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration BLOCK_TTL = Duration.ofMinutes(2);

    // 只允许创建阻断标记的事务收尾，避免旧事务覆盖较新的账号状态。
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end; " +
                    "if ARGV[2] == 'DELETE' then return redis.call('DEL', KEYS[1]) end; " +
                    "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1",
            Long.class);

    private final StringRedisTemplate redis;
    private final UserAccountMapper accounts;

    /** 缓存中的账号状态和鉴权版本；版本变化用于识别应失效的旧 Session。 */
    public record State(AccountStatus status, int version) {}

    /**
     * 读取账号状态缓存；未命中时从数据库加载并尝试回填。
     * 回填后再次读取 Redis，防止数据库查询期间错过并发事务写入的阻断标记。
     */
    public State current(Long userId) {
        String key = key(userId);
        try {
            for (int i = 0; i < 3; i++) {
                String cached = redis.opsForValue().get(key);
                if (cached != null) return decode(cached);

                UserAccount account = accounts.selectById(userId);
                String loaded = account == null ? "MISSING"
                        : encode(new State(account.getStatus(), account.getAuthVersion()));
                redis.opsForValue().setIfAbsent(key, loaded, CACHE_TTL);
                // 回读 Redis：数据库读取期间，另一个事务可能已写入阻断标记或新版本。
            }
            throw new BusinessException(503, "账号校验暂不可用");
        } catch (DataAccessException e) {
            throw new BusinessException(503, "账号校验暂不可用", e);
        }
    }

    /**
     * 锁住账号行后、修改数据库前阻断旧会话；事务提交后发布新版本，回滚后清除标记。
     * 调用方事务超时必须短于 BLOCK_TTL；绕过此方法直接改库不会立即失效缓存。
     */
    public void blockUntilTransactionEnds(Long userId, State committedState) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("账号变更必须在事务中执行");
        }

        String key = key(userId);
        String marker = "BLOCKED:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, marker, BLOCK_TTL);
        } catch (DataAccessException e) {
            throw new BusinessException(503, "账号校验暂不可用", e);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                String value = status == STATUS_COMMITTED ? encode(committedState) : "DELETE";
                try {
                    redis.execute(FINISH, List.of(key), marker, value,
                            Long.toString(CACHE_TTL.toMillis()));
                } catch (DataAccessException e) {
                    // 标记保留至过期；期间校验失败关闭，过期后从数据库重建。
                    log.error("账号状态缓存收尾失败，userId={}", userId, e);
                }
            }
        });
    }

    /** 为一个用户构造 Redis 鉴权状态键，避免与业务缓存共用命名空间。 */
    private static String key(Long userId) {
        return "shiwushop:auth:state:" + userId;
    }

    /** 将状态和版本编码为短字符串，供 Redis 缓存与事务收尾脚本使用。 */
    private static String encode(State state) {
        return state.status().name() + ":" + state.version();
    }

    /** 解析缓存状态；缺失标记返回空，变更中的阻断标记和损坏数据均拒绝放行。 */
    private static State decode(String value) {
        if ("MISSING".equals(value)) return null;
        if (value.startsWith("BLOCKED:")) {
            throw new BusinessException(503, "账号状态更新中，请稍后重试");
        }
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new BusinessException(503, "账号缓存格式错误");
        try {
            return new State(AccountStatus.valueOf(parts[0]), Integer.parseInt(parts[1]));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(503, "账号缓存格式错误", e);
        }
    }
}
