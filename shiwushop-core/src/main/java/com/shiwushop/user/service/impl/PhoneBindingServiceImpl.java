package com.shiwushop.user.service.impl;

import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.auth.AccountAuthCache;
import com.shiwushop.user.auth.PhoneLookup;
import com.shiwushop.user.auth.SmsChallengeStore;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.PhoneBindingMapper;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.user.service.PhoneBindingService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 绑定手机号时验证当前密码和一次性验证码，再更新唯一绑定。 */
@Service
@RequiredArgsConstructor
public class PhoneBindingServiceImpl implements PhoneBindingService {
    private static final Logger log = LoggerFactory.getLogger(PhoneBindingServiceImpl.class);
    private static final String BIND = "bind";
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final PhoneLookup phones;
    private final PhoneBindingMapper bindings;
    private final UserAccountMapper accounts;
    private final AccountAccess accountAccess;
    private final AccountAuthCache authCache;
    private final PrivacyCipher privacy;
    private final SmsChallengeStore challenges;
    private final SmsCodeDelivery delivery;

    /** 绑定码仅供已登录的有效账号申请。 */
    @Override
    public void sendCode(Long userId, String phone, String remoteAddress) {
        accountAccess.checkActive(userId);
        delivery.send(phone, BIND, remoteAddress, true);
    }

    /** 在同一事务中更新绑定和鉴权版本，使原有会话失效。 */
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
        BusinessEventLog.committed("user", "phone_bound", "user", userId);
    }
}
