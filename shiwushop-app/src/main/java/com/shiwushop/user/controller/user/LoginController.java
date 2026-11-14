package com.shiwushop.user.controller.user;

import com.shiwushop.user.dto.user.ChangePasswordDTO;
import com.shiwushop.user.dto.user.LoginDTO;
import com.shiwushop.user.auth.JwtLogin;
import com.shiwushop.user.service.AccountService;
import com.shiwushop.user.service.LoginService;
import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.user.vo.user.UserInfoVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.logging.BusinessEventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 负责密码登录、会话和当前账号入口；短信登录由 SmsEntryController 处理。 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class LoginController {
    private final LoginService loginService;
    private final AccountService accountService;
    private final JwtLogin jwt;
    private final BrowserFlow browserFlow;

    /** 返回 Spring Security 为当前请求建立的 CSRF 令牌。 */
    @GetMapping("/csrf")
    public Result<String> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Result.success(token.getToken());
    }

    /** 账号密码及风险验证由登录服务完成；成功后返回现有 LoginVO。 */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request,
                                 HttpServletResponse response) {
        return Result.success(loginService.login(dto, request, response));
    }

    /** Redis 确认撤销后才清除 Cookie。 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        jwt.revokeCurrent(request);
        jwt.clearCookie(response);
        browserFlow.rotate(response);
        Long userId = SessionUser.currentId(request);
        if (userId != null)
            BusinessEventLog.committed("user", "logout", "user", userId);
        return Result.success();
    }

    /** 改密提升鉴权版本，旧 JWT 失效。 */
    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO body,
                                       HttpServletRequest request, HttpServletResponse response) {
        accountService.changePassword(SessionUser.requireId(request), body.oldPassword(), body.newPassword());
        jwt.clearCookie(response);
        browserFlow.rotate(response);
        return Result.success();
    }

    /** 查询当前启用中的账号。 */
    @GetMapping("/me")
    public Result<UserInfoVO> me(HttpServletRequest request) {
        return Result.success(accountService.currentUser(SessionUser.requireId(request)));
    }
}
