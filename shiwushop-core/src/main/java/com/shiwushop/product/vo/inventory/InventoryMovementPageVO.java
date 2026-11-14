package com.shiwushop.product.vo.inventory;

import java.util.List;

public record InventoryMovementPageVO(
    /** 当前对象的明细列表。 */
    List<InventoryMovementVO> items,
    /** 是否还有下一页。 */
    boolean hasMore,
    /** 下一页查询游标。 */
    Long nextCursor
) {
}
