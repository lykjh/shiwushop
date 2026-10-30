package com.shiwushop.account.controller.user;

import com.shiwushop.account.dto.user.PhoneBindDTO;
import com.shiwushop.account.dto.user.SmsCodeDTO;
import com.shiwushop.account.dto.user.SmsPhoneDTO;
import com.shiwushop.account.service.SmsService;
import com.shiwushop.account.vo.user.LoginVO;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class SmsLogin {
    private final SmsService smsService;

    /** 为当前登录账号的新手机号发送绑定码；发送结果不包含验证码。 */
    @PostMapping("/phone/bind/send")
    public Result<Void> sendBind(@Valid @RequestBody SmsPhoneDTO body, HttpServletRequest request) {
        smsService.sendBindCode(SessionUser.requireId(request), body.phone(), request.getRemoteAddr());
        return Result.success();
    }

    /** 校验当前密码和绑定码；换绑后使当前会话失效。 */
    @PostMapping("/phone/bind/confirm")
    public Result<Void> bind(@Valid @RequestBody PhoneBindDTO body, HttpServletRequest request) {
        smsService.bind(SessionUser.requireId(request), body.currentPassword(), body.phone(), body.code());
        request.getSession(false).invalidate();
        return Result.success();
    }

    /** 匿名发送接口对已绑定与未绑定号码返回相同的成功结构。 */
    @PostMapping("/sms/send")
    public Result<Void> sendLogin(@Valid @RequestBody SmsPhoneDTO body, HttpServletRequest request) {
        smsService.sendLoginCode(body.phone(), request.getRemoteAddr());
        return Result.success();
    }

    /** 登录码验证成功后返回与密码登录一致的账号信息和 Session。 */
    @PostMapping("/sms/login")
    public Result<LoginVO> login(@Valid @RequestBody SmsCodeDTO body, HttpServletRequest request) {
        return Result.success(smsService.login(body.phone(), body.code(), request));
    }
}
