package com.shiwushop.trade.dto.shipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipmentCreateDTO(
    /** 物流承运商名称。 */
    @NotBlank @Size(max = 80) String carrierName,
    /** 物流运单号。 */
    @NotBlank @Size(max = 100) String trackingNo
) {
    public ShipmentCreateDTO {
        if (carrierName != null)
            carrierName = carrierName.strip();
        if (trackingNo != null)
            trackingNo = trackingNo.strip();
    }
}
