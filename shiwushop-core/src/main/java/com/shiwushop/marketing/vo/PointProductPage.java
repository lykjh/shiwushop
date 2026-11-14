package com.shiwushop.marketing.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record PointProductPage(
    /** 当前对象的明细列表。 */
    List<PointProduct> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页的起始 ID 游标。 */
    Long nextAfterId
) {
    public record PointProduct(
        /** 关联营销活动 ID。 */
        long activityId,
        /** 活动商品范围记录 ID，不是 SKU ID。 */
        long activitySkuId,
        /** 商品规格（SKU）ID。 */
        long skuId,
        /** 商品名称。 */
        String productName,
        /** 商品封面媒体 ID。 */
        Long coverMediaId,
        /** 所属店铺 ID。 */
        long shopId,
        /** 单件兑换所需的积分最小单位数；100 单位为 1 积分。 */
        long pointCostUnits,
        /** 每个账号的累计参与上限。 */
        Integer perUserLimit,
        /** 活动库存池中可用件数。 */
        int availableQuantity,
        /** 活动开始时间。 */
        OffsetDateTime startAt,
        /** 活动结束时间。 */
        OffsetDateTime endAt
    ) {
    }
}
