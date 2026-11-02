package com.shiwushop.user.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SmsEntryRegisterDTO(
        @NotBlank @Pattern(regexp = "^1[3-9][0-9]{9}$", message = "手机号格式错误") String phone,
        @NotBlank @Size(max = 100) String registrationTicket,
        @NotBlank @Size(min = 8, max = 72, message = "密码长度在8到72个字符之间")
        @Pattern(regexp = "[!-~]*", message = "密码只能包含字母、数字和特定符号") String password) {}
