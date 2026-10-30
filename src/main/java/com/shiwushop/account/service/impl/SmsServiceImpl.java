package com.shiwushop.account.service.impl;

import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.account.auth.AccountAuthCache;
import com.shiwushop.account.auth.LoginRateLimiter;
import com.shiwushop.account.auth.PhoneLookup;
import com.shiwushop.account.auth.SmsChallengeStore;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.mapper.user.PhoneBindingMapper;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.privacy.PrivacyCipher;
import com.shiwushop.account.service.SmsSender;
import com.shiwushop.account.service.SmsService;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.RateLimiter;
import com.shiwushop.common.logging.BusinessEventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SmsServiceImpl implements SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsServiceImpl.class);
    private static final String BIND = "bind";
    private static final String LOGIN = "login";
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final PhoneLookup phones;
    private final PhoneBindingMapper bindings;
    private final UserAccountMapper accounts;
    private final AccountAccess accountAccess;
    private final AccountAuthCache authCache;
    private final PrivacyCipher privacy;
    private final RateLimiter limiter;
    private final LoginRateLimiter loginRateLimiter;
    private final SmsChallengeStore challenges;
    private final SmsSender sender;

    @Value("${shop.sms.ip-tokens-per-second:2}") private long ipTokensPerSecond;
    @Value("${shop.sms.ip-burst:20}") private long ipBurst;

    @PostConstruct
    void validateLimits() {
        if (ipTokensPerSecond < 1 || ipBurst < 1) throw new IllegalArgumentException("短信入口限额参数无效");
    }

    /** 已登录用户才能请求绑定码；验证码仍需与当前密码一起提交。 */
    @Override
    public void sendBindCode(Long userId, String phone, String remoteAddress) {
        accountAccess.checkActive(userId);
        send(phone, BIND, remoteAddress, true);
    }

    /** 用当前密码和一次性绑定码更新唯一手机号绑定，提交后使旧会话失效。 */
    @Override
    @Transactional(timeout = 30)
    public void bind(Long userId, String currentPassword, String phone, String code) {
        UserAccount account = accounts.lockById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE)
            throw new BusinessException(401, "请重新登录");
        if (!passwords.matches(currentPassword, account.getPasswordHash()))
            throw new BusinessException(401, "当前密码错误");
        String digest = phones.digest(phone);
        if (!challenges.consume(digest, BIND, phones.codeMac(BIND, digest, code))) {
            log.info("sms purpose=bind outcome=invalid_code");
            throw new BusinessException(401, "验证码无效或已过期");
        }

        byte[] ciphertext = privacy.encrypt(userId, "login-phone", phone);
        authCache.blockUntilTransactionEnds(userId,
                new AccountAuthCache.State(AccountStatus.ACTIVE, account.getAuthVersion() + 1));
        try {
            if (bindings.lockByUserId(userId) == null) bindings.insert(userId, ciphertext, digest);
            else bindings.update(userId, ciphertext, digest);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "手机号已绑定其他账号");
        }
        if (accounts.bumpAuthVersion(userId, account.getAuthVersion()) != 1)
            throw new BusinessException(409, "账号已变更，请重试");
        BusinessEventLog.committed("account", "phone_bound", "user", userId);
    }

    /** 登录码只发送给已绑定且启用的账号；未知号码也收到相同的接口响应。 */
    @Override
    public void sendLoginCode(String phone, String remoteAddress) {
        send(phone, LOGIN, remoteAddress, false);
    }

    /** 一次性消费登录码，再按现有密码登录方式轮换并建立 Session。 */
    @Override
    public LoginVO login(String phone, String code, HttpServletRequest request) {
        loginRateLimiter.check(request.getRemoteAddr());
        String digest = phones.digest(phone);
        if (!challenges.consume(digest, LOGIN, phones.codeMac(LOGIN, digest, code))) {
            log.info("sms purpose=login outcome=invalid_code");
            throw new BusinessException(401, "验证码无效或已过期");
        }
        Long userId = bindings.findUserId(digest);
        if (userId == null) throw new BusinessException(401, "验证码无效或已过期");
        UserAccount account = accountAccess.requireActive(userId);
        AccountAuthCache.State state = authCache.current(userId);
        if (state == null || state.status() != AccountStatus.ACTIVE
                || state.version() != account.getAuthVersion())
            throw new BusinessException(503, "账号校验暂不可用");
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute("userId", userId);
        session.setAttribute("authVersion", account.getAuthVersion());
        BusinessEventLog.committed("account", "sms_login", "user", userId);
        return new LoginVO(userId, account.getUsername());
    }

    /** 先原子保留额度和待确认挑战；渠道拒绝时删除挑战，保留已使用的发送额度。 */
    private void send(String phone, String purpose, String remoteAddress, boolean binding) {
        if (!sender.configured()) throw new BusinessException(503, "短信渠道未配置");
        long retry = limiter.tokenBucketRetrySeconds("shiwushop:sms:send:ip:" + limiter.digest(remoteAddress),
                ipTokensPerSecond, ipBurst);
        if (retry > 0) throw new BusinessException(429, "验证码发送过于频繁",
                Map.of("retryAfterSeconds", retry), null);
        String digest = phones.digest(phone);
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String token = UUID.randomUUID().toString();
        challenges.reserve(digest, purpose, token, phones.codeMac(purpose, digest, code));

        if (!binding) {
            Long userId = bindings.findUserId(digest);
            UserAccount account = userId == null ? null : accounts.selectById(userId);
            if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
                challenges.abort(digest, purpose, token);
                log.info("sms purpose={} outcome=not_eligible", purpose);
                return;
            }
        }
        try {
            sender.send(phone, code);
            challenges.activate(digest, purpose, token);
            log.info("sms purpose={} outcome=sent", purpose);
        } catch (RuntimeException e) {
            challenges.abort(digest, purpose, token);
            log.warn("sms purpose={} outcome=delivery_unconfirmed error={}", purpose,
                    e.getClass().getSimpleName());
            throw e;
        }
    }
}
