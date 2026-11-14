package com.shiwushop.trade.entity.status;

public enum PaymentAttemptStatus {
    /** 支付结果待确认。 */
    PENDING,
    /** 支付成功。 */
    SUCCESS,
    /** 支付尝试已关闭。 */
    CLOSED
}
