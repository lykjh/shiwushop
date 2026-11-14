package com.shiwushop.member.vo;

import java.math.BigDecimal;

public record MemberLevelVO(
    /** 会员等级代码，与账号权限角色无关。 */
    String code,
    /** 名称。 */
    String name,
    /** 达到该会员等级所需的最低成长金额，单位元。 */
    BigDecimal minGrowthAmount,
    /** 该会员等级每月可得券张数。 */
    int monthlyCouponCount,
    /** 该等级月券的单张面额，单位元。 */
    BigDecimal monthlyCouponFaceAmount,
    /** 该等级月券的使用门槛金额，单位元。 */
    BigDecimal monthlyCouponThresholdAmount
) {
}
