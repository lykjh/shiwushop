package com.shiwushop.account.service.impl;

import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.account.auth.AccountAuthCache;
import com.shiwushop.account.auth.LoginRateLimiter;
import com.shiwushop.account.auth.PhoneLookup;
import com.shiwushop.account.auth.SmsChallengeStore;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.mapper.user.PhoneBindingMapper;
import com.shiwushop.account.service.SmsLoginService;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 一次性验证码登录，沿用账号状态检查与 Session 鉴权版本。 */
@Service
@RequiredArgsConstructor
public class SmsLoginServiceImpl implements SmsLoginService {
    private static final Logger log = LoggerFactory.getLogger(SmsLoginServiceImpl.class);
    private static final String LOGIN = "login";
    private final PhoneLookup phones;
    private final PhoneBindingMapper bindings;
    private final AccountAccess accountAccess;
    private final AccountAuthCache authCache;
    private final LoginRateLimiter loginRateLimiter;
    private final SmsChallengeStore challenges;
    private final SmsCodeDelivery delivery;

    /** 未绑定号码不发送短信，但接口仍返回普通成功响应。 */
    @Override
    public void sendCode(String phone, String remoteAddress) {
        delivery.send(phone, LOGIN, remoteAddress, false);
    }

    /** 消费验证码后轮换 Session ID，防止旧验证码复用或固定会话。 */
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
}
