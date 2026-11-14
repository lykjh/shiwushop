package com.shiwushop.user.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.auth.RateLimiter;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.user.auth.AccountAuthCache;
import com.shiwushop.user.auth.JwtLogin;
import com.shiwushop.user.auth.LoginRateLimiter;
import com.shiwushop.user.auth.PhoneLookup;
import com.shiwushop.user.auth.SmsChallengeStore;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.PhoneBindingMapper;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.PasswordResetService;
import com.shiwushop.user.vo.user.PasswordResetVerifyVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** 找回授权保存在 Redis；账号行锁与鉴权版本共同裁决并发重设。 */
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {
    /** 密码重置短信的用途标识，与登录和绑定验证码隔离。 */
    private static final String PURPOSE = "password-reset";
    private static final Duration GRANT_TTL = Duration.ofMinutes(5);
    private static final String GRANT_PREFIX = "shiwushop:password-reset:grant:";
    private static final DefaultRedisScript<Long> CONSUME_GRANT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);
    /** 计算手机号查找摘要并定位已绑定账号。 */
    private final PhoneLookup phones;
    /** 保存手机号密文和用于查找已绑定账号的摘要。 */
    private final PhoneBindingMapper bindings;
    /** 持久化登录账号状态、密码哈希和会话鉴权版本。 */
    private final UserAccountMapper accounts;
    /** 保存短信验证码挑战、发码冷却和核验次数。 */
    private final SmsChallengeStore challenges;
    /** 检查发码预算并向短信渠道发出验证码请求。 */
    private final SmsCodeDelivery delivery;
    /** 检查登录入口是否还有可用请求令牌。 */
    private final LoginRateLimiter loginLimiter;
    /** 使用 Redis 执行令牌桶和风控次数限制。 */
    private final RateLimiter limiter;
    /** 读取和更新账号状态、角色与会话鉴权版本的 Redis 缓存。 */
    private final AccountAuthCache authCache;
    /** 提供业务逻辑使用的当前时间。 */
    private final Clock clock;
    private final JwtLogin jwt;
    private final StringRedisTemplate redis;
    /** 生成密码重置流程使用的随机票据。 */
    private final SecureRandom random = new SecureRandom();
    /** 以 BCrypt 保存密码哈希并校验提交的密码。 */
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    private record Grant(Long userId, String phoneDigest, int authVersion, Instant expiresAt) {
        String encode(String ticketDigest) {
            return ticketDigest + ":" + userId + ":" + phoneDigest + ":" + authVersion + ":" + expiresAt.toEpochMilli();
        }

        static Grant read(String value, String expectedDigest) {
            if (value == null)
                throw invalidGrant();
            String[] fields = value.split(":", -1);
            if (fields.length != 5 || !expectedDigest.equals(fields[0]))
                throw invalidGrant();
            return new Grant(Long.valueOf(fields[1]), fields[2], Integer.parseInt(fields[3]),
                    Instant.ofEpochMilli(Long.parseLong(fields[4])));
        }
    }

    /** 沿用发送冷却、号码额度和入口保护；未绑定号码不会注册账号或收到短信。 */
    @Override
    public void sendCode(String phone, HttpServletRequest request) {
        delivery.send(phone, PURPOSE, request.getRemoteAddr(), false);
    }

    /** 重新核验会替换当前浏览器的旧票据。 */
    @Override
    public PasswordResetVerifyVO verify(String phone, String code, HttpServletRequest request) {
        loginLimiter.check(request.getRemoteAddr());
        String browserId = BrowserFlow.requireId(request);
        String digest = phones.digest(phone);
        if (!challenges.consume(digest, PURPOSE, phones.codeMac(PURPOSE, digest, code)))
            throw new BusinessException(401, "验证码无效或已过期");
        Long userId = bindings.findUserId(digest);
        UserAccount account = userId == null ? null : accounts.selectById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE)
            throw new BusinessException(401, "验证码无效或已过期");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var grant = new Grant(userId, digest, account.getAuthVersion(), clock.instant().plus(GRANT_TTL));
        redis.opsForValue().set(grantKey(browserId), grant.encode(limiter.digest(ticket)), GRANT_TTL);
        return new PasswordResetVerifyVO(ticket, GRANT_TTL.toSeconds());
    }

    /**
     * 按账号加行锁后再次核对绑定和版本，防止验证后换绑或两份并发请求重复改密。
     * 密码与版本在同一事务中更新；Redis 不可用时先失败，保留原密码。
     */
    @Override
    @Transactional(timeout = 30)
    public void reset(String ticket, String newPassword, HttpServletRequest request) {
        loginLimiter.check(request.getRemoteAddr());
        String browserId = BrowserFlow.currentId(request);
        if (browserId == null)
            throw invalidGrant();
        String key = grantKey(browserId);
        String stored = redis.opsForValue().get(key);
        Grant grant = Grant.read(stored, limiter.digest(ticket));

        UserAccount account = accounts.lockById(grant.userId());
        if (!clock.instant().isBefore(grant.expiresAt()))
            throw invalidGrant();
        if (account == null || account.getStatus() != AccountStatus.ACTIVE
                || account.getAuthVersion() != grant.authVersion()
                || !grant.userId().equals(bindings.findUserId(grant.phoneDigest())))
            throw invalidGrant();
        if (passwords.matches(newPassword, account.getPasswordHash()))
            throw new BusinessException(400, "新密码无效或与原密码相同");

        // 等待行锁期间可能签发了新票据，不能误删新票据或接受旧票据。
        Long consumed = redis.execute(CONSUME_GRANT, List.of(key), stored);
        if (consumed == null)
            throw new BusinessException(503, "重设凭证服务暂时不可用");
        if (consumed != 1)
            throw invalidGrant();
        String hash = passwords.encode(newPassword);
        authCache.blockUntilTransactionEnds(grant.userId(),
                new AccountAuthCache.State(AccountStatus.ACTIVE, grant.authVersion() + 1));
        if (accounts.changePassword(grant.userId(), grant.authVersion(), hash) != 1)
            throw new BusinessException(409, "账号已变更，请重新验证");
        // 找回另一个账号时，当前登录身份也随成功重设而退出。
        Long current = SessionUser.currentId(request);
        if (current != null && !grant.userId().equals(current))
            jwt.revokeCurrent(request);
        BusinessEventLog.committed("user", "password_reset", "user", grant.userId());
    }

    private String grantKey(String browserId) {
        return GRANT_PREFIX + limiter.digest(browserId);
    }

    private static BusinessException invalidGrant() {
        return new BusinessException(401, "重设凭证无效或已过期");
    }
}
