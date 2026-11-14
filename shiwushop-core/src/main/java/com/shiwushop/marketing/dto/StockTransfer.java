package com.shiwushop.marketing.dto;

import jakarta.validation.constraints.*;

public record StockTransfer(
    /** IN 从普通库存划入活动池；OUT 从活动池退回普通库存。 */
    @NotBlank @Pattern(regexp = "IN|OUT") String direction,
    /** 商品件数。 */
    @Min(1) @Max(10000) int quantity,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason
) {
}
