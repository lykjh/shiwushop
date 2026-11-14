package com.shiwushop.member.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemberRewardTask(
    /** 权益处理任务 ID。 */
    Long id,
    /** 所属用户 ID。 */
    Long userId,
    /** 关联订单明细 ID。 */
    Long orderItemId,
    /** 权益类型：GROWTH 成长、POINTS 积分。 */
    String rewardKind,
    /** 权益任务状态，区分等待支付/收货/售后、待入账、已入账、排除及人工处理。 */
    String status,
    /** 该权益已入账的成长金额，单位元。 */
    BigDecimal creditedGrowthAmount,
    /** 该权益已入账的积分最小单位数；100 单位为 1 积分。 */
    long creditedPointUnits,
    /** 满足处理条件的最早时间。 */
    LocalDateTime eligibleAt
) {
}
