package com.shiwushop.aftersale.entity.status;

public enum AfterSaleStatus {
    /** 已申请售后，等待审核。 */
    REQUESTED,
    /** 审核驳回。 */
    REJECTED,
    /** 审核通过，等待买家寄回商品。 */
    WAITING_RETURN,
    /** 买家已寄回，等待商家确认收货。 */
    WAITING_RECEIPT,
    /** 退款处理中。 */
    REFUNDING,
    /** 退款已完成。 */
    REFUNDED,
    /** 已关闭。 */
    CLOSED
}
