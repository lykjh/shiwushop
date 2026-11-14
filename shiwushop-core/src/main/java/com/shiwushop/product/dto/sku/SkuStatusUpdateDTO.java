package com.shiwushop.product.dto.sku;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SkuStatusUpdateDTO(
    /** SKU 状态：ACTIVE 可销售、STOPPED 停售。 */
    @NotNull @Pattern(regexp = "ACTIVE|STOPPED") String status
) {
}
