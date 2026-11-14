package com.shiwushop.marketing.vo;

import com.shiwushop.marketing.model.UserCoupon;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public record CouponVO(
    /** 当前记录 ID。 */
    Long id,
    /** 关联营销活动 ID。 */
    Long activityId,
    /** 发券义务 ID；同一义务可产生多张券。 */
    Long grantId,
    /** 名称。 */
    String name,
    /** 单张优惠券面额，单位元。 */
    BigDecimal faceAmount,
    /** 享受该优惠所需的门槛金额，单位元。 */
    BigDecimal thresholdAmount,
    /** 优惠券生效时间。 */
    OffsetDateTime validFrom,
    /** 有效截止时间。 */
    OffsetDateTime expiresAt,
    /** 券状态：AVAILABLE 可用、RESERVED 占用、USED 核销、EXPIRED 过期。 */
    String status,
    /** 优惠券适用的店铺 ID 列表。 */
    List<Long> shopIds,
    /** 优惠券适用的 SKU ID 列表。 */
    List<Long> skuIds,
    /** 当前占用该券的订单 ID。 */
    Long reservedOrderId,
    /** 已核销该券的订单 ID。 */
    Long usedOrderId
) {
    public static CouponVO from(UserCoupon coupon, List<Long> shops, List<Long> skus, LocalDateTime now) {
        String status = coupon.getStatus().equals("AVAILABLE") &&
                !coupon.getExpiresAt().isAfter(now) ? "EXPIRED" : coupon.getStatus();
        return new CouponVO(coupon.getId(), coupon.getActivityId(), coupon.getGrantId(), coupon.getName(),
                coupon.getFaceAmount(),
                coupon.getThresholdAmount(), coupon.getValidFrom().atOffset(ZoneOffset.ofHours(8)),
                coupon.getExpiresAt().atOffset(ZoneOffset.ofHours(8)), status, shops, skus, coupon.getReservedOrderId(),
                coupon.getUsedOrderId());
    }

    public record Page(
        /** 当前对象的明细列表。 */
        List<CouponVO> items,
        /** 是否还有下一页。 */
        boolean hasMore,
        /** 下一页查询游标。 */
        Long nextCursor
    ) {
    }
}
