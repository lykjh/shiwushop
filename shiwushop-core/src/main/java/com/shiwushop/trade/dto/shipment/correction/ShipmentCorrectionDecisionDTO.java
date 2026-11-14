package com.shiwushop.trade.dto.shipment.correction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ShipmentCorrectionDecisionDTO(
    /** 是否审核通过。 */
    @NotNull Boolean approved,
    /** 审批备注。 */
    @Size(max = 500) String remark
) {
    public ShipmentCorrectionDecisionDTO {
        if (remark != null)
            remark = remark.strip();
    }
}
