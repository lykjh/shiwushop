package com.shiwushop.trade.vo.order;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;

public record OrderRewardSummary(
    /** 关联订单 ID。 */
    @JsonIgnore long orderId,
    /** 订单积分权益汇总状态。 */
    String pointStatus,
    /** 订单成长权益汇总状态。 */
    String growthStatus,
    /** 该权益已入账的积分最小单位数；100 单位为 1 积分。 */
    long creditedPointUnits,
    /** 该权益已入账的成长金额，单位元。 */
    BigDecimal creditedGrowthAmount,
    /** 订单已冲回的积分最小单位数；100 单位为 1 积分。 */
    long clawedBackPointUnits,
    /** 订单已冲回的成长金额，单位元。 */
    BigDecimal clawedBackGrowthAmount,
    /** 权益不入账的原因代码。 */
    String exclusionReason
) {
}
