package com.shiwushop.marketing.model;

import com.shiwushop.marketing.dto.CouponRuleDraft;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record ActivityCouponRule(
    /** 单张优惠券面额，单位元。 */
    BigDecimal faceAmount,
    /** 享受该优惠所需的门槛金额，单位元。 */
    BigDecimal thresholdAmount,
    /** 优惠券模板总发行张数上限。 */
    Integer issueLimit,
    /** 每个账号累计可获得的券张数上限。 */
    Integer perUserLimit,
    /** 优惠券领取截止时间。 */
    LocalDateTime claimEndAt,
    /** 优惠券有效期天数。 */
    Integer validityDays,
    /** 优惠券模板指定的统一到期时间。 */
    LocalDateTime fixedExpireAt,
    /** 发券方式：MEMBERSHIP 月券、CLAIM 领取、CODE 兑换、COMPENSATION 补偿。 */
    String grantMode,
    /** MEMBERSHIP 月券模板面向的会员等级；其他发券方式为空。 */
    String memberLevelCode
) {
    public CouponRuleDraft draft() {
        return new CouponRuleDraft(faceAmount, thresholdAmount, issueLimit, perUserLimit,
                claimEndAt.atOffset(ZoneOffset.ofHours(8)), validityDays,
                fixedExpireAt == null ? null : fixedExpireAt.atOffset(ZoneOffset.ofHours(8)), grantMode,
                memberLevelCode);
    }
}
