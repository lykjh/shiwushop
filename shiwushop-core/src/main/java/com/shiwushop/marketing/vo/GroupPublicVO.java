package com.shiwushop.marketing.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record GroupPublicVO(
    /** 当前记录 ID。 */
    long id,
    /** 关联营销活动 ID。 */
    long activityId,
    /** 活动商品范围记录 ID，不是 SKU ID。 */
    long activitySkuId,
    /** 商品规格（SKU）ID。 */
    long skuId,
    /** 拼团状态：FORMING 组团中、SUCCEEDED 成团、FAILED 失败、CLOSED 关闭。 */
    String status,
    /** 该团要求的成团人数。 */
    int requiredMembers,
    /** 该团已付款人数。 */
    int paidMembers,
    /** 该团剩余可参与名额。 */
    int remainingSeats,
    /** 业务开启时间。 */
    OffsetDateTime openedAt,
    /** 有效截止时间。 */
    OffsetDateTime expiresAt,
    /** 拼团成功时间。 */
    OffsetDateTime succeededAt
) {
    public record Page(
        /** 当前对象的明细列表。 */
        List<GroupPublicVO> items,
        /** 是否还有下一页。 */
        boolean hasMore,
        /** 下一页的起始 ID 游标。 */
        Long nextAfterId
    ) {
    }
}
