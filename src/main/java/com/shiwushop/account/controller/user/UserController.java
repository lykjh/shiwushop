package com.shiwushop.account.controller.user;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import com.shiwushop.account.dto.user.LoginDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.dto.user.ChangePasswordDTO;
import com.shiwushop.account.dto.user.CaptchaVerifyDTO;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.account.vo.user.UserInfoVO;
import com.shiwushop.account.vo.user.CaptchaProofVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.account.auth.AuthRiskLimiter;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.captcha.CaptchaRateLimiter;
import com.shiwushop.common.captcha.CaptchaService;
import com.shiwushop.account.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


/** 会员注册、登录和会话入口；滑块用于本模块的身份验证。 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final AuthRiskLimiter authRiskLimiter;
    private final CaptchaService captchaService;
    private final CaptchaRateLimiter captchaRateLimiter;

    /**
     * 将当前请求的 CSRF 令牌返回给客户端，供后续修改数据的请求放入请求头。
     * 令牌由 Spring Security 放入 request，这里只负责读取和返回。
     *
     * @param request 当前 HTTP 请求
     * @return 当前请求关联的 CSRF 令牌
     */
    @GetMapping("/csrf")
    public Result<String> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Result.success(token.getToken());
    }

    /**
     * 提交会员注册信息。注册校验和账号创建交给服务层；成功后才记录注册成功的风控信号。
     * DTO 的字段约束由 {@code @Valid} 在进入方法前校验。
     *
     * @param dto 用户名、密码等注册信息
     * @param request 当前请求，供服务层识别会话和风险信号
     * @return 注册成功时的统一空响应
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        userService.register(dto, request);
        authRiskLimiter.recordRegisterSuccess(request);
        return Result.success();
    }

    /**
     * 登录会员账号。密码、风险判断和会话处理由用户服务完成，控制器只包装登录结果。
     *
     * @param dto 登录凭据及按风险规则要求提交的滑块凭证
     * @param request 当前请求，供服务层处理会话
     * @return 登录后的用户信息
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto,HttpServletRequest request) {
        return Result.success(userService.login(dto, request));
    }

    /**
     * 退出当前会话。存在 Session 时使其失效；未建立 Session 的请求也返回成功。
     *
     * @param request 当前请求
     * @return 退出操作的统一空响应
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Result.success();
    }

    /**
     * 修改当前登录会员的密码。服务层完成旧密码校验和新密码写入后，使当前 Session 失效，
     * 客户端需要使用新密码重新登录。
     *
     * @param body 旧密码和新密码
     * @param request 当前登录请求
     * @return 修改成功时的统一空响应
     */
    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO body,
                                       HttpServletRequest request) {
        userService.changePassword(SessionUser.requireId(request), body.oldPassword(), body.newPassword());
        request.getSession(false).invalidate();
        return Result.success();
    }

    /**
     * 查询当前会话对应的会员资料。用户 ID 从 Session 读取，具体资料由服务层查询。
     *
     * @param request 当前请求
     * @return 当前会员资料
     */
    @GetMapping("/me")
    public Result<UserInfoVO> me(HttpServletRequest request) {
        return Result.success(userService.currentUser(SessionUser.currentId(request)));
    }

    /**
     * 生成一次滑块挑战。先检查生成入口的资源额度，再调用验证码服务生成图片和挑战数据。
     *
     * @param request 当前请求，供限流器识别请求来源
     * @return 滑块组件需要的挑战数据
     */
    @GetMapping("/generate")
    public Result<ApiResponse<ImageCaptchaVO>> generate(HttpServletRequest request) {
        captchaRateLimiter.checkGenerate(request);
        return Result.success(captchaService.generateCaptcha());
    }

    /**
     * 校验滑块轨迹，并把通过后的凭证绑定到当前 Session。
     * 业务校验失败时记录失败次数；成功时清除该 Session 的滑块失败计数。
     *
     * @param body 挑战 ID 和滑动轨迹
     * @param request 当前请求；没有 Session 时创建一个以保存验证结果
     * @return 后续登录或注册请求使用的滑块凭证
     */
    @PostMapping("/valid")
    public Result<CaptchaProofVO> valid(@Valid @RequestBody CaptchaVerifyDTO body,
                                        HttpServletRequest request) {
        captchaRateLimiter.checkValid(request);
        HttpSession session = request.getSession(true);
        String proof;
        try {
            proof = captchaService.verifyAndIssueProof(body.id(), body.track(), session.getId());
        } catch (BusinessException e) {
            if (e.getCode() == 400) captchaRateLimiter.recordValidFailure(session.getId());
            throw e;
        }
        captchaRateLimiter.clearValidFailures(session.getId());
        return Result.success(new CaptchaProofVO(proof));
    }
}
