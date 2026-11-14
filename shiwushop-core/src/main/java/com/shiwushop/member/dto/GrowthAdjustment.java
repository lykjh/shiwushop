package com.shiwushop.member.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record GrowthAdjustment(
    /** 本次调整的金额，正数增加、负数扣减，单位元。 */
    @NotNull @Digits(integer = 16, fraction = 2) BigDecimal deltaAmount,
    /** 负向成长调整必须关联原入账流水；正向调整不填。 */
    @Positive Long sourceEntryId,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason,
    /** 操作凭据引用。 */
    @NotBlank @Size(max = 500) String evidenceRef
) {
}
