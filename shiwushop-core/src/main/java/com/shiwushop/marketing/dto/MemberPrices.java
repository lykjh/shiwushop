package com.shiwushop.marketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record MemberPrices(
    /** 白银会员单价，单位元。 */
    @JsonProperty("SILVER") @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal silver,
    /** 黄金会员单价，单位元。 */
    @JsonProperty("GOLD") @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal gold,
    /** 铂金会员单价，单位元。 */
    @JsonProperty("PLATINUM") @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2) BigDecimal platinum
) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("不接受等级价格字段：" + field);
    }
}
