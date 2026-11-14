package com.shiwushop.marketing.dto;

import jakarta.validation.constraints.*;

public record CouponRedemption(
    /** 用户提交的兑换码明文。 */
    @NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String code
) {
}
