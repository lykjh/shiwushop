package com.shiwushop.product.dto.inventory;

public enum InventoryMovementReason {
    /** 下单扣减库存。 */
    ORDER_DEDUCT,
    /** 取消订单回补库存。 */
    ORDER_CANCEL_RETURN,
    /** 售后退货确认后回补库存。 */
    REFUND_RETURN,
    /** 商家手动调整库存。 */
    MERCHANT_ADJUST
}
