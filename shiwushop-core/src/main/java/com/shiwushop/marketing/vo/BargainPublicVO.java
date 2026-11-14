package com.shiwushop.marketing.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record BargainPublicVO(
    /** 当前记录 ID。 */
    long id,
    /** 关联营销活动 ID。 */
    long activityId,
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    long activitySkuId,
    /** 商品规格（SKU）ID。 */
    long skuId,
    /** 砍价状态：ACTIVE 助力中、QUALIFIED 可购买、ORDERED 已下单、EXPIRED 过期、CLOSED 关闭。 */
    String status,
    /** 本场砍价的起始金额，单位元。 */
    BigDecimal startAmount,
    /** 本场砍价的目标金额，单位元。 */
    BigDecimal targetAmount,
    /** 砍价当前金额，单位元。 */
    BigDecimal currentAmount,
    /** 完成砍价所需的助力人数。 */
    int requiredHelpers,
    /** 当前已成功助力次数。 */
    int helpCount,
    /** 本场砍价助力截止时间。 */
    OffsetDateTime helpExpiresAt,
    /** 砍价成功后的购买截止时间。 */
    OffsetDateTime purchaseExpiresAt
) {
    public record Mine(
        /** 当前记录 ID。 */
        long id,
        /** 关联营销活动 ID。 */
        long activityId,
        /** 活动商品范围记录 ID，不是 SKU ID。 */
        long activitySkuId,
        /** 商品规格（SKU）ID。 */
        long skuId,
        /** 砍价状态：ACTIVE 助力中、QUALIFIED 可购买、ORDERED 已下单、EXPIRED 过期、CLOSED 关闭。 */
        String status,
        /** 本场砍价的起始金额，单位元。 */
        BigDecimal startAmount,
        /** 本场砍价的目标金额，单位元。 */
        BigDecimal targetAmount,
        /** 砍价当前金额，单位元。 */
        BigDecimal currentAmount,
        /** 完成砍价所需的助力人数。 */
        int requiredHelpers,
        /** 当前已成功助力次数。 */
        int helpCount,
        /** 本场砍价助力截止时间。 */
        OffsetDateTime helpExpiresAt,
        /** 砍价成功后的购买截止时间。 */
        OffsetDateTime purchaseExpiresAt,
        /** 活动参与资格记录 ID。 */
        long participationId,
        /** 关联订单 ID。 */
        Long orderId,
        /** 当前砍价资格是否允许下单。 */
        boolean canPurchase
    ) {
    }

    public record Page(
        /** 当前对象的明细列表。 */
        List<Mine> items,
        /** 是否还有下一页。 */
        boolean hasMore,
        /** 下一页的起始 ID 游标。 */
        Long nextAfterId
    ) {
    }
}
