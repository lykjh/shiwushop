package com.shiwushop.common.captcha;

import com.shiwushop.common.auth.RateLimiter;

import com.shiwushop.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CaptchaProofStore {

    /** 滑块已使用标记和登录验证凭证的 Redis 有效期2分钟。 */
    private static final Duration TTL = Duration.ofMinutes(2);
    /** 滑块已使用标记的 Redis 键前缀，后接 captchaId 摘要，防止重复签发凭证。 */
    private static final String USED_PREFIX = "shiwushop:captcha:used:";
    /** 登录滑块凭证的 Redis 键前缀，后接凭证摘要，值为匿名浏览器标识摘要。 */
    private static final String PROOF_PREFIX = "shiwushop:captcha:login:proof:";
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end; "
                    + "return redis.call('DEL', KEYS[1])", Long.class);

    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final SecureRandom random = new SecureRandom();

    /** 先标记原滑块已使用，再保存与匿名浏览器绑定的随机短期凭证。 */
    public String issue(String captchaId, String browserId) {
        try {
            String usedKey = USED_PREFIX + rateLimiter.digest(captchaId);
            Boolean first = redis.opsForValue().setIfAbsent(usedKey, "1", TTL);
            if (!Boolean.TRUE.equals(first)) {
                throw new BusinessException(400, "滑块验证已使用，请重新获取");
            }

            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

            redis.opsForValue().set(PROOF_PREFIX + rateLimiter.digest(proof), rateLimiter.digest(browserId), TTL);

            return proof;
        } catch (DataAccessException e) {
            throw new BusinessException(503, "验证服务暂时不可用", e);
        }
    }

    /** 先核对签发时的浏览器，再在同一 Redis 操作中删除凭证。 */
    public void consume(String proof, String browserId) {
        if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(400, "请重新完成滑块验证");
        }

        try {
            Long consumed = redis.execute(CONSUME, List.of(PROOF_PREFIX + rateLimiter.digest(proof)),
                    rateLimiter.digest(browserId));
            if (consumed == null) {
                throw new BusinessException(503, "验证服务暂时不可用");
            }
            if (consumed != 1) {
                throw new BusinessException(400, "滑块验证已失效，请重新获取");
            }
        } catch (DataAccessException e) {
            throw new BusinessException(503, "验证服务暂时不可用", e);
        }
    }
}
