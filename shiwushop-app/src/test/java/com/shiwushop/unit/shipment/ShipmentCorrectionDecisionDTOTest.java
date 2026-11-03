package com.shiwushop.unit.shipment;

import com.shiwushop.trade.dto.shipment.correction.ShipmentCorrectionDecisionDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShipmentCorrectionDecisionDTOTest {
    @Test void decisionRequiresChoiceAndLimitsRemark() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new ShipmentCorrectionDecisionDTO(null, null)).isEmpty());
            assertFalse(validator.validate(new ShipmentCorrectionDecisionDTO(true, "x".repeat(501))).isEmpty());
            assertTrue(validator.validate(new ShipmentCorrectionDecisionDTO(false, "核查")).isEmpty());
        }
    }
}
