package com.shiwushop.common.captcha.vo;

public record CaptchaProofVO(
    /** 与当前会话绑定的一次性图形验证码通过凭据。 */
    String captchaProof
) {
}
