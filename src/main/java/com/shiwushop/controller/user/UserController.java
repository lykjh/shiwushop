package com.shiwushop.controller.user;

import cloud.tianai.captcha.application.vo.ImageCaptchaVO;
import cloud.tianai.captcha.common.response.ApiResponse;
import com.shiwushop.dto.user.CaptchaVerifyDTO;
import com.shiwushop.dto.user.LoginDTO;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.dto.user.ChangePasswordDTO;
import com.shiwushop.vo.user.CaptchaProofVO;
import com.shiwushop.vo.user.LoginVO;
import com.shiwushop.vo.user.UserInfoVO;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.AuthRiskLimiter;
import com.shiwushop.common.auth.CaptchaRateLimiter;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.service.CaptchaService;
import com.shiwushop.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    private final AuthRiskLimiter authRiskLimiter;
    private final CaptchaService captchaService;
    private final CaptchaRateLimiter captchaRateLimiter;

    @GetMapping("/generate")
    public Result<ApiResponse<ImageCaptchaVO>> generate(
            HttpServletRequest request
    ) {
        captchaRateLimiter.checkGenerate(request);
        return Result.success(captchaService.generateCaptcha());
    }

    @PostMapping("/valid")
    public Result<CaptchaProofVO> valid(
            @Valid @RequestBody CaptchaVerifyDTO body,
            HttpServletRequest request
    ) {
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

    @GetMapping("/csrf")
    public Result<String> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Result.success(token.getToken());
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        userService.register(dto, request);
        authRiskLimiter.recordRegisterSuccess(request);
        return Result.success();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto,HttpServletRequest request) {
        return Result.success(userService.login(dto, request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Result.success();
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO body,
                                       HttpServletRequest request) {
        userService.changePassword(SessionUser.requireId(request), body.oldPassword(), body.newPassword());
        request.getSession(false).invalidate();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserInfoVO> me(HttpServletRequest request) {
        return Result.success(userService.currentUser(SessionUser.currentId(request)));
    }
}
