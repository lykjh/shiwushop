package com.shiwushop.aftersale.vo.timeout;

import com.shiwushop.aftersale.entity.timeout.AfterSaleTimeoutTask;

import java.util.List;

public record AfterSaleTimeoutPageVO(
    /** 当前对象的明细列表。 */
    List<AfterSaleTimeoutTask> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页的向前 ID 游标。 */
    Long nextBeforeId
) {
}
