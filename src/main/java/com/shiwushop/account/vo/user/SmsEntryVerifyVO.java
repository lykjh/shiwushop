package com.shiwushop.account.vo.user;

/** 已有绑定直接登录；首次使用只返回短期设密码票据。 */
public record SmsEntryVerifyVO(String state, Long id, String username,
                               String registrationTicket, Integer expiresInSeconds) {
    public static SmsEntryVerifyVO loggedIn(LoginVO login) {
        return new SmsEntryVerifyVO("LOGGED_IN", login.getId(), login.getUsername(), null, null);
    }

    public static SmsEntryVerifyVO setPassword(String ticket) {
        return new SmsEntryVerifyVO("SET_PASSWORD_REQUIRED", null, null, ticket, 300);
    }
}
