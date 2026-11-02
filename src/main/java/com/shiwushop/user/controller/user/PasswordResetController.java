package com.shiwushop.user.controller.user;

import com.shiwushop.common.Result;
import com.shiwushop.user.dto.user.PasswordResetDTO;
import com.shiwushop.user.dto.user.SmsCodeDTO;
import com.shiwushop.user.dto.user.SmsPhoneDTO;
import com.shiwushop.user.service.PasswordResetService;
import com.shiwushop.user.vo.user.PasswordResetVerifyVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 手机验证码找回密码入口；保留原来的旧密码改密接口。 */
@RestController
@RequestMapping("/users/password/reset")
@RequiredArgsConstructor
public class PasswordResetController {
    private final PasswordResetService service;

    /** 校验手机号格式后发送找回码，不通过发送结果公开手机号是否已绑定。 */
    @PostMapping("/send")
    public Result<Void> send(@Valid @RequestBody SmsPhoneDTO body, HttpServletRequest request) {
        service.sendCode(body.phone(), request);
        return Result.success();
    }

    /** 核验找回码；票据随后只能由持有当前 Cookie 的客户端使用。 */
    @PostMapping("/verify")
    public Result<PasswordResetVerifyVO> verify(@Valid @RequestBody SmsCodeDTO body, HttpServletRequest request) {
        return Result.success(service.verify(body.phone(), body.code(), request));
    }

    /** 事务提交后退出当前登录态；保留匿名 Session，避免并发请求保存已删除会话时报错。 */
    @PostMapping
    public Result<Void> reset(@Valid @RequestBody PasswordResetDTO body, HttpServletRequest request) {
        service.reset(body.resetTicket(), body.newPassword(), request);
        request.getSession(false).removeAttribute("userId");
        request.getSession(false).removeAttribute("authVersion");
        return Result.success();
    }
}
