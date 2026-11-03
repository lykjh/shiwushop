package com.shiwushop.unit.inventory;

import com.shiwushop.product.dto.inventory.InventoryMovementQuery;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class InventoryMovementQueryTest {
    @Test void acceptsOptionalFieldsAndInclusiveSizeLimits() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new InventoryMovementQuery(null, null, null, null, null)).isEmpty());
            assertTrue(validator.validate(new InventoryMovementQuery(null, null, null, 1L, 1)).isEmpty());
            assertTrue(validator.validate(new InventoryMovementQuery(null, null, null, 1L, 100)).isEmpty());
            assertFalse(validator.validate(new InventoryMovementQuery(null, null, null, 0L, 101)).isEmpty());
        }
    }
    @Test void requiresStrictlyIncreasingTimeRange() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var time = LocalDateTime.of(2026, 11, 3, 10, 0);
            assertTrue(validator.validate(new InventoryMovementQuery(null, time, time.plusSeconds(1), null, null)).isEmpty());
            assertFalse(validator.validate(new InventoryMovementQuery(null, time, time, null, null)).isEmpty());
            assertFalse(validator.validate(new InventoryMovementQuery(null, time, time.minusSeconds(1), null, null)).isEmpty());
        }
    }
}
