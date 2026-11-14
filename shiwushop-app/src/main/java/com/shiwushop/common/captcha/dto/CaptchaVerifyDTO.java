package com.shiwushop.common.captcha.dto;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CaptchaVerifyDTO(
    /* 待验证的验证码挑战 ID。 */
    @NotBlank @Size(max = 128) String id,
    /* 图形验证码交互轨迹，交由验证码组件验证。 */
    @NotNull ImageCaptchaTrack track
) {}
