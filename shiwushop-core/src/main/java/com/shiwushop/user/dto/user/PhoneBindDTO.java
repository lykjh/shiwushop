package com.shiwushop.user.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PhoneBindDTO(
    /** 用于确认本人身份的当前密码。 */
    @NotBlank String currentPassword,
    /** 手机号。 */
    @NotBlank @Pattern(regexp = "^1[3-9][0-9]{9}$", message = "手机号格式错误") String phone,
    /** 用于绑定手机号的短信验证码。 */
    @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "验证码格式错误") String code
) {
}
