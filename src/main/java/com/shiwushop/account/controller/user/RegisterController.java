package com.shiwushop.account.controller.user;

import com.shiwushop.account.auth.AuthRiskLimiter;
import com.shiwushop.account.dto.user.RegisterDTO;
import com.shiwushop.account.service.RegisterService;
import com.shiwushop.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只处理会员注册入口。 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class RegisterController {
    private final RegisterService registerService;
    private final AuthRiskLimiter authRiskLimiter;

    /**
     * 先由注册服务验证滑块并创建账号。
     * 服务返回时数据库事务已提交，此后才记录注册成功的风控信号。
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        registerService.register(dto, request);
        authRiskLimiter.recordRegisterSuccess(request);
        return Result.success();
    }
}
