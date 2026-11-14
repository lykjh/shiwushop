package com.shiwushop.user.vo.user;

/** 已有绑定直接登录；首次使用只返回短期设密码票据。 */
public record SmsEntryVerifyVO(
    /** LOGGED_IN 已登录，或 SET_PASSWORD_REQUIRED 需设置密码完成注册。 */
    String state,
    /** 已登录用户 ID；需要注册时为空。 */
    Long id,
    /** 已登录用户名；需要注册时为空。 */
    String username,
    /** 仅需注册时返回的短期票据；已登录时为空。 */
    String registrationTicket,
    /** 注册票据有效秒数；已登录时为空。 */
    Integer expiresInSeconds
) {
    public static SmsEntryVerifyVO loggedIn(LoginVO login) {
        return new SmsEntryVerifyVO("LOGGED_IN", login.getId(), login.getUsername(), null, null);
    }

    public static SmsEntryVerifyVO setPassword(String ticket) {
        return new SmsEntryVerifyVO("SET_PASSWORD_REQUIRED", null, null, ticket, 300);
    }
}
