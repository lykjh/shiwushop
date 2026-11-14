package com.shiwushop.marketing.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shiwushop.marketing.dto.MemberPrices;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record ActivityPublicVO(
    /** 当前记录 ID。 */
    Long id,
    /** 同一活动跨修订版本的业务代码。 */
    String activityCode,
    /** 活动版本号。 */
    int versionNo,
    /** 名称。 */
    String name,
    /** 营销活动类型。 */
    String type,
    /** 活动状态：DRAFT、PENDING_REVIEW、APPROVED、RUNNING、ENDED、REJECTED、PAUSED、TERMINATED。 */
    String status,
    /** 活动开始时间。 */
    OffsetDateTime startAt,
    /** 活动结束时间。 */
    OffsetDateTime endAt,
    /** 允许参与活动的会员等级代码列表。 */
    List<String> eligibleLevelCodes,
    /** 面向用户展示的活动规则说明。 */
    String rulesText,
    /** 活动适用的商品范围。 */
    List<Scope> scopes,
    /** 满减或满赠门槛阶梯。 */
    List<Tier> tiers,
    /** 发券模板规则。 */
    CouponRule couponRule
) {
    public record Card(
        /** 当前记录 ID。 */
        Long id,
        /** 同一活动跨修订版本的业务代码。 */
        String activityCode,
        /** 活动版本号。 */
        int versionNo,
        /** 名称。 */
        String name,
        /** 营销活动类型。 */
        String type,
        /** 活动状态：DRAFT、PENDING_REVIEW、APPROVED、RUNNING、ENDED、REJECTED、PAUSED、TERMINATED。 */
        String status,
        /** 活动开始时间。 */
        OffsetDateTime startAt,
        /** 活动结束时间。 */
        OffsetDateTime endAt,
        /** 允许参与活动的会员等级代码列表。 */
        List<String> eligibleLevelCodes,
        /** 面向用户展示的活动规则说明。 */
        String rulesText
    ) {
    }

    public record Page(
        /** 当前对象的明细列表。 */
        List<Card> items,
        /** 是否还有下一页。 */
        boolean hasMore,
        /** 下一页查询游标。 */
        Long nextCursor
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scope(
        /** 当前记录 ID。 */
        Long id,
        /** 商品规格（SKU）ID。 */
        Long skuId,
        /** 所属店铺 ID。 */
        Long shopId,
        /** 商品在活动中的角色：SALE 销售、GIFT 赠品、REDEEM 积分兑换。 */
        String scopeRole,
        /** 白银、黄金及铂金三个等级的会员单价。 */
        MemberPrices memberPrices,
        /** 活动单价，单位元。 */
        BigDecimal activityPrice,
        /** 每个账号的累计参与上限。 */
        Integer perUserLimit,
        /** 活动商品总销售上限。 */
        Integer salesLimit,
        /** 拼团成团人数。 */
        Integer teamSize,
        /** 活动参与资格有效时长，单位秒。 */
        Integer participationSeconds,
        /** 砍价起始单价，单位元。 */
        BigDecimal bargainStartPrice,
        /** 砍价目标单价，单位元。 */
        BigDecimal bargainTargetPrice,
        /** 完成砍价所需的助力人数。 */
        Integer requiredHelpers,
        /** 每个账号在该活动每日助力次数上限。 */
        Integer dailyHelpLimit,
        /** 该活动商品允许同时进行的砍价场次总上限。 */
        Integer maxOpenSessions,
        /** 单件兑换所需的积分最小单位数；100 单位为 1 积分。 */
        Long pointCostUnits
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tier(
        /** 当前记录 ID。 */
        Long id,
        /** 享受该优惠所需的门槛金额，单位元。 */
        BigDecimal thresholdAmount,
        /** 达到阶梯门槛时减免的金额，单位元。 */
        BigDecimal reductionAmount,
        /** 赠品 SKU ID。 */
        Long giftSkuId,
        /** 满足阶梯条件时赠送的商品件数。 */
        Integer giftQuantity
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CouponRule(
        /** 单张优惠券面额，单位元。 */
        BigDecimal faceAmount,
        /** 享受该优惠所需的门槛金额，单位元。 */
        BigDecimal thresholdAmount,
        /** 每个账号累计可获得的券张数上限。 */
        Integer perUserLimit,
        /** 优惠券领取截止时间。 */
        OffsetDateTime claimEndAt,
        /** 优惠券有效期天数。 */
        Integer validityDays,
        /** 优惠券模板指定的统一到期时间。 */
        OffsetDateTime fixedExpireAt,
        /** 发券方式：MEMBERSHIP 月券、CLAIM 领取、CODE 兑换、COMPENSATION 补偿。 */
        String grantMode,
        /** MEMBERSHIP 月券模板面向的会员等级。 */
        String memberLevelCode
    ) {
    }
}
