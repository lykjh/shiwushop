package com.shiwushop.account.service.impl;

import com.shiwushop.account.auth.AuthRiskLimiter;
import com.shiwushop.account.auth.RegisterRateLimiter;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.entity.status.AccountRole;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.entity.user.UserProfile;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.mapper.user.UserProfileMapper;
import com.shiwushop.account.service.RegisterService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaService;
import com.shiwushop.common.logging.BusinessEventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RegisterServiceImpl implements RegisterService {
    private final UserProfileMapper userProfileMapper;
    private final UserAccountMapper userAccountMapper;
    private final RegisterRateLimiter registerRateLimiter;
    private final AuthRiskLimiter authRiskLimiter;
    private final CaptchaService captchaService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 注册入口先过流量保护和滑块验证，再在同一事务中创建用户资料与账号。
     * 只有滑块凭证无效才增加注册风险失败数；用户名冲突和密码格式错误不计入。
     */
    @Override
    @Transactional
    public void register(RegisterDTO dto, HttpServletRequest request) {
        registerRateLimiter.check(request.getRemoteAddr());
        HttpSession session = request.getSession(true);
        authRiskLimiter.registerRequiresCaptcha(request);
        String proof = dto.getCaptchaProof();
        if (proof == null || proof.isBlank()) {
            throw new BusinessException(403, "请先完成滑块验证", Map.of("state", "CAPTCHA_REQUIRED"), null);
        }
        try {
            captchaService.consumeLoginProof(proof, session.getId());
        } catch (BusinessException e) {
            if (e.getCode() == 400) authRiskLimiter.recordRegisterFailure(request);
            throw e;
        }
        // 密码格式和用户名冲突不属于滑块失败，不增加风控失败次数。
        realRegister(dto);
    }

    /**
     * 写入用户资料及 BCrypt 密码哈希，不保存明文密码。
     * 并发用户名冲突交给数据库唯一约束裁决，异常转换为稳定的 409 响应。
     */
    void realRegister(RegisterDTO dto) {
        if (dto.getPassword().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BusinessException(400, "密码不得超过72字节");
        UserProfile profile = new UserProfile();
        userProfileMapper.insertEmpty(profile);
        UserAccount account = new UserAccount();
        account.setUserId(profile.getId());
        account.setUsername(dto.getUsername());
        account.setPasswordHash(encoder.encode(dto.getPassword()));
        account.setRole(AccountRole.USER);
        account.setStatus(AccountStatus.ACTIVE);
        try {
            userAccountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "账号已存在", e);
        }
        BusinessEventLog.committed("account", "register", "user", account.getUserId());
    }
}
