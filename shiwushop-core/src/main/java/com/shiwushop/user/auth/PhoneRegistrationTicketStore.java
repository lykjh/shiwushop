package com.shiwushop.user.auth;

import com.shiwushop.common.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** 验码后签发一次性注册票据，票据只绑定原手机号和匿名 Session。 */
@Component
public class PhoneRegistrationTicketStore {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end; " +
                    "return redis.call('DEL', KEYS[1])", Long.class);
    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;

    public PhoneRegistrationTicketStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Redis 键只存票据摘要；原始票据仅交给当前请求的客户端。 */
    public String issue(String phoneDigest, String sessionId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Boolean created = redis.opsForValue().setIfAbsent(key(ticket), value(phoneDigest, sessionId), TTL);
        if (!Boolean.TRUE.equals(created)) throw new BusinessException(503, "注册凭证服务暂时不可用");
        return ticket;
    }

    /** 核对手机号和 Session 后原子删除，错误会话不会消耗合法票据。 */
    public void consume(String ticket, String phoneDigest, String sessionId) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}"))
            throw new BusinessException(401, "注册凭证无效或已过期");
        Long consumed = redis.execute(CONSUME, List.of(key(ticket)), value(phoneDigest, sessionId));
        if (consumed == null) throw new BusinessException(503, "注册凭证服务暂时不可用");
        if (consumed != 1) throw new BusinessException(401, "注册凭证无效或已过期");
    }

    private String key(String ticket) {
        return "shiwushop:sms:registration:" + sha256(ticket);
    }

    private String value(String phoneDigest, String sessionId) {
        return phoneDigest + ":" + sha256(sessionId);
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
