package com.shiwushop.aftersale.vo;

import com.shiwushop.merchant.vo.shop.ReturnDestinationVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AfterSaleVO(
    /** 当前记录 ID。 */
    Long id,
    /** 关联订单 ID。 */
    Long orderId,
    /** 售后类型：REFUND_ONLY 仅退款、RETURN_REFUND 退货退款。 */
    String type,
    /** 售后单状态，取值见 AfterSaleStatus。 */
    String status,
    /** 买家申请售后的原因。 */
    String reason,
    /** 本次售后申请的现金退款金额，单位元。 */
    BigDecimal refundAmount,
    /** 请求发起时间。 */
    LocalDateTime requestedAt,
    /** 业务处理结束时间。 */
    LocalDateTime finishedAt,
    /** 审核意见。 */
    String reviewReason,
    /** 当前对象的明细列表。 */
    List<Item> items,
    /** 售后退货物流信息。 */
    ReturnInfo returnShipment,
    /** 本次售后的退款尝试信息。 */
    RefundInfo refund,
    /** 售后使用的退货地址快照。 */
    ReturnDestinationVO returnDestination
) {
    public record Item(
        /** 关联订单明细 ID。 */
        Long orderItemId,
        /** 商品件数。 */
        Integer quantity,
        /** 本次售后申请的现金退款金额，单位元。 */
        BigDecimal refundAmount
    ) {
    }

    public record ReturnInfo(
        /** 物流承运商名称。 */
        String carrierName,
        /** 物流运单号。 */
        String trackingNo,
        /** 买家提交退货物流的时间。 */
        LocalDateTime sentAt,
        /** 商家确认收到退货的时间。 */
        LocalDateTime receivedAt
    ) {
    }

    public record RefundInfo(
        /** 退款尝试状态，取值见 RefundAttemptStatus。 */
        String status,
        /** 本系统提交给支付渠道的退款请求号。 */
        String outRequestNo,
        /** 请求发起时间。 */
        LocalDateTime requestedAt,
        /** 处理结果确认时间。 */
        LocalDateTime resolvedAt
    ) {
    }
}
