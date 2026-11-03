package com.shiwushop.trade.dto.shipment.correction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ShipmentCorrectionRequestDTO(@NotNull @PositiveOrZero Integer originalVersion,
        @NotBlank @Size(max = 80) String carrierName, @NotBlank @Size(max = 100) String trackingNo,
        @NotBlank @Size(max = 500) String reason) {
    public ShipmentCorrectionRequestDTO {
        if (carrierName != null) carrierName = carrierName.strip();
        if (trackingNo != null) trackingNo = trackingNo.strip();
        if (reason != null) reason = reason.strip();
    }
}
