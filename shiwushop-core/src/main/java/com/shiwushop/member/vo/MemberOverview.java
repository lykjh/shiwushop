package com.shiwushop.member.vo;

import java.math.BigDecimal;

public record MemberOverview(
    /** 会员等级代码。 */
    String levelCode,
    /** 账户累计成长金额，保留角分精度，单位元。 */
    BigDecimal growthAmount,
    /** 向下取整后用于展示的成长值，账务仍保留角分。 */
    long displayedGrowth,
    /** 距下一会员等级尚需的成长金额；最高等级时为空，单位元。 */
    BigDecimal amountToNextLevel,
    /** 尚待满足条件并入账的成长金额，单位元。 */
    BigDecimal pendingGrowthAmount,
    /** 可用积分最小单位数；100 单位为 1 积分。 */
    long availablePointUnits,
    /** 已占用且暂不可另行使用的积分最小单位数；100 单位为 1 积分。 */
    long heldPointUnits,
    /** 待由后续积分抵扣的欠额最小单位数；100 单位为 1 积分。 */
    long pointDebtUnits
) {
}
