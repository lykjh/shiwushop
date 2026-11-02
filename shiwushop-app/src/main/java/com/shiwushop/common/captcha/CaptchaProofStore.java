package com.shiwushop.common.captcha;

import com.shiwushop.common.auth.RateLimiter;

import com.shiwushop.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class CaptchaProofStore {

    private static final Duration TTL=Duration.ofMinutes(2);
    private static final String USED_PREFIX="shiwushop:captcha:used:";
    private static final String PROOF_PREFIX = "shiwushop:captcha:login:proof:";

    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final SecureRandom random=new SecureRandom();

    /** 先标记原滑块已使用，再保存与 Session 摘要绑定的随机短期凭证。 */
    public String issue(String captchaId,String sessionId){
        try{
            String usedKey=USED_PREFIX+rateLimiter.digest(captchaId);
            Boolean first =redis.opsForValue().setIfAbsent(usedKey,"1",TTL);
            if(!Boolean.TRUE.equals(first)){
                throw new BusinessException(400, "滑块验证已使用，请重新获取");
            }

            byte[] bytes=new byte[32];
            random.nextBytes(bytes);
            String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

            redis.opsForValue().set(PROOF_PREFIX+rateLimiter.digest(proof),rateLimiter.digest(sessionId),TTL);

            return  proof;
        }catch (DataAccessException e){
            throw new BusinessException(503, "验证服务暂时不可用", e);
        }
    }

    /** 原子读取并删除凭证；仅签发时的 Session 可消费一次。 */
    public void consume(String proof, String sessionId) {

        if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(400, "请重新完成滑块验证");
        }

        try {
            // GET DEL 是一次原子操作；并发请求只能有一个取得该 proof。
            String owner = redis.opsForValue().getAndDelete(PROOF_PREFIX + rateLimiter.digest(proof));
            if (owner == null || !owner.equals(rateLimiter.digest(sessionId))) {
                throw new BusinessException(400, "滑块验证已失效，请重新获取");
            }
        } catch (DataAccessException e) {
            throw new BusinessException(503, "验证服务暂时不可用", e);
        }
    }

}
