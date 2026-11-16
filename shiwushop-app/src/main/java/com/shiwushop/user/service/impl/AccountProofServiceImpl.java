package com.shiwushop.user.service.impl;

import com.shiwushop.user.service.AccountProofService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.user.auth.PhoneLookup;
import com.shiwushop.user.auth.SmsChallengeStore;
import com.shiwushop.user.mapper.user.PhoneBindingMapper;
import com.shiwushop.user.service.AccountAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AccountProofServiceImpl implements AccountProofService {
    private final AccountAccess access;
    private final PhoneBindingMapper bindings;
    private final PrivacyCipher privacy;
    private final PhoneLookup phones;
    private final SmsChallengeStore challenges;
    private final SmsCodeDelivery delivery;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    @Override
    public long sendCode(Long userId, String purpose, String remoteAddress) {
        String phone = boundPhone(userId);
        return delivery.send(phone, purpose, remoteAddress, true);
    }

    @Override
    public void verify(Long userId, String purpose, String password, String code) {
        if ((password == null || password.isBlank()) == (code == null || code.isBlank()))
            throw new BusinessException(400, "请选择一种身份验证方式");
        if (password != null && !password.isBlank()) {
            if (password.getBytes(StandardCharsets.UTF_8).length > 72
                    || !passwords.matches(password, access.requireActive(userId).getPasswordHash()))
                throw new BusinessException(401, "当前密码错误");
            return;
        }
        if (code == null || !code.matches("[0-9]{6}"))
            throw new BusinessException(400, "验证码格式错误");
        String phone = boundPhone(userId);
        String digest = phones.digest(phone);
        if (!challenges.consume(digest, purpose, phones.codeMac(purpose, digest, code)))
            throw new BusinessException(401, "验证码无效或已过期");
    }

    private String boundPhone(Long userId) {
        access.requireActive(userId);
        PhoneBindingMapper.PhonePrivate bound = bindings.findPhone(userId);
        if (bound == null || bound.getCiphertext() == null)
            throw new BusinessException(409, "请先绑定手机号");
        return privacy.decrypt(userId, "login-phone", bound.getKeyVersion(), bound.getCiphertext());
    }
}
