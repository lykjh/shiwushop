package com.shiwushop.marketing.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record StockEntryPage(
    /** 当前对象的明细列表。 */
    List<StockEntry> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页的起始 ID 游标。 */
    Long nextAfterId
) {
    public record StockEntry(
        /** 当前记录 ID。 */
        long id,
        /** 活动库存流水的变动原因代码。 */
        String reason,
        /** 本次累计划入库存的增减件数。 */
        int allocatedDelta,
        /** 本次可用余额变化量。 */
        int availableDelta,
        /** 本次占用余额变化量。 */
        int heldDelta,
        /** 本次已消耗库存变化件数。 */
        int consumedDelta,
        /** 本次退回普通库存变化件数。 */
        int returnedDelta,
        /** 关联订单明细 ID。 */
        Long orderItemId,
        /** 活动参与资格记录 ID。 */
        Long participationId,
        /** 记录创建时间。 */
        OffsetDateTime createdAt
    ) {
    }
}
