package com.shiwushop.common.captcha.dto;

import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CaptchaVerifyDTO(
        @NotBlank @Size(max = 128) String id,
        @NotNull ImageCaptchaTrack track
) {}
