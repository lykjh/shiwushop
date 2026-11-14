package com.shiwushop.trade.vo.shipment.correction;

import java.time.LocalDateTime;

public record ShipmentCorrectionHistoryVO(
    /** 当前记录 ID。 */
    Long id,
    /** 提交物流更正时读取的原版本标识。 */
    int originalVersion,
    /** 更正前的物流承运商名称。 */
    String oldCarrierName,
    /** 更正前的物流运单号。 */
    String oldTrackingNo,
    /** 申请更正后的物流承运商名称。 */
    String newCarrierName,
    /** 申请更正后的物流运单号。 */
    String newTrackingNo,
    /** 本次业务操作的原因。 */
    String reason,
    /** 提交物流更正申请的用户 ID。 */
    Long requestedBy,
    /** 请求发起时间。 */
    LocalDateTime requestedAt,
    /** 作出物流更正审批决定的用户 ID。 */
    Long decidedBy,
    /** 审批决定时间。 */
    LocalDateTime decidedAt
) {
}
