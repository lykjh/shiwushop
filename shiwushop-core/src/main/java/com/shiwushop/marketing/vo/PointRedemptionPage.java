package com.shiwushop.marketing.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record PointRedemptionPage(
    /** 当前对象的明细列表。 */
    List<PointRedemption> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页的起始 ID 游标。 */
    Long nextAfterId
) {
    public record PointRedemption(
        /** 关联订单 ID。 */
        long orderId,
        /** 活动参与资格记录 ID。 */
        long participationId,
        /** 活动商品范围记录 ID，不是 SKU ID。 */
        long activitySkuId,
        /** 兑换订单已消耗的积分最小单位数；100 单位为 1 积分。 */
        long spentPointUnits,
        /** 兑换订单已退回的积分最小单位数；100 单位为 1 积分。 */
        long returnedPointUnits,
        /** 积分兑换订单处理状态。 */
        String status,
        /** 记录创建时间。 */
        OffsetDateTime createdAt
    ) {
    }
}
