package com.shiwushop.payment.vo;

import com.shiwushop.payment.entity.ReconciliationIssue;

import java.util.List;

public record ReconciliationIssuePage(
    /** 当前对象的明细列表。 */
    List<ReconciliationIssue> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页查询游标。 */
    Long nextCursor
) {
}
