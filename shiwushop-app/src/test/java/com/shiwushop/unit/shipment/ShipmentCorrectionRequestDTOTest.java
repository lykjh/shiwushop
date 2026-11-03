package com.shiwushop.unit.shipment;

import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionRequestDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShipmentCorrectionRequestDTOTest {
    @Test void stripsFieldsAndRejectsInvalidVersionOrBlankFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valid = new ShipmentCorrectionRequestDTO(0, " 顺丰 ", " SF1 ", " 更正 ");
            assertEquals("顺丰", valid.carrierName());
            assertTrue(validator.validate(valid).isEmpty());
            assertFalse(validator.validate(new ShipmentCorrectionRequestDTO(-1, " ", "", " ")).isEmpty());
            assertFalse(validator.validate(new ShipmentCorrectionRequestDTO(null, "x", "x", "x")).isEmpty());
            assertFalse(validator.validate(new ShipmentCorrectionRequestDTO(0, "x".repeat(81), "x", "x")).isEmpty());
        }
    }
}
