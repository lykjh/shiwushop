package com.shiwushop.account.controller.user;

import com.shiwushop.account.dto.user.ChangePasswordDTO;
import com.shiwushop.account.dto.user.LoginDTO;
import com.shiwushop.account.service.AccountService;
import com.shiwushop.account.service.LoginService;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.account.vo.user.UserInfoVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import com.shiwushop.common.logging.BusinessEventLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 负责密码登录、会话和当前账号入口；短信登录由 SmsLogin 处理。 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class LoginController {
    private final LoginService loginService;
    private final AccountService accountService;

    /** 返回 Spring Security 为当前请求建立的 CSRF 令牌。 */
    @GetMapping("/csrf")
    public Result<String> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Result.success(token.getToken());
    }

    /** 账号密码及风险验证由登录服务完成；成功后返回现有 LoginVO。 */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        return Result.success(loginService.login(dto, request));
    }

    /** 使当前 Session 失效；尚未登录的调用仍返回成功。 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Long userId = SessionUser.currentId(request);
            session.invalidate();
            if (userId != null) BusinessEventLog.committed("account", "logout", "user", userId);
        }
        return Result.success();
    }

    /** 改密提升鉴权版本并撤销当前 Session，用户随后重新登录。 */
    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO body,
                                       HttpServletRequest request) {
        accountService.changePassword(SessionUser.requireId(request), body.oldPassword(), body.newPassword());
        request.getSession(false).invalidate();
        return Result.success();
    }

    /** 依据现有 Session 查询当前启用中的账号。 */
    @GetMapping("/me")
    public Result<UserInfoVO> me(HttpServletRequest request) {
        return Result.success(accountService.currentUser(SessionUser.currentId(request)));
    }
}
