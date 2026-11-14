package com.shiwushop.marketing.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record QuoteVO(
    /** 服务端报价指纹，下单时复核报价是否变化。 */
    String quoteHash,
    /** 本次服务端报价计算时间。 */
    OffsetDateTime calculatedAt,
    /** 交易类型：NORMAL 普通、FLASH 秒杀、GROUP 拼团、BARGAIN 砍价、POINTS 积分兑换。 */
    String businessType,
    /** 本次报价采用的会员等级代码。 */
    String memberLevelCode,
    /** 按普通原价计算的商品总额，单位元。 */
    BigDecimal originalAmount,
    /** 分配给当前对象的优惠金额，单位元。 */
    BigDecimal discountAmount,
    /** 现金应付总额，单位元。 */
    BigDecimal totalAmount,
    /** 本次结算应付的积分最小单位总数；100 单位为 1 积分。 */
    long totalPointUnits,
    /** 按店铺分组的报价结果。 */
    List<Shop> shops,
    /** 本次报价的候选券及可用性。 */
    List<CouponCandidate> couponCandidates,
    /** 本次报价的预计成长与积分。 */
    RewardPreview rewardPreview
) {
    public record Shop(
        /** 所属店铺 ID。 */
        Long shopId,
        /** 按普通原价计算的商品总额，单位元。 */
        BigDecimal originalAmount,
        /** 分配给当前对象的优惠金额，单位元。 */
        BigDecimal discountAmount,
        /** 现金应付总额，单位元。 */
        BigDecimal totalAmount,
        /** 本单或本店应付的积分最小单位数；100 单位为 1 积分。 */
        long pointUnits,
        /** 本店本次选中的用户券 ID；未用券时为空。 */
        Long selectedUserCouponId,
        /** 选用的满减活动 ID。 */
        Long reductionActivityId,
        /** 选用的满赠活动 ID。 */
        Long giftActivityId,
        /** 当前对象的明细列表。 */
        List<Item> items
    ) {
    }

    public record Item(
        /** 商品规格（SKU）ID。 */
        Long skuId,
        /** 商品件数。 */
        int quantity,
        /** 订单明细类型：SALE 销售、GIFT 赠品、REDEEM 积分兑换。 */
        String itemKind,
        /** 优惠前的普通单价快照，单位元。 */
        BigDecimal originalUnitPrice,
        /** 普通价、会员价及活动价择优后的单价，单位元。 */
        BigDecimal unitPrice,
        /** 单价优惠后、满减及用券前的明细小计，单位元。 */
        BigDecimal lineAmount,
        /** 分配给当前对象的优惠金额，单位元。 */
        BigDecimal discountAmount,
        /** 扣除优惠后的应付金额，单位元。 */
        BigDecimal payableAmount,
        /** 活动商品范围记录 ID，不是 SKU ID。 */
        Long activitySkuId,
        /** 该明细参与的优惠及金额。 */
        List<Benefit> benefits
    ) {
    }

    public record Benefit(
        /** 关联营销活动 ID。 */
        Long activityId,
        /** 该项优惠类型，如 MEMBER、LIMITED、COUPON 或 FULL_REDUCTION。 */
        String kind,
        /** 分配给当前对象的优惠金额，单位元。 */
        BigDecimal discountAmount
    ) {
    }

    public record CouponCandidate(
        /** 用户持有的优惠券实例 ID。 */
        Long userCouponId,
        /** 所属店铺 ID。 */
        Long shopId,
        /** 该优惠券是否可用于本次报价。 */
        boolean usable,
        /** 该券不可用于当前报价的原因代码；可用时为空。 */
        String reason,
        /** 本次报价实际可抵扣的金额，不直接以券面额作为推荐依据，单位元。 */
        BigDecimal savingAmount
    ) {
    }

    public record RewardPreview(
        /** 本次报价预计可获得的积分最小单位数；100 单位为 1 积分。 */
        long pointUnits,
        /** 本次报价预计可获得的成长金额，单位元。 */
        BigDecimal growthAmount,
        /** 权益不入账的原因代码。 */
        String exclusionReason
    ) {
    }
}
