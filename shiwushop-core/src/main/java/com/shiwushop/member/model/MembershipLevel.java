package com.shiwushop.member.model;

import java.math.BigDecimal;

/** 等级门槛来自 membership_levels；会员等级与账号权限角色无关。 */
public record MembershipLevel(
    /** 会员等级代码。 */
    String levelCode,
    /** 达到该会员等级所需的最低成长金额，单位元。 */
    BigDecimal minGrowthAmount
) {
}
