package com.shiwushop.trade.vo.shipment.correction;

public record ShipmentCorrectionRequestVO(
    /** 当前记录 ID。 */
    Long id,
    /** 物流更正状态：PENDING 待审、APPROVED 通过、REJECTED 驳回。 */
    String status
) {
}
