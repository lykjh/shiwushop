package com.shiwushop.merchant.dto.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MerchantApplicationDecisionDTO(
    /** 并发更新版本号。 */
    @NotNull @Positive Long version,
    /** 是否审核通过。 */
    @NotNull Boolean approved,
    /** 本次业务操作的原因。 */
    @Size(max = 500) String reason
) {
}
