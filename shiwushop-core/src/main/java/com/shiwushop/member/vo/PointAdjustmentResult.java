package com.shiwushop.member.vo;

public record PointAdjustmentResult(
    /** 本次操作产生的账本流水 ID。 */
    Long entryId,
    /** 所属用户 ID。 */
    Long userId,
    /** 本次原始调整后的可用积分最小单位数；100 单位为 1 积分。 */
    long availablePointUnits,
    /** 本次原始调整后的积分欠额最小单位数；100 单位为 1 积分。 */
    long pointDebtUnits
) {
}
