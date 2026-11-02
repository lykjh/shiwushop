package com.shiwushop.aftersale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReturnShipmentDTO(@NotBlank @Size(max = 80) String carrierName,
                                 @NotBlank @Size(max = 100) String trackingNo) {
    public ReturnShipmentDTO {
        if (carrierName != null) carrierName = carrierName.strip();
        if (trackingNo != null) trackingNo = trackingNo.strip();
    }
}
