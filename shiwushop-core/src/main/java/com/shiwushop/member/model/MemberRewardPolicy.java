package com.shiwushop.member.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** 已校验的成交/账本事实的精确计算；不读取当前商品价格或当前店主。 */
public final class MemberRewardPolicy {
    private MemberRewardPolicy() {
    }

    public static String levelFor(BigDecimal growthAmount, List<MembershipLevel> levels) {
        return levels.stream().filter(level -> growthAmount.compareTo(level.minGrowthAmount()) >= 0)
                .max(Comparator.comparing(MembershipLevel::minGrowthAmount))
                .orElseThrow(() -> new IllegalStateException("会员等级缺少适用的基础档位")).levelCode();
    }

    /** 一分实付对应一个积分最小单位；精度或容量错误显式失败，不舍弃角分。 */
    public static long pointUnits(BigDecimal paidAmount) {
        return paidAmount.movePointRight(2).longValueExact();
    }

    public static LocalDateTime growthEligibleAt(LocalDateTime confirmedAt) {
        return confirmedAt.plusDays(7);
    }

    public static LocalDateTime pointsExpireAt(LocalDateTime firstCreditedAt) {
        return firstCreditedAt.plusMonths(12);
    }

    /** 原成长减当前保留实付，再减来源已冲回；入账前退款和人工调整都不会重复扣减。 */
    public static BigDecimal additionalGrowthReversal(BigDecimal earnedAmount,
                                                      BigDecimal retainedAmount,
                                                      BigDecimal alreadyReversed) {
        return earnedAmount.subtract(retainedAmount).subtract(alreadyReversed).max(BigDecimal.ZERO);
    }
}
