package com.shiwushop.account.auth;

import com.shiwushop.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SmsChallengeStore {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local wait = redis.call('PTTL', KEYS[1])
            if wait > 0 then return math.ceil(wait / 1000) end
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - ARGV[3])
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then
                local oldest = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
                return math.max(1, math.ceil((tonumber(oldest[2]) + ARGV[3] - now) / 1000))
            end
            redis.call('ZADD', KEYS[2], now, ARGV[1])
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[2])
            redis.call('HSET', KEYS[3], 'token', ARGV[1], 'mac', ARGV[5], 'state', 'pending', 'failures', 0)
            redis.call('PEXPIRE', KEYS[3], ARGV[6])
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> ACTIVATE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'token') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'active')
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ABORT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'token') ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'active' then return 0 end
            if redis.call('HGET', KEYS[1], 'mac') == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            if redis.call('HINCRBY', KEYS[1], 'failures', 1) >= tonumber(ARGV[2]) then
                redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final long cooldownMillis;
    private final long windowMillis;
    private final long codeMillis;
    private final int sendsPerWindow;
    private final int failuresPerCode;

    public SmsChallengeStore(StringRedisTemplate redis,
                             @Value("${shop.sms.cooldown-seconds:60}") long cooldownSeconds,
                             @Value("${shop.sms.send-window-seconds:3600}") long windowSeconds,
                             @Value("${shop.sms.code-ttl-seconds:300}") long codeSeconds,
                             @Value("${shop.sms.sends-per-window:10}") int sendsPerWindow,
                             @Value("${shop.sms.failures-per-code:5}") int failuresPerCode) {
        if (cooldownSeconds < 1 || windowSeconds < 1 || codeSeconds < 1
                || sendsPerWindow < 1 || failuresPerCode < 1) throw new IllegalArgumentException("短信限额参数无效");
        this.redis = redis;
        this.cooldownMillis = cooldownSeconds * 1000;
        this.windowMillis = windowSeconds * 1000;
        this.codeMillis = codeSeconds * 1000;
        this.sendsPerWindow = sendsPerWindow;
        this.failuresPerCode = failuresPerCode;
    }

    /** 同槽原子检查冷却与滚动发送额度，保存待渠道确认的挑战。 */
    public void reserve(String digest, String purpose, String token, String mac) {
        String prefix = prefix(digest);
        Long retry = redis.execute(RESERVE, List.of(prefix + ":cooldown", prefix + ":sends",
                        prefix + ":" + purpose + ":code"),
                token, Long.toString(cooldownMillis), Long.toString(windowMillis),
                Integer.toString(sendsPerWindow), mac, Long.toString(codeMillis));
        if (retry == null) throw new BusinessException(503, "验证码服务暂时不可用");
        if (retry > 0) throw new BusinessException(429, "验证码发送过于频繁",
                Map.of("retryAfterSeconds", retry), null);
    }

    /** 只有当前这次发送的令牌能激活挑战，旧发送结果不能覆盖新验证码。 */
    public void activate(String digest, String purpose, String token) {
        if (!Long.valueOf(1).equals(redis.execute(ACTIVATE, List.of(codeKey(digest, purpose)), token)))
            throw new BusinessException(503, "验证码服务暂时不可用");
    }

    public void abort(String digest, String purpose, String token) {
        redis.execute(ABORT, List.of(codeKey(digest, purpose)), token);
    }

    /** 校验和删除在同一个 Redis 命令中执行，错误次数耗尽后删除挑战。 */
    public boolean consume(String digest, String purpose, String candidateMac) {
        Long consumed = redis.execute(CONSUME,
                List.of(codeKey(digest, purpose)), candidateMac,
                Integer.toString(failuresPerCode));
        if (consumed == null) throw new BusinessException(503, "验证码服务暂时不可用");
        return consumed == 1;
    }

    private String codeKey(String digest, String purpose) {
        return prefix(digest) + ":" + purpose + ":code";
    }

    private String prefix(String digest) {
        return "shiwushop:sms:{" + digest + "}";
    }
}
