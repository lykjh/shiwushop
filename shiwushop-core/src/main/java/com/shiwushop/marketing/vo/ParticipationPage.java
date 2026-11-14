package com.shiwushop.marketing.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record ParticipationPage(
    /** 当前对象的明细列表。 */
    List<Participation> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页的起始 ID 游标。 */
    Long nextAfterId
) {
    public record Participation(
        /** 当前记录 ID。 */
        long id,
        /** 营销活动类型。 */
        String type,
        /** 关联营销活动 ID。 */
        long activityId,
        /** 活动商品范围记录 ID，不是 SKU ID。 */
        long activitySkuId,
        /** 关联订单 ID。 */
        Long orderId,
        /** 拼团团实例 ID。 */
        Long groupId,
        /** 砍价实例 ID。 */
        Long bargainId,
        /** 商品件数。 */
        int quantity,
        /** 活动参与资格状态。 */
        String status,
        /** 有效截止时间。 */
        OffsetDateTime expiresAt,
        /** 支付或结算完成时间。 */
        OffsetDateTime paidAt
    ) {
    }
}
