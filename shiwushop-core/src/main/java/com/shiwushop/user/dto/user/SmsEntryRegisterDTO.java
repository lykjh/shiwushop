package com.shiwushop.user.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SmsEntryRegisterDTO(
    /** 手机号。 */
    @NotBlank @Pattern(regexp = "^1[3-9][0-9]{9}$", message = "手机号格式错误") String phone,
    /** 短信验证后签发的短期注册票据。 */
    @NotBlank @Size(max = 100) String registrationTicket,
    /** 用户提交的明文密码，仅用于当前认证或注册处理。 */
    @NotBlank @Size(min = 8, max = 72, message = "密码长度在8到72个字符之间")
        @Pattern(regexp = "[!-~]*", message = "密码只能包含字母、数字和特定符号") String password
) {
}
