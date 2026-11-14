package com.shiwushop.trade.dto.shipment.correction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ShipmentCorrectionRequestDTO(
    /** 提交物流更正时读取的原版本标识。 */
    @NotNull @PositiveOrZero Integer originalVersion,
    /** 物流承运商名称。 */
    @NotBlank @Size(max = 80) String carrierName,
    /** 物流运单号。 */
    @NotBlank @Size(max = 100) String trackingNo,
    /** 本次业务操作的原因。 */
    @NotBlank @Size(max = 500) String reason
) {
    public ShipmentCorrectionRequestDTO {
        if (carrierName != null)
            carrierName = carrierName.strip();
        if (trackingNo != null)
            trackingNo = trackingNo.strip();
        if (reason != null)
            reason = reason.strip();
    }
}
