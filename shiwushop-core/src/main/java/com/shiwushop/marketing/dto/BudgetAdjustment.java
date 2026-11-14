package com.shiwushop.marketing.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record BudgetAdjustment(
    /** 本次调整的金额，正数增加、负数扣减，单位元。 */
    @NotNull @Digits(integer = 16, fraction = 2) BigDecimal deltaAmount,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason,
    /** 操作凭据引用。 */
    @NotBlank @Size(max = 500) String evidenceRef
) {
}
