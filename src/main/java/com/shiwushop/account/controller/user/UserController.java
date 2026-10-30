package com.shiwushop.account.controller.user;

import com.shiwushop.account.dto.user.LoginDTO;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.dto.user.ChangePasswordDTO;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.account.vo.user.UserInfoVO;

import com.shiwushop.common.Result;
import com.shiwushop.account.auth.AuthRiskLimiter;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.account.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


/** 账号、会话与 CSRF 入口；滑块资源入口由 CaptchaController 负责。 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    private final AuthRiskLimiter authRiskLimiter;
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
