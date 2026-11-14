package com.shiwushop.trade.vo.payment;

public record PaymentStartVO(
    /** 支付记录 ID。 */
    Long paymentId,
    /** 关联订单 ID。 */
    Long orderId,
    /** 支付渠道。 */
    String channel,
    /** 支付尝试状态，取值见 PaymentAttemptStatus。 */
    String status,
    /** 支付跳转地址。 */
    String payUrl
) {
}
