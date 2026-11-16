package com.shiwushop.user.service;

/** 按业务用途发送验证码并核验账号敏感操作的身份凭证。 */
public interface AccountProofService {
    long sendCode(Long userId, String purpose, String remoteAddress);

    void verify(Long userId, String purpose, String password, String code);
}
