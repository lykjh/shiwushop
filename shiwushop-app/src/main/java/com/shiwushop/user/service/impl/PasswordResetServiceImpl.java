package com.shiwushop.user.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.RateLimiter;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.user.auth.AccountAuthCache;
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
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/** 复用短信挑战和 Redis Session；账号行锁与鉴权版本共同裁决并发重设。 */
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {
    private static final String PURPOSE = "password-reset";
    private static final String GRANT = "passwordResetGrant";
    private final PhoneLookup phones;
    private final PhoneBindingMapper bindings;
    private final UserAccountMapper accounts;
    private final SmsChallengeStore challenges;
    private final SmsCodeDelivery delivery;
    private final LoginRateLimiter loginLimiter;
    private final RateLimiter limiter;
    private final AccountAuthCache authCache;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    /** Session 存票据摘要而非原文；手机号也只保留 HMAC，支持 Redis Session 跨实例存取。 */
    private record Grant(Long userId, String phoneDigest, int authVersion,
                         String ticketDigest, Instant expiresAt) implements Serializable {}

    /** 沿用发送冷却、号码额度和入口保护；未绑定号码不会注册账号或收到短信。 */
    @Override
    public void sendCode(String phone, HttpServletRequest request) {
        delivery.send(phone, PURPOSE, request.getRemoteAddr(), false);
    }

    /** 一次性核验找回码后，保存目标账号及当前版本；重新核验会替换同 Session 的旧票据。 */
    @Override
    public PasswordResetVerifyVO verify(String phone, String code, HttpServletRequest request) {
        loginLimiter.check(request.getRemoteAddr());
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
        request.getSession(true).setAttribute(GRANT, new Grant(userId, digest, account.getAuthVersion(),
                limiter.digest(ticket), clock.instant().plusSeconds(300)));
        return new PasswordResetVerifyVO(ticket, 300);
    }

    /**
     * 按账号加行锁后再次核对绑定和版本，防止验证后换绑或两份并发请求重复改密。
     * 密码与版本在同一事务中更新；Redis 不可用时先失败，保留原密码。
     */
    @Override
    @Transactional(timeout = 30)
    public void reset(String ticket, String newPassword, HttpServletRequest request) {
        loginLimiter.check(request.getRemoteAddr());
        HttpSession session = request.getSession(false);
        Grant grant = session == null ? null : (Grant) session.getAttribute(GRANT);
        if (grant == null || !grant.ticketDigest().equals(limiter.digest(ticket)))
            throw new BusinessException(401, "重设凭证无效或已过期");

        UserAccount account = accounts.lockById(grant.userId());
        if (!clock.instant().isBefore(grant.expiresAt()))
            throw new BusinessException(401, "重设凭证无效或已过期");
        if (account == null || account.getStatus() != AccountStatus.ACTIVE
                || account.getAuthVersion() != grant.authVersion()
                || !grant.userId().equals(bindings.findUserId(grant.phoneDigest())))
            throw new BusinessException(401, "重设凭证无效或已过期");
        if (passwords.matches(newPassword, account.getPasswordHash()))
            throw new BusinessException(400, "新密码无效或与原密码相同");

        // 并发请求即使已读到同一 Session 快照，也只有版本匹配的首个事务能成功。
        session.removeAttribute(GRANT);
        String hash = passwords.encode(newPassword);
        authCache.blockUntilTransactionEnds(grant.userId(),
                new AccountAuthCache.State(AccountStatus.ACTIVE, grant.authVersion() + 1));
        if (accounts.changePassword(grant.userId(), grant.authVersion(), hash) != 1)
            throw new BusinessException(409, "账号已变更，请重新验证");
        BusinessEventLog.committed("user", "password_reset", "user", grant.userId());
    }
}
