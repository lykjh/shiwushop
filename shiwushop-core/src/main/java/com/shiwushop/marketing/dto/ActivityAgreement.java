package com.shiwushop.marketing.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

import tools.jackson.databind.annotation.JsonDeserialize;

public record ActivityAgreement(
    /** 平台承担比例，单位基点；10000 表示 100%。 */
    @NotNull @Min(0) @Max(10000) @JsonDeserialize(using = JsonIntegerValue.class) Integer platformShareBp,
    /** 商家同意承担的预算额度，单位元。 */
    @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal merchantBudgetAmount,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason
) {
}
