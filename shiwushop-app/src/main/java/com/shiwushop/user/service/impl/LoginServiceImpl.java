package com.shiwushop.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.user.auth.AuthRiskLimiter;
import com.shiwushop.user.auth.JwtLogin;
import com.shiwushop.user.auth.LoginRateLimiter;
import com.shiwushop.user.dto.user.LoginDTO;
import com.shiwushop.user.entity.status.AccountStatus;
import com.shiwushop.user.entity.user.UserAccount;
import com.shiwushop.user.mapper.user.UserAccountMapper;
import com.shiwushop.user.service.LoginService;
import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.captcha.CaptchaService;
import com.shiwushop.common.logging.BusinessEventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {
    /** 持久化登录账号状态、密码哈希和会话鉴权版本。 */
    private final UserAccountMapper userAccountMapper;
    private final JwtLogin jwt;
    /** 检查登录入口是否还有可用请求令牌。 */
    private final LoginRateLimiter loginRateLimiter;
    /** 按账号、会话与共享出口的风险决定挑战或限制。 */
    private final AuthRiskLimiter authRiskLimiter;
    /** 提供滑块图像、轨迹验证和一次性凭证消费。 */
    private final CaptchaService captchaService;
    private final BrowserFlow browserFlow;
    /** 以 BCrypt 保存密码哈希并校验提交的密码。 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 检查登录入口额度与风险，必要时验证当前浏览器的滑块凭证。
     * 账号或密码错误才记为失败；成功后清除失败状态、签发 JWT 并轮换浏览器标识。
     */
    @Override
    public LoginVO login(LoginDTO dto, HttpServletRequest request, HttpServletResponse response) {
        loginRateLimiter.check(request.getRemoteAddr());
        String browserId = BrowserFlow.requireId(request);
        boolean captchaRequired = authRiskLimiter.loginRequiresCaptcha(dto.getUsername(), request);
        String proof = dto.getCaptchaProof();
        if (proof == null || proof.isBlank()) {
            if (captchaRequired)
                throw new BusinessException(403, "请先完成滑块验证",
                        Map.of("state", "CAPTCHA_REQUIRED"), null);
        } else {
            captchaService.consumeLoginProof(proof, browserId);
        }
        UserAccount account;
        try {
            account = authenticate(dto);
        } catch (BusinessException e) {
            if (e.getCode() == 401)
                authRiskLimiter.recordLoginFailure(dto.getUsername(), request);
            throw e;
        }
        authRiskLimiter.recordLoginSuccess(dto.getUsername(), request);
        jwt.start(account.getUserId(), account.getAuthVersion(), request, response);
        browserFlow.rotate(response);
        BusinessEventLog.committed("user", "login", "user", account.getUserId());
        return new LoginVO(account.getUserId(), account.getUsername());
    }

    /** 账号不存在、停用或密码错误统一返回 401，避免暴露账号状态。 */
    private UserAccount authenticate(LoginDTO dto) {
        UserAccount account = userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, dto.getUsername()));
        if (account == null || account.getStatus() != AccountStatus.ACTIVE
                || dto.getPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !encoder.matches(dto.getPassword(), account.getPasswordHash()))
            throw new BusinessException(401, "账号或密码错误");
        return account;
    }
}
