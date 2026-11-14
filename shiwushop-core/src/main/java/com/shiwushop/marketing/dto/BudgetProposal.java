package com.shiwushop.marketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

import tools.jackson.databind.annotation.JsonDeserialize;

public record BudgetProposal(
    /** 预算承担方：PLATFORM 平台、MERCHANT 商家。 */
    @NotNull @Pattern(regexp = "PLATFORM|MERCHANT") String party,
    /** 商家承担方店铺 ID；PLATFORM 方案不填。 */
    @Positive @JsonDeserialize(using = JsonLongValue.class) Long shopId,
    /** 预算总额度，单位元。 */
    @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal budgetAmount,
    /** 商家方案中的平台承担基点数；平台预算方案不填，10000 表示 100%。 */
    @Min(0) @Max(10000) @JsonDeserialize(using = JsonIntegerValue.class) Integer platformShareBp
) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("不接受预算方案字段：" + field);
    }
}
