package com.shiwushop.user.vo.user;

/** 核验成功后只返回短期票据，不公开账号资料，也不建立登录态。 */
public record PasswordResetVerifyVO(
    /** 短信验证后签发的短期密码重置票据。 */
    String resetTicket,
    /** 票据有效时长，单位秒。 */
    long expiresInSeconds
) {
}
