package com.shiwushop.marketing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.OffsetDateTime;
import java.util.List;

public record CouponCodeBatch(
    /** 本次创建批次的兑换码列表。 */
    @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Code> codes
) {
    public record Code(
        /** 待创建的兑换码明文；服务端仅持久化其摘要。 */
        @NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String code,
        /** 兑换码类型：PUBLIC 公共码、SINGLE 单次码。 */
        @NotNull @Pattern(regexp = "PUBLIC|SINGLE") String codeKind,
        /** 最多允许兑换次数；SINGLE 单次码固定为 1。 */
        @NotNull @Positive @JsonDeserialize(using = JsonIntegerValue.class) Integer maxRedemptions,
        /** 有效截止时间。 */
        @NotNull OffsetDateTime expiresAt
    ) {
    }
}
