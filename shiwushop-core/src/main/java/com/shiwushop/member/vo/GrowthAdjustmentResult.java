package com.shiwushop.member.vo;

import java.math.BigDecimal;

public record GrowthAdjustmentResult(
    /** 本次操作产生的账本流水 ID。 */
    Long entryId,
    /** 所属用户 ID。 */
    Long userId,
    /** 本次原始调整后的成长金额；同键重放不返回当前余额，单位元。 */
    BigDecimal growthAmount,
    /** 会员等级代码。 */
    String levelCode
) {
}
