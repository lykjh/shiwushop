package com.shiwushop.marketing.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record MonthlyBenefitVO(
    /** 月券所属月份，格式 yyyy-MM。 */
    String month,
    /** 权益判定时冻结的会员等级代码。 */
    String eligibleLevelCode,
    /** 本次发券义务应发的总张数。 */
    int entitledCount,
    /** 单张优惠券面额，单位元。 */
    BigDecimal faceAmount,
    /** 享受该优惠所需的门槛金额，单位元。 */
    BigDecimal thresholdAmount,
    /** 该月权益原定到期时间；补发不延长有效期。 */
    OffsetDateTime expiresAt,
    /** 月券义务状态；NOT_ELIGIBLE 表示不具备当月资格。 */
    String state,
    /** 该发券义务下的用户券实例列表。 */
    List<CouponVO> coupons
) {
    public record AdminGrant(
        /** 发券义务详情。 */
        @JsonUnwrapped CouponGrantVO grant,
        /** 所属用户 ID。 */
        long userId,
        /** 关联营销活动 ID。 */
        Long activityId,
        /** 月券权益所属月份，格式 yyyy-MM；非月券为空。 */
        String benefitMonth,
        /** 权益判定时冻结的会员等级代码。 */
        String eligibleLevelCode,
        /** 本次发券失败的错误代码。 */
        String errorCode,
        /** 任务允许再次尝试的时间。 */
        OffsetDateTime nextAttemptAt
    ) {
    }

    public record Page(
        /** 当前对象的明细列表。 */
        List<AdminGrant> items,
        /** 是否还有下一页。 */
        boolean hasMore,
        /** 下一页查询游标。 */
        Long nextCursor
    ) {
    }

    public record Retry(
        /** 发券义务 ID；同一义务可产生多张券。 */
        long grantId,
        /** 人工补发操作后的义务状态，重新排队不代表已经发券。 */
        String state,
        /** 任务允许再次尝试的时间。 */
        OffsetDateTime nextAttemptAt
    ) {
    }
}
