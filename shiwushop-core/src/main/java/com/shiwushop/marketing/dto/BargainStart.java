package com.shiwushop.marketing.dto;

import jakarta.validation.constraints.*;

public record BargainStart(
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    @NotNull @Positive Long activitySkuId
) {
}
