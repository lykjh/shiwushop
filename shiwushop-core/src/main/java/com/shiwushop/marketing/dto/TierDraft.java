package com.shiwushop.marketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

import tools.jackson.databind.annotation.JsonDeserialize;

public record TierDraft(
    /** 享受该优惠所需的门槛金额，单位元。 */
    @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal thresholdAmount,
    /** 达到阶梯门槛时减免的金额，单位元。 */
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal reductionAmount,
    /** 赠品 SKU ID。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long giftSkuId,
    /** 满足阶梯条件时赠送的商品件数。 */
    @Min(1) @Max(10000) @JsonDeserialize(using = JsonIntegerValue.class) Integer giftQuantity
) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("不接受阶梯字段：" + field);
    }
}
