package com.shiwushop.account.dto.user;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CaptchaVerifyDTO(
        @NotBlank String id,
        @NotNull ImageCaptchaTrack track
) {}
