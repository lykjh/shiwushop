package com.shiwushop.trade.vo.payment;

import java.time.LocalDateTime;

public record PaymentStatusVO(
    /** 关联订单 ID。 */
    Long orderId,
    /** 订单支付状态。 */
    String orderStatus,
    /** 订单付款截止时间。 */
    LocalDateTime payExpiresAt,
    /** 订单的支付尝试信息。 */
    Payment payment
) {
    public record Payment(
        /** 当前记录 ID。 */
        Long id,
        /** 支付渠道。 */
        String channel,
        /** 支付尝试状态，取值见 PaymentAttemptStatus。 */
        String status,
        /** 支付或结算完成时间。 */
        LocalDateTime paidAt
    ) {
    }
}
