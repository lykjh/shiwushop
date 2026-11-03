package com.shiwushop.product.dto.inventory;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

public record InventoryMovementQuery(
        InventoryMovementReason reason,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
        @Positive Long afterId,
        @Min(1) @Max(100) Integer size) {
    @AssertTrue(message = "开始时间必须早于结束时间")
    public boolean isValidRange() {
        return createdFrom == null || createdTo == null || createdFrom.isBefore(createdTo);
    }
}
