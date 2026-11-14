package com.shiwushop.product.dto.inventory;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

public record InventoryMovementQuery(
    /** 按普通库存变动原因筛选。 */
    InventoryMovementReason reason,
    /** 创建时间查询下界。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
    /** 创建时间查询上界。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
    /** 本页查询的起始 ID 游标。 */
    @Positive Long afterId,
    /** 每页请求条数。 */
    @Min(1) @Max(100) Integer size
) {
    @AssertTrue(message = "开始时间必须早于结束时间")
    public boolean isValidRange() {
        return createdFrom == null || createdTo == null || createdFrom.isBefore(createdTo);
    }
}
