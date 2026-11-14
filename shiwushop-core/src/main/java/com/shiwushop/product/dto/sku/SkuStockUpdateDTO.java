package com.shiwushop.product.dto.sku;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SkuStockUpdateDTO(
    /** 普通可售库存件数。 */
    @NotNull @Min(0) Integer stock
) {
}
