package com.shiwushop.trade.vo.order;

import java.util.List;

public record OrderSliceVO(
    /** 当前对象的明细列表。 */
    List<OrderSummaryVO> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页查询游标。 */
    Long nextCursor
) {
}
