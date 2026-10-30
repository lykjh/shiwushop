package com.shiwushop.account.service.impl;

import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.account.auth.AccountAuthCache;
import com.shiwushop.account.auth.AuthRiskLimiter;
import com.shiwushop.account.auth.LoginRateLimiter;
import com.shiwushop.account.auth.RegisterRateLimiter;

import com.shiwushop.common.auth.*;
import com.shiwushop.account.dto.user.LoginDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.common.captcha.CaptchaService;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.account.vo.user.UserInfoVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shiwushop.common.BusinessException;
import com.shiwushop.account.entity.user.UserAccount;
import com.shiwushop.account.entity.user.UserProfile;
import com.shiwushop.account.entity.status.AccountRole;
import com.shiwushop.account.entity.status.AccountStatus;
import com.shiwushop.account.mapper.user.UserAccountMapper;
import com.shiwushop.account.mapper.user.UserProfileMapper;
import com.shiwushop.account.service.UserService;
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
public class UserServiceImpl implements UserService {
    private final UserProfileMapper userProfileMapper;
    private final UserAccountMapper userAccountMapper;
    private final AccountAccess accountAccess;
    private final AccountAuthCache authCache;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    private final RegisterRateLimiter registerRateLimiter;
    private final LoginRateLimiter loginRateLimiter;
    private final AuthRiskLimiter authRiskLimiter;
    private final CaptchaService captchaService;

    /**
     * 执行注册入口的资源保护和滑块验证，然后在事务内创建账号。
     * 注册必须携带当前 Session 的一次性凭证；只有无效凭证才计入注册风控失败。
     */
    @Override
    @Transactional
    public void register(RegisterDTO dto, HttpServletRequest request){
        registerRateLimiter.check(request.getRemoteAddr());
        HttpSession session =request.getSession(true);
        authRiskLimiter.registerRequiresCaptcha(request);
        String proof =dto.getCaptchaProof();

        if(proof==null||proof.isBlank()){
            // 注册始终要求当前 Session 的一次性滑块凭证；风险规则仍可提前阻断高频滥用。
            throw new BusinessException(403,"请先完成滑块验证", Map.of("state","CAPTCHA_REQUIRED"),null);
        }else {
            try{
                captchaService.consumeLoginProof(proof,session.getId());
            }catch (BusinessException e){
                if(e.getCode()==400)authRiskLimiter.recordRegisterFailure(request);

                throw e;
            }
        }

        // 密码格式和用户名冲突不属于滑块凭证失败，不增加风控失败次数。
        realRegister(dto);
    }
    /**
     * 创建用户资料与账号记录，并以 BCrypt 保存密码哈希。
     * 数据库唯一约束负责处理并发使用同一用户名的情况；冲突转换为 409 响应。
     */
    void realRegister(RegisterDTO dto){
        //注册业务
        if (dto.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(400, "密码不得超过72字节");
        }
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
            throw new BusinessException(409, "账号已存在",e);
        }
        BusinessEventLog.committed("account", "register", "user", account.getUserId());
    }

    /**
     * 检查登录入口额度和风险状态；风险要求滑块时校验当前 Session 的凭证。
     * 仅账号或密码错误累计失败次数；成功后清除失败状态、轮换 Session ID 并写入登录身份。
     */
    @Override
    public LoginVO login(LoginDTO dto, HttpServletRequest request) {
        loginRateLimiter.check(request.getRemoteAddr());
        HttpSession session = request.getSession(true);
        boolean captchaRequired = authRiskLimiter.loginRequiresCaptcha(dto.getUsername(), request);
        String proof = dto.getCaptchaProof();
        if (proof == null || proof.isBlank()) {
            if (captchaRequired) {
                throw new BusinessException(403, "请先完成滑块验证",
                        Map.of("state", "CAPTCHA_REQUIRED"), null);
            }
        } else {
            // 凭证必须属于当前 Session，且只能使用一次。
            captchaService.consumeLoginProof(proof, session.getId());
        }

        LoginVO loginVO;
        try {
            loginVO = authenticate(dto);
        } catch (BusinessException e) {
            // 只把账号或密码错误记为失败；服务故障不计入用户风险。
            if (e.getCode() == 401) {
                authRiskLimiter.recordLoginFailure(dto.getUsername(), request);
            }
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

    /** 查询启用中的账号并校验密码；对账号不存在、停用和密码错误使用相同错误响应。 */
    private LoginVO authenticate(LoginDTO dto) {
        UserAccount account = userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getUsername, dto.getUsername())
        );
        if (account == null || account.getStatus() != AccountStatus.ACTIVE
                || dto.getPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !encoder.matches(dto.getPassword(), account.getPasswordHash())) {
            throw new BusinessException(401, "账号或密码错误");
        }
        return new LoginVO(account.getUserId(), account.getUsername());
    }

    /** 根据用户 ID 读取启用中的账号，返回客户端可见的基本资料与角色。 */
    @Override
    public UserInfoVO currentUser(Long userId) {
        UserAccount account = accountAccess.requireActive(userId);
        return new UserInfoVO(account.getUserId(), account.getUsername(), account.getRole().name());
    }

    /**
     * 锁定账号后校验旧密码并更新密码哈希与鉴权版本。
     * 鉴权版本变化用于让旧会话失效；事务提交前阻止缓存继续提供旧版本状态。
     */
    @Override
    @Transactional(timeout = 30)
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SessionUser.requireId(userId);
        UserAccount account = userAccountMapper.lockById(userId);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE)
            throw new BusinessException(401, "请重新登录");
        if (!encoder.matches(oldPassword, account.getPasswordHash()))
            throw new BusinessException(400, "原密码错误");
        if (newPassword == null || newPassword.length() < 8
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || newPassword.equals(oldPassword))
            throw new BusinessException(400, "新密码无效或与原密码相同");
        String hash = encoder.encode(newPassword);
        authCache.blockUntilTransactionEnds(userId,
                new AccountAuthCache.State(AccountStatus.ACTIVE, account.getAuthVersion() + 1));
        if (userAccountMapper.changePassword(userId, account.getAuthVersion(), hash) != 1)
            throw new BusinessException(409, "账号已变更，请重新登录");
        BusinessEventLog.committed("account", "password_changed", "user", userId);
    }

}
