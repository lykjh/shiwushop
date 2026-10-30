package com.shiwushop.account.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SmsPhoneDTO(
        @NotBlank @Pattern(regexp = "^1[3-9][0-9]{9}$", message = "手机号格式错误") String phone) {}
