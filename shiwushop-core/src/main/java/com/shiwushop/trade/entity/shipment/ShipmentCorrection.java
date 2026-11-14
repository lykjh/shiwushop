package com.shiwushop.trade.entity.shipment;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShipmentCorrection {
    /** 物流更正申请 ID。 */
    private Long id;
    /** 关联发货包裹 ID。 */
    private Long shipmentId;
    /** 关联订单 ID。 */
    private Long orderId;
    /** 所属店铺 ID。 */
    private Long shopId;
    /** 提交物流更正时读取的原版本标识。 */
    private Integer originalVersion;
    /** 更正前的物流承运商名称。 */
    private String oldCarrierName;
    /** 更正前的物流运单号。 */
    private String oldTrackingNo;
    /** 申请更正后的物流承运商名称。 */
    private String newCarrierName;
    /** 申请更正后的物流运单号。 */
    private String newTrackingNo;
    /** 本次业务操作的原因。 */
    private String reason;
    /** 提交物流更正申请的用户 ID。 */
    private Long requestedBy;
    /** 请求发起时间。 */
    private LocalDateTime requestedAt;
    /** 物流更正状态：PENDING 待审、APPROVED 通过、REJECTED 驳回。 */
    private String status;
    /** 作出物流更正审批决定的用户 ID。 */
    private Long decidedBy;
    /** 审批决定时间。 */
    private LocalDateTime decidedAt;
    /** 审批备注。 */
    private String remark;
}
