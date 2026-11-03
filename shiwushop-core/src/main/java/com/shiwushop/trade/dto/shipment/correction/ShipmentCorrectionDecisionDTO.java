package com.shiwushop.trade.dto.shipment.correction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ShipmentCorrectionDecisionDTO(@NotNull Boolean approved, @Size(max = 500) String remark) {
    public ShipmentCorrectionDecisionDTO {
        if (remark != null) remark = remark.strip();
    }
}
