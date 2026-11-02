package com.shiwushop.user.controller.user;

import com.shiwushop.user.dto.user.SmsCodeDTO;
import com.shiwushop.user.dto.user.SmsEntryRegisterDTO;
import com.shiwushop.user.dto.user.SmsPhoneDTO;
import com.shiwushop.user.service.SmsEntryService;
import com.shiwushop.user.vo.user.LoginVO;
import com.shiwushop.user.vo.user.SmsEntryVerifyVO;
import com.shiwushop.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 手机验证码首次使用入口；手机号和邮箱各有独立流程。 */
@RestController
@RequestMapping("/users/sms/entry")
@RequiredArgsConstructor
public class SmsEntryController {
    private final SmsEntryService entry;

    /** 发送响应不揭露手机号是否已绑定。 */
    @PostMapping("/send")
    public Result<Void> send(@Valid @RequestBody SmsPhoneDTO body, HttpServletRequest request) {
        entry.sendCode(body.phone(), request);
        return Result.success();
    }

    /** 已绑定账号直接登录；未绑定账号返回短期设密码票据。 */
    @PostMapping("/verify")
    public Result<SmsEntryVerifyVO> verify(@Valid @RequestBody SmsCodeDTO body, HttpServletRequest request) {
        return Result.success(entry.verify(body.phone(), body.code(), request));
    }

    /** 首次设密码后创建普通用户并立即登录。 */
    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody SmsEntryRegisterDTO body, HttpServletRequest request) {
        return Result.success(entry.register(body.phone(), body.registrationTicket(), body.password(), request));
    }
}
