package com.shiwushop.aftersale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReturnShipmentDTO(
    /** 物流承运商名称。 */
    @NotBlank @Size(max = 80) String carrierName,
    /** 物流运单号。 */
    @NotBlank @Size(max = 100) String trackingNo
) {
    public ReturnShipmentDTO {
        if (carrierName != null)
            carrierName = carrierName.strip();
        if (trackingNo != null)
            trackingNo = trackingNo.strip();
    }
}
