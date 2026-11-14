package com.shiwushop.user.controller.user;

import com.shiwushop.user.vo.user.SmsSendVO;

import com.shiwushop.user.dto.user.PhoneBindDTO;
import com.shiwushop.user.dto.user.SmsPhoneDTO;
import com.shiwushop.user.service.PhoneBindingService;
import com.shiwushop.user.auth.JwtLogin;
import com.shiwushop.common.Result;
import com.shiwushop.common.auth.BrowserFlow;
import com.shiwushop.common.auth.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只处理当前账号的手机号绑定；确认绑定后使原会话失效。 */
@RestController
@RequestMapping("/users/phone/bind")
@RequiredArgsConstructor
public class PhoneBindingController {
    private final PhoneBindingService binding;
    private final JwtLogin jwt;
    private final BrowserFlow browserFlow;

    /** 发送验证码前确认当前账号有效，验证码不出现在响应里。 */
    @PostMapping("/send")
    public Result<SmsSendVO> send(@Valid @RequestBody SmsPhoneDTO body, HttpServletRequest request) {
        return Result.success(new SmsSendVO(binding.sendCode(SessionUser.requireId(request), body.phone(), request.getRemoteAddr())));
    }

    /** 当前密码和一次性验证码均通过后换绑手机号，并注销旧会话。 */
    @PostMapping("/confirm")
    public Result<Void> confirm(@Valid @RequestBody PhoneBindDTO body, HttpServletRequest request,
                                HttpServletResponse response) {
        binding.bind(SessionUser.requireId(request), body.currentPassword(), body.phone(), body.code());
        jwt.clearCookie(response);
        browserFlow.rotate(response);
        return Result.success();
    }
}
