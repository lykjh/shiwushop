package com.shiwushop.user.service.impl;

import com.shiwushop.user.auth.LoginRateLimiter;
import com.shiwushop.user.auth.AccountAuthCache;
import com.shiwushop.user.service.AccountAccess;
import com.shiwushop.user.entity.status.AccountStatus;
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
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;

/** 手机号首次使用和已绑定登录的统一入口。 */
@Service
@RequiredArgsConstructor
public class SmsEntryServiceImpl implements SmsEntryService {
    /** 统一短信登录及首次注册入口的验证码用途标识。 */
    private static final String ENTRY = "entry";
    /** 本类日志记录器，供业务事件或故障诊断使用。 */
    private static final Logger log = LoggerFactory.getLogger(SmsEntryServiceImpl.class);
    private final PhoneLookup phones;
    private final PhoneBindingMapper bindings;
    private final SmsChallengeStore challenges;
    private final PhoneRegistrationTicketStore tickets;
    private final SmsCodeDelivery delivery;
    private final AccountAccess accountAccess;
    private final AccountAuthCache authCache;
    private final PhoneAccountCreator creator;
    private final RandomUsername usernames;
    private final LoginRateLimiter loginLimiter;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    /** 新入口对未绑定手机号也实际发送验证码，另设全站短信发送预算。 */
    @Override
    public void sendCode(String phone, HttpServletRequest request) {
        requireAnonymous(request);
        delivery.send(phone, ENTRY, request.getRemoteAddr(), true);
    }

    /** 验证码仅消费一次；已有账号登录，首次使用则签发与当前 Session 绑定的票据。 */
    @Override
    public SmsEntryVerifyVO verify(String phone, String code, HttpServletRequest request) {
        requireAnonymous(request);
        loginLimiter.check(request.getRemoteAddr());
        String digest = phones.digest(phone);
        if (!challenges.consume(digest, ENTRY, phones.codeMac(ENTRY, digest, code))) {
            log.info("sms purpose=entry outcome=invalid_code");
            throw new BusinessException(401, "验证码无效或已过期");
        }
        Long userId = bindings.findUserId(digest);
        if (userId != null)
            return SmsEntryVerifyVO.loggedIn(completeLogin(userId, request));
        HttpSession session = request.getSession(true);
        String ticket = tickets.issue(digest, session.getId());
        log.info("sms purpose=entry outcome=password_required");
        return SmsEntryVerifyVO.setPassword(ticket);
    }

    /** 票据消费后逐次尝试随机账号，唯一索引负责并发冲突的最终裁决。 */
    @Override
    public LoginVO register(String phone, String ticket, String password, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") != null)
            throw new BusinessException(401, "注册凭证无效或已过期");
        String digest = phones.digest(phone);
        tickets.consume(ticket, digest, session.getId());
        String hash = passwords.encode(password);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                LoginVO created = creator.create(usernames.next(), hash, phone, digest);
                return completeLogin(created.getId(), request);
            } catch (DuplicateKeyException e) {
                if (bindings.findUserId(digest) != null)
                    throw new BusinessException(409, "手机号已绑定其他账号", e);
                if (attempt == 2)
                    throw new BusinessException(409, "账号生成冲突，请重试", e);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** 写登录态前核对数据库与缓存版本，避免账号变更期间建立立即失效的会话。 */
    private LoginVO completeLogin(Long userId, HttpServletRequest request) {
        UserAccount account = accountAccess.requireActive(userId);
        AccountAuthCache.State state = authCache.current(userId);
        if (state == null || state.status() != AccountStatus.ACTIVE
                || state.version() != account.getAuthVersion())
            throw new BusinessException(503, "账号校验暂不可用");
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute("userId", userId);
        session.setAttribute("authVersion", account.getAuthVersion());
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, userId.toString());
        session.setAttribute("sessionRef", UUID.randomUUID().toString());
        BusinessEventLog.committed("user", "sms_login", "user", userId);
        return new LoginVO(userId, account.getUsername());
    }

    private void requireAnonymous(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null)
            throw new BusinessException(409, "当前已登录，请使用手机号绑定功能");
    }
}
