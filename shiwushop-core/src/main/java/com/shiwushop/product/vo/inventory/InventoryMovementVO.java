package com.shiwushop.product.vo.inventory;

import java.time.LocalDateTime;

public record InventoryMovementVO(
    /** 当前记录 ID。 */
    Long id,
    /** 库存变动原因代码。 */
    String reason,
    /** 本次库存增减件数，正数增加、负数减少。 */
    int delta,
    /** 本次变动后的余额。 */
    int balanceAfter,
    /** 关联订单明细 ID。 */
    Long orderItemId,
    /** 记录创建时间。 */
    LocalDateTime createdAt
) {
}
