package com.shiwushop.user.dto.user;

public record AccountProofDTO(
    /** 用于确认本人身份的当前密码。 */
    String currentPassword,
    /** 用于本人身份验证的短信验证码。 */
    String smsVerification
) {
}
