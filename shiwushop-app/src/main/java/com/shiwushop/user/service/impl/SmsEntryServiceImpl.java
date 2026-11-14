package com.shiwushop.user.service.impl;

import com.shiwushop.user.auth.LoginRateLimiter;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.user.auth.JwtLogin;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.user.auth.PhoneLookup;
import com.shiwushop.user.auth.PhoneRegistrationTicketStore;
import com.shiwushop.user.auth.SmsChallengeStore;
import com.shiwushop.user.mapper.user.PhoneBindingMapper;
import com.shiwushop.user.service.SmsEntryService;
import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.user.vo.user.SmsEntryVerifyVO;
import com.shiwushop.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 手机号首次使用和已绑定登录的统一入口。 */
@Service
@RequiredArgsConstructor
public class SmsEntryServiceImpl implements SmsEntryService {
    /** 统一短信登录及首次注册入口的验证码用途标识。 */
    private static final String ENTRY = "entry";
    /** 本类日志记录器，供业务事件或故障诊断使用。 */
    private static final Logger log = LoggerFactory.getLogger(SmsEntryServiceImpl.class);
    /** 计算手机号查找摘要并定位已绑定账号。 */
    private final PhoneLookup phones;
    /** 保存手机号密文和用于查找已绑定账号的摘要。 */
    private final PhoneBindingMapper bindings;
    /** 保存短信验证码挑战、发码冷却和核验次数。 */
    private final SmsChallengeStore challenges;
    /** 管理手机号验证后供首次注册使用的短期票据。 */
    private final PhoneRegistrationTicketStore tickets;
    /** 检查发码预算并向短信渠道发出验证码请求。 */
    private final SmsCodeDelivery delivery;
    /** 账号状态、角色检查和经营者资格。 */
    private final AccountAccess accountAccess;
    private final JwtLogin jwt;
    /** 为已验证的未绑定手机号创建并绑定新账号。 */
    private final PhoneAccountCreator creator;
    /** 为自动创建的账号生成候选用户名。 */
    private final RandomUsername usernames;
    /** 检查登录入口是否还有可用请求令牌。 */
    private final LoginRateLimiter loginLimiter;
    private final BrowserFlow browserFlow;
    /** 以 BCrypt 保存密码哈希并校验提交的密码。 */
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    /** 新入口对未绑定手机号也实际发送验证码，另设全站短信发送预算。 */
    @Override
    public long sendCode(String phone, HttpServletRequest request) {
        requireAnonymous(request);
        return delivery.send(phone, ENTRY, request.getRemoteAddr(), true);
    }

    /** 验证码仅消费一次；已有账号登录，首次使用则签发与当前浏览器绑定的票据。 */
    @Override
    public SmsEntryVerifyVO verify(String phone, String code, HttpServletRequest request, HttpServletResponse response) {
        requireAnonymous(request);
        loginLimiter.check(request.getRemoteAddr());
        String browserId = BrowserFlow.requireId(request);
        String digest = phones.digest(phone);
        if (!challenges.consume(digest, ENTRY, phones.codeMac(ENTRY, digest, code))) {
            log.info("sms purpose=entry outcome=invalid_code");
            throw new BusinessException(401, "验证码无效或已过期");
        }
        Long userId = bindings.findUserId(digest);
        if (userId != null)
            return SmsEntryVerifyVO.loggedIn(completeLogin(userId, request, response));
        String ticket = tickets.issue(digest, browserId);
        log.info("sms purpose=entry outcome=password_required");
        return SmsEntryVerifyVO.setPassword(ticket);
    }

    /** 票据消费后逐次尝试随机账号，唯一索引负责并发冲突的最终裁决。 */
    @Override
    public LoginVO register(String phone, String ticket, String password, HttpServletRequest request,
                            HttpServletResponse response) {
        String browserId = BrowserFlow.currentId(request);
        if (browserId == null || SessionUser.currentId(request) != null)
            throw new BusinessException(401, "注册凭证无效或已过期");
        String digest = phones.digest(phone);
        tickets.consume(ticket, digest, browserId);
        String hash = passwords.encode(password);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                LoginVO created = creator.create(usernames.next(), hash, phone, digest);
                return completeLogin(created.getId(), request, response);
            } catch (DuplicateKeyException e) {
                if (bindings.findUserId(digest) != null)
                    throw new BusinessException(409, "手机号已绑定其他账号", e);
                if (attempt == 2)
                    throw new BusinessException(409, "账号生成冲突，请重试", e);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private LoginVO completeLogin(Long userId, HttpServletRequest request, HttpServletResponse response) {
        UserAccount account = accountAccess.requireActive(userId);
        jwt.start(userId, account.getAuthVersion(), request, response);
        browserFlow.rotate(response);
        BusinessEventLog.committed("user", "sms_login", "user", userId);
        return new LoginVO(userId, account.getUsername());
    }

    private void requireAnonymous(HttpServletRequest request) {
        if (SessionUser.currentId(request) != null)
            throw new BusinessException(409, "当前已登录，请使用手机号绑定功能");
    }
}
