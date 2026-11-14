package com.shiwushop.product.dto.product;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ProductRevisionDTO(
    /** 商品内容修订号，用于上架审批和并发校验。 */
    @NotNull @PositiveOrZero Long revision
) {
}
