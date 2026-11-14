package com.shiwushop.product.dto.product;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ProductListingDecisionDTO(
    /** 商品内容修订号，用于上架审批和并发校验。 */
    @NotNull @PositiveOrZero Long revision,
    /** 是否审核通过。 */
    @NotNull Boolean approved,
    /** 本次业务操作的原因。 */
    @Size(max = 500) String reason
) {
}
