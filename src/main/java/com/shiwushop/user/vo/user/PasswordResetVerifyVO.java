package com.shiwushop.user.vo.user;

/** 核验成功后只返回短期票据，不公开账号资料，也不建立登录态。 */
public record PasswordResetVerifyVO(String resetTicket, long expiresInSeconds) {}
