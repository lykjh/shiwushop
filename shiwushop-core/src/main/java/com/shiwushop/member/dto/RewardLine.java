package com.shiwushop.member.dto;

import java.math.BigDecimal;

/** 由持有订单锁的交易/售后调用方提供成交快照及已确认退款事实。 */
public record RewardLine(
    /** 关联订单明细 ID。 */
    Long orderItemId,
    /** 已支付金额，单位元。 */
    BigDecimal paidAmount,
    /** 已确认退回的金额，单位元。 */
    BigDecimal refundedAmount
) {
    public BigDecimal retainedAmount() {
        return paidAmount.subtract(refundedAmount);
    }
}
