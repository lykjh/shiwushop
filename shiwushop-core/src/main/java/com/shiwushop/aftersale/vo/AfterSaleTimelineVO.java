package com.shiwushop.aftersale.vo;

import java.time.LocalDateTime;
import java.util.List;

public record AfterSaleTimelineVO(
    /** 当前售后单状态。 */
    String currentStatus,
    /** 当前对象的明细列表。 */
    List<Event> items,
    /** 下一页的向前 ID 游标。 */
    Long nextBeforeId
) {
    public record Event(
        /** 当前记录 ID。 */
        Long id,
        /** 审计操作代码。 */
        String action,
        /** 审计或差异摘要。 */
        String summary,
        /** 审计事件发生时间。 */
        LocalDateTime occurredAt
    ) {
    }
}
