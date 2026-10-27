package com.shiwushop.common.auth;

import com.shiwushop.common.BusinessException;


import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class RateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY=new DefaultRedisScript<>(
            "local count =redis.call('INCR',KEYS[1]);"+"if count == 1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); end; return count",
            Long.class);
    // 使用 Redis 时间和单键脚本，让所有应用实例共享同一个原子令牌桶。
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local current = now[1] * 1000 + math.floor(now[2] / 1000)
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2]) * 1000
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
            local tokens = tonumber(state[1]) or capacity
            local updated = tonumber(state[2]) or current
            tokens = math.min(capacity, tokens + math.max(0, current - updated) * rate)
            local retry = 0
            if tokens >= 1000 then
                tokens = tokens - 1000
            else
                retry = math.ceil((1000 - tokens) / rate / 1000)
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', current)
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
            return retry
            """, Long.class);
    // 只保留最近达到阈值所需的失败时间；Redis 时间避免应用实例时钟不一致。
    private static final DefaultRedisScript<Long> FAILURE_WINDOW = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            local window = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if ARGV[2] ~= '' then
                redis.call('ZADD', KEYS[1], now, ARGV[2])
                local excess = redis.call('ZCARD', KEYS[1]) - tonumber(ARGV[3])
                if excess > 0 then redis.call('ZREMRANGEBYRANK', KEYS[1], 0, excess - 1) end
                redis.call('PEXPIRE', KEYS[1], window)
            end
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);
    private static final DefaultRedisScript<Long> FAILURE_RETRY = new DefaultRedisScript<>("""
            local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            if #oldest == 0 then return 0 end
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            return math.max(1, math.ceil((tonumber(oldest[2]) + tonumber(ARGV[1]) - now) / 1000))
            """, Long.class);
    // 同槽脚本把一次请求的计数和风险快照放在同一个 Redis 时间点。
    private static final DefaultRedisScript<List> AUTH_RISK_CHECK = new DefaultRedisScript<>("""
            local register = ARGV[1] == '1'
            local function valid(key, expected)
                local kind = redis.call('TYPE', key).ok
                return kind == 'none' or kind == expected
            end
            if not valid(KEYS[1], 'hash') then return redis.error_reply('risk volume type') end
            for i = 2, 4 do
                if not valid(KEYS[i], 'zset') then return redis.error_reply('risk failure type') end
            end
            if register then
                if not valid(KEYS[6], 'zset') then return redis.error_reply('device failure type') end
                for i = 7, 8 do
                    if not valid(KEYS[i], 'string') then return redis.error_reply('risk count type') end
                end
                if not valid(KEYS[9], 'hash') or not valid(KEYS[10], 'zset') then
                    return redis.error_reply('session attempt type')
                end
            elseif not valid(KEYS[5], 'zset') then
                return redis.error_reply('account failure type')
            end
            local clock = redis.call('TIME')
            local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
            local bucket = math.floor(now / 60000)
            local requests = redis.call('HINCRBY', KEYS[1], bucket .. ':r', 1)
            local failures = tonumber(redis.call('HGET', KEYS[1], bucket .. ':f')) or 0
            redis.call('HDEL', KEYS[1], (bucket - 2) .. ':r', (bucket - 2) .. ':f')
            redis.call('PEXPIRE', KEYS[1], 120000)
            local function failuresIn(key, window)
                redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
                return redis.call('ZCARD', key)
            end
            local session5 = failuresIn(KEYS[2], tonumber(ARGV[2]))
            local session1 = failuresIn(KEYS[3], 60000)
            local oldest = redis.call('ZRANGE', KEYS[3], 0, 0, 'WITHSCORES')
            local sessionRetry = #oldest == 0 and 0
                    or math.max(1, math.ceil((tonumber(oldest[2]) + 60000 - now) / 1000))
            local shared5 = failuresIn(KEYS[4], tonumber(ARGV[2]))
            local account, deviceFailures, deviceSuccesses, sharedSuccesses, attempts = 0, 0, 0, 0, 0
            if register then
                deviceFailures = failuresIn(KEYS[6], tonumber(ARGV[2]))
                deviceSuccesses = tonumber(redis.call('GET', KEYS[7])) or 0
                sharedSuccesses = tonumber(redis.call('GET', KEYS[8])) or 0
                local session = ARGV[4]
                local expired = redis.call('ZRANGEBYSCORE', KEYS[10], '-inf', now - tonumber(ARGV[2]))
                for _, member in ipairs(expired) do redis.call('HDEL', KEYS[9], member) end
                redis.call('ZREMRANGEBYSCORE', KEYS[10], '-inf', now - tonumber(ARGV[2]))
                attempts = tonumber(redis.call('HGET', KEYS[9], session)) or 0
                attempts = attempts + 1
                redis.call('HSET', KEYS[9], session, attempts)
                redis.call('ZADD', KEYS[10], now, session)
                local excess = redis.call('ZCARD', KEYS[10]) - tonumber(ARGV[3])
                if excess > 0 then
                    local evicted = redis.call('ZRANGE', KEYS[10], 0, excess - 1)
                    for _, member in ipairs(evicted) do redis.call('HDEL', KEYS[9], member) end
                    redis.call('ZREMRANGEBYRANK', KEYS[10], 0, excess - 1)
                end
                redis.call('PEXPIRE', KEYS[9], tonumber(ARGV[2]))
                redis.call('PEXPIRE', KEYS[10], tonumber(ARGV[2]))
            else
                account = failuresIn(KEYS[5], tonumber(ARGV[2]))
            end
            return {bucket, requests, failures, session5, session1, shared5,
                    account, deviceFailures, deviceSuccesses, sharedSuccesses, attempts, sessionRetry}
            """, List.class);
    // Redis 脚本阻止并发读到一半更新的失败事件；先验类型，避免旧键类型污染。
    private static final DefaultRedisScript<Long> AUTH_FAILURE_EVENT = new DefaultRedisScript<>("""
            local kind = redis.call('TYPE', KEYS[1]).ok
            if kind ~= 'none' and kind ~= 'hash' then return redis.error_reply('risk volume type') end
            for i = 2, #KEYS do
                kind = redis.call('TYPE', KEYS[i]).ok
                if kind ~= 'none' and kind ~= 'zset' then return redis.error_reply('risk failure type') end
            end
            local clock = redis.call('TIME')
            local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
            for i = 2, #KEYS do
                local position = 3 + (i - 2) * 2
                local window = tonumber(ARGV[position])
                redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now - window)
                redis.call('ZADD', KEYS[i], now, ARGV[2] .. ':' .. i)
                local excess = redis.call('ZCARD', KEYS[i]) - tonumber(ARGV[position + 1])
                if excess > 0 then redis.call('ZREMRANGEBYRANK', KEYS[i], 0, excess - 1) end
                redis.call('PEXPIRE', KEYS[i], window)
            end
            local bucket = tonumber(ARGV[1])
            local current = math.floor(now / 60000)
            if bucket < 0 then bucket = current end
            if bucket >= current - 1 and bucket <= current then
                redis.call('HINCRBY', KEYS[1], bucket .. ':f', 1)
                redis.call('PEXPIRE', KEYS[1], 120000)
            end
            return #KEYS - 1
            """, Long.class);
    private static final DefaultRedisScript<Long> AUTH_SUCCESS_EVENT = new DefaultRedisScript<>("""
            for i = 1, #KEYS do
                local kind = redis.call('TYPE', KEYS[i]).ok
                if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('risk success type') end
            end
            for i = 1, #KEYS do
                if redis.call('INCR', KEYS[i]) == 1 then redis.call('PEXPIRE', KEYS[i], ARGV[1]) end
            end
            return #KEYS
            """, Long.class);
    private static final DefaultRedisScript<Long> AUTH_CLEAR_EVENT = new DefaultRedisScript<>(
            "return redis.call('DEL', unpack(KEYS))", Long.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis=redis;
    }

    /** 原子计数并返回当前值；Redis 不可用时抛 503（fail-closed） */
    public long increment(String key, long windowMs) {
        try {
            Long count = redis.execute(INCREMENT_WITH_EXPIRY, List.of(key), String.valueOf(windowMs));
            if (count == null) {
                throw new BusinessException(503, "限流服务暂时不可用");
            }
            return count;
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    /** 返回 0 表示放行；否则返回下一个令牌所需的等待秒数。 */
    public long tokenBucketRetrySeconds(String key, long tokensPerSecond, long burst) {
        try {
            Long retry = redis.execute(TOKEN_BUCKET, List.of(key),
                    String.valueOf(tokensPerSecond), String.valueOf(burst));
            if (retry == null) {
                throw new BusinessException(503, "限流服务暂时不可用");
            }
            return retry;
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    public long recordFailure(String key, long windowMs, long maxEvents) {
        return failureWindow(key, windowMs, UUID.randomUUID().toString(), maxEvents);
    }

    public long failureCount(String key, long windowMs) {
        return failureWindow(key, windowMs, "", 1);
    }

    private long failureWindow(String key, long windowMs, String eventId, long maxEvents) {
        try {
            Long count = redis.execute(FAILURE_WINDOW, List.of(key),
                    String.valueOf(windowMs), eventId, String.valueOf(maxEvents));
            if (count == null) throw new BusinessException(503, "限流服务暂时不可用");
            return count;
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    public long failureRetrySeconds(String key, long windowMs) {
        try {
            Long retry = redis.execute(FAILURE_RETRY, List.of(key), String.valueOf(windowMs));
            if (retry == null) throw new BusinessException(503, "限流服务暂时不可用");
            return retry;
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    public record AuthRiskSnapshot(long bucket, long requests, long failures,
            long sessionFailures, long sessionBlockFailures, long sharedFailures,
            long accountFailures, long deviceFailures, long deviceSuccesses,
            long sharedSuccesses, long sessionAttempts, long sessionRetrySeconds) {}

    public AuthRiskSnapshot checkAuthRisk(List<String> keys, boolean register, long windowMs,
            long maxTrackedSessions, String sessionDigest) {
        try {
            List<?> result = redis.execute(AUTH_RISK_CHECK, keys,
                    register ? "1" : "0", String.valueOf(windowMs),
                    String.valueOf(maxTrackedSessions), sessionDigest);
            if (result == null || result.size() != 12) throw new BusinessException(503, "限流服务暂时不可用");
            long[] values = new long[12];
            for (int i = 0; i < values.length; i++) values[i] = ((Number) result.get(i)).longValue();
            return new AuthRiskSnapshot(values[0], values[1], values[2], values[3], values[4],
                    values[5], values[6], values[7], values[8], values[9], values[10], values[11]);
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    public void recordAuthFailure(List<String> keys, long bucket, long[] windows, long[] limits) {
        if (keys.size() != windows.length + 1 || windows.length != limits.length) {
            throw new IllegalArgumentException("风险失败键和窗口数量不一致");
        }
        List<String> arguments = new ArrayList<>();
        arguments.add(String.valueOf(bucket));
        arguments.add(UUID.randomUUID().toString());
        for (int i = 0; i < windows.length; i++) {
            arguments.add(String.valueOf(windows[i]));
            arguments.add(String.valueOf(limits[i]));
        }
        try {
            Long updated = redis.execute(AUTH_FAILURE_EVENT, keys, arguments.toArray(String[]::new));
            if (updated == null || updated != windows.length) throw new BusinessException(503, "限流服务暂时不可用");
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    public void recordAuthSuccess(List<String> keys, long windowMs) {
        try {
            Long updated = redis.execute(AUTH_SUCCESS_EVENT, keys, String.valueOf(windowMs));
            if (updated == null || updated != keys.size()) throw new BusinessException(503, "限流服务暂时不可用");
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    public void clearAuthRisk(List<String> keys) {
        try {
            if (redis.execute(AUTH_CLEAR_EVENT, keys) == null) {
                throw new BusinessException(503, "限流服务暂时不可用");
            }
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    public long count(String key){
        try{
            String value=redis.opsForValue().get(key);
            return value==null?0:Long.parseLong(value);
        }catch (DataAccessException e){
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    public void clear(String key){
        try{
            redis.delete(key);
        }catch (DataAccessException e){
            throw new BusinessException(503, "限流服务暂时不可用", e);
        }
    }

    /** 对限流键片段做散列，避免在 Redis 键名中暴露用户名或 IP。 */
    public String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
