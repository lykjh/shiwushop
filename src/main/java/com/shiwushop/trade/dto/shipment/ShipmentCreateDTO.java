package com.shiwushop.trade.dto.shipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipmentCreateDTO(@NotBlank @Size(max = 80) String carrierName,
                                 @NotBlank @Size(max = 100) String trackingNo) {
    public ShipmentCreateDTO {
        if (carrierName != null) carrierName = carrierName.strip();
        if (trackingNo != null) trackingNo = trackingNo.strip();
    }
}
