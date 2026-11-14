package com.shiwushop.user.controller.user;

import com.shiwushop.user.vo.user.SmsSendVO;

import com.shiwushop.common.Result;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.user.auth.JwtLogin;
import com.shiwushop.user.dto.user.PasswordResetDTO;
import com.shiwushop.user.dto.user.SmsCodeDTO;
import com.shiwushop.user.dto.user.SmsPhoneDTO;
import com.shiwushop.user.service.PasswordResetService;
import com.shiwushop.user.vo.user.PasswordResetVerifyVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 手机验证码找回密码入口；保留原来的旧密码改密接口。 */
@RestController
@RequestMapping("/users/password/reset")
@RequiredArgsConstructor
public class PasswordResetController {
    private final PasswordResetService service;
    private final JwtLogin jwt;
    private final BrowserFlow browserFlow;

    /** 校验手机号格式后发送找回码，不通过发送结果公开手机号是否已绑定。 */
    @PostMapping("/send")
    public Result<SmsSendVO> send(@Valid @RequestBody SmsPhoneDTO body, HttpServletRequest request) {
        return Result.success(new SmsSendVO(service.sendCode(body.phone(), request)));
    }

    /** 核验找回码；票据随后只能由持有当前 Cookie 的客户端使用。 */
    @PostMapping("/verify")
    public Result<PasswordResetVerifyVO> verify(@Valid @RequestBody SmsCodeDTO body, HttpServletRequest request) {
        return Result.success(service.verify(body.phone(), body.code(), request));
    }

    /** 密码重设提交后清除登录 Cookie。 */
    @PostMapping
    public Result<Void> reset(@Valid @RequestBody PasswordResetDTO body, HttpServletRequest request,
                              HttpServletResponse response) {
        service.reset(body.resetTicket(), body.newPassword(), request);
        jwt.clearCookie(response);
        browserFlow.rotate(response);
        return Result.success();
    }
}
