package com.shiwushop.account.service.impl;

import com.shiwushop.account.auth.PhoneLookup;
import com.shiwushop.account.auth.SmsChallengeStore;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.mapper.user.PhoneBindingMapper;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.service.SmsSender;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.RateLimiter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 统一执行短信入口限额、挑战预留、发送与失败回滚。 */
@Component
@RequiredArgsConstructor
class SmsCodeDelivery {
    private static final Logger log = LoggerFactory.getLogger(SmsCodeDelivery.class);
    private final SecureRandom random = new SecureRandom();
    private final PhoneLookup phones;
    private final PhoneBindingMapper bindings;
    private final UserAccountMapper accounts;
    private final RateLimiter limiter;
    private final SmsChallengeStore challenges;
    private final SmsSender sender;

    @Value("${shop.sms.ip-tokens-per-second:2}") private long ipTokensPerSecond;
    @Value("${shop.sms.ip-burst:20}") private long ipBurst;
    @Value("${shop.sms.entry-global-tokens-per-second:1}") private long entryGlobalRate;
    @Value("${shop.sms.entry-global-burst:20}") private long entryGlobalBurst;

    @PostConstruct
    void validateLimits() {
        if (ipTokensPerSecond < 1 || ipBurst < 1 || entryGlobalRate < 1 || entryGlobalBurst < 1)
            throw new IllegalArgumentException("短信入口限额参数无效");
    }

    /** 渠道失败时删除预留挑战；发送额度仍视为已使用，避免反复冲击渠道。 */
    void send(String phone, String purpose, String remoteAddress, boolean binding) {
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
        if ("entry".equals(purpose)) {
            long globalRetry;
            try {
                globalRetry = limiter.tokenBucketRetrySeconds("shiwushop:sms:entry:global",
                        entryGlobalRate, entryGlobalBurst);
            } catch (RuntimeException e) {
                challenges.cancelBeforeSend(digest, purpose, token);
                throw e;
            }
            if (globalRetry > 0) {
                challenges.cancelBeforeSend(digest, purpose, token);
                throw new BusinessException(429, "验证码发送过于频繁",
                        Map.of("retryAfterSeconds", globalRetry), null);
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
