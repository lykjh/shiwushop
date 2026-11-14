package com.shiwushop.user.auth;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.SessionUser.Identity;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class LoginSessions {
    private static final long IDLE_MILLIS = Duration.ofMinutes(30).toMillis();
    // 一个账号一个 Redis Hash，使会话变更在 Redis Cluster 的同一槽内原子执行。
    // 字段值依次为创建时间、最近访问时间、绝对过期时间、账号鉴权版本。
    private static final String READ = """
            local function active(value, now, idle, version)
                if not value then return nil end
                local created, last, expires, v = string.match(value, '^(%d+):(%d+):(%d+):(%d+)$')
                if not created or (version and v ~= version)
                        or tonumber(expires) <= now or tonumber(last) + idle <= now then
                    return nil
                end
                return created, last, expires, v
            end
            """;
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>(READ + """
            local now, idle = tonumber(ARGV[2]), tonumber(ARGV[3])
            local entries = redis.call('HGETALL', KEYS[1])
            for i = 1, #entries, 2 do
                if not active(entries[i + 1], now, idle, nil) then
                    redis.call('HDEL', KEYS[1], entries[i])
                end
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2] .. ':' .. ARGV[2] .. ':' .. ARGV[4] .. ':' .. ARGV[5])
            local ttl = tonumber(ARGV[4]) - now
            if redis.call('PTTL', KEYS[1]) < ttl then
                redis.call('PEXPIREAT', KEYS[1], ARGV[4])
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> TOUCH = new DefaultRedisScript<>(READ + """
            local now, idle = tonumber(ARGV[2]), tonumber(ARGV[3])
            local created, last, expires, v = active(redis.call('HGET', KEYS[1], ARGV[1]), now, idle, ARGV[4])
            if not created then return 0 end
            if now > tonumber(last) then
                redis.call('HSET', KEYS[1], ARGV[1], created .. ':' .. ARGV[2] .. ':' .. expires .. ':' .. v)
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>(READ + """
            local now, idle = tonumber(ARGV[2]), tonumber(ARGV[3])
            if not active(redis.call('HGET', KEYS[1], ARGV[1]), now, idle, ARGV[4]) then return -1 end
            local target = ARGV[5]
            if target ~= '' then
                if not active(redis.call('HGET', KEYS[1], target), now, idle, ARGV[4]) then return 0 end
                return redis.call('HDEL', KEYS[1], target)
            end
            for _, sid in ipairs(redis.call('HKEYS', KEYS[1])) do
                if sid ~= ARGV[1] then redis.call('HDEL', KEYS[1], sid) end
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public void create(Identity identity, Instant expiresAt) {
        execute(CREATE, identity, Long.toString(expiresAt.toEpochMilli()), Integer.toString(identity.authVersion()));
    }

    public boolean touch(Identity identity) {
        return execute(TOUCH, identity, Integer.toString(identity.authVersion())) == 1;
    }

    public void logout(Identity identity) {
        redisCall(() -> redis.opsForHash().delete(key(identity.userId()), identity.sessionRef()));
    }

    public void revoke(Identity identity, String target) {
        long result = execute(REVOKE, identity, Integer.toString(identity.authVersion()), target);
        if (result == -1)
            throw new BusinessException(401, "请重新登录");
        if (result == 0)
            throw new BusinessException(404, "会话不存在");
    }

    public record Entry(String sessionRef, Instant createdAt, Instant lastAccessedAt, Instant expiresAt) {}

    public List<Entry> list(Identity identity) {
        long now = clock.millis();
        return redisCall(() -> redis.<String, String>opsForHash().entries(key(identity.userId())))
                .entrySet().stream().map(entry -> {
                    String[] fields = entry.getValue().split(":");
                    long lastAccess = Long.parseLong(fields[1]);
                    long expiresAt = Math.min(lastAccess + IDLE_MILLIS, Long.parseLong(fields[2]));
                    if (now >= expiresAt || Integer.parseInt(fields[3]) != identity.authVersion())
                        return null;
                    return new Entry(entry.getKey(), Instant.ofEpochMilli(Long.parseLong(fields[0])),
                            Instant.ofEpochMilli(lastAccess), Instant.ofEpochMilli(expiresAt));
                }).filter(java.util.Objects::nonNull).toList();
    }

    private long execute(DefaultRedisScript<Long> script, Identity identity, String... extra) {
        String[] args = new String[3 + extra.length];
        args[0] = identity.sessionRef();
        args[1] = Long.toString(clock.millis());
        args[2] = Long.toString(IDLE_MILLIS);
        System.arraycopy(extra, 0, args, 3, extra.length);
        Long result = redisCall(() -> redis.execute(script, List.of(key(identity.userId())), (Object[]) args));
        if (result == null)
            throw new BusinessException(503, "会话服务暂不可用");
        return result;
    }

    private static <T> T redisCall(Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException e) {
            throw new BusinessException(503, "会话服务暂不可用", e);
        }
    }

    private static String key(Long userId) {
        return "shiwushop:auth:logins:" + userId;
    }
}
