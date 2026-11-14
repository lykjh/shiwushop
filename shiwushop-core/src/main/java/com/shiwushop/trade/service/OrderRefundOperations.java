package com.shiwushop.trade.service;

import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.order.OrderItem;

import java.util.List;

public interface OrderRefundOperations {
    /** 已锁定并确认归属的零现金订单，取消未发货的剩余商品。 */
    void cancelUnshippedZeroCash(Order order,
                                 List<OrderItem> orderItems);

    /** 已锁定的失败团订单同事务登记系统全退责任，返回售后案 ID。 */
    Long prepareFailedGroupRefund(Order order,
                                  List<OrderItem> orderItems);

    /** 提交支付事实后，按系统售后案发起可重放的渠道退款。 */
    void refundFailedGroup(Long caseId);
}
