package com.shiwushop.trade.dto.shipment.correction;

public enum ShipmentCorrectionStatus {
    /** 物流更正申请待审核。 */
    PENDING,
    /** 审核通过。 */
    APPROVED,
    /** 审核驳回。 */
    REJECTED
}
