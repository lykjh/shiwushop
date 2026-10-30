package com.shiwushop.account.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.account.auth.AuthRiskLimiter;
import com.shiwushop.account.auth.LoginRateLimiter;
import com.shiwushop.account.dto.user.LoginDTO;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.service.LoginService;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.captcha.CaptchaService;
import com.shiwushop.common.logging.BusinessEventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {
    private final UserAccountMapper userAccountMapper;
    private final AccountAccess accountAccess;
    private final LoginRateLimiter loginRateLimiter;
    private final AuthRiskLimiter authRiskLimiter;
    private final CaptchaService captchaService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 检查登录入口额度与风险，达到挑战条件时验证当前 Session 的滑块凭证。
     * 账号或密码错误才记为失败；成功后清除失败状态、轮换 Session ID 并写入身份。
     */
    @Override
    public LoginVO login(LoginDTO dto, HttpServletRequest request) {
        loginRateLimiter.check(request.getRemoteAddr());
        HttpSession session = request.getSession(true);
        boolean captchaRequired = authRiskLimiter.loginRequiresCaptcha(dto.getUsername(), request);
        String proof = dto.getCaptchaProof();
        if (proof == null || proof.isBlank()) {
            if (captchaRequired)
                throw new BusinessException(403, "请先完成滑块验证",
                        Map.of("state", "CAPTCHA_REQUIRED"), null);
        } else {
            // 提交的凭证必须属于当前 Session，且只能使用一次。
            captchaService.consumeLoginProof(proof, session.getId());
        }
        LoginVO loginVO;
        try {
            loginVO = authenticate(dto);
        } catch (BusinessException e) {
            if (e.getCode() == 401) authRiskLimiter.recordLoginFailure(dto.getUsername(), request);
            throw e;
        }
        // 换 Session ID 前清除旧 Session 对应的失败计数。
        authRiskLimiter.recordLoginSuccess(dto.getUsername(), request);
        request.changeSessionId();
        session.setAttribute("userId", loginVO.getId());
        session.setAttribute("authVersion", accountAccess.requireActive(loginVO.getId()).getAuthVersion());
        BusinessEventLog.committed("account", "login", "user", loginVO.getId());
        return loginVO;
    }

    /** 账号不存在、停用或密码错误统一返回 401，避免暴露账号状态。 */
    private LoginVO authenticate(LoginDTO dto) {
        UserAccount account = userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, dto.getUsername()));
        if (account == null || account.getStatus() != AccountStatus.ACTIVE
                || dto.getPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !encoder.matches(dto.getPassword(), account.getPasswordHash()))
            throw new BusinessException(401, "账号或密码错误");
        return new LoginVO(account.getUserId(), account.getUsername());
    }
}
