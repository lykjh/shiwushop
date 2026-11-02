package com.shiwushop.user.controller.user;

import com.shiwushop.user.dto.user.SmsCodeDTO;
import com.shiwushop.user.dto.user.SmsPhoneDTO;
import com.shiwushop.user.service.SmsLoginService;
import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只处理匿名短信验证码发送和短信登录。 */
@RestController
@RequestMapping("/users/sms")
@RequiredArgsConstructor
public class SmsLoginController {
    private final SmsLoginService login;

    /** 未绑定号码也返回相同结构，避免通过发送接口探测账号。 */
    @PostMapping("/send")
    public Result<Void> send(@Valid @RequestBody SmsPhoneDTO body, HttpServletRequest request) {
        login.sendCode(body.phone(), request.getRemoteAddr());
        return Result.success();
    }

    /** 一次性消费验证码后建立与密码登录相同的会话。 */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody SmsCodeDTO body, HttpServletRequest request) {
        return Result.success(login.login(body.phone(), body.code(), request));
    }
}
