package com.shiwushop.account.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SmsCodeDTO(
        @NotBlank @Pattern(regexp = "^1[3-9][0-9]{9}$", message = "手机号格式错误") String phone,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "验证码格式错误") String code) {}
