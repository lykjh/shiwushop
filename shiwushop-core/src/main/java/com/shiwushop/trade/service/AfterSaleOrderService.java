package com.shiwushop.trade.service;

import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.order.OrderItem;
import com.shiwushop.trade.entity.payment.PaymentAttempt;
import com.shiwushop.trade.entity.shipment.Shipment;

/** 售后所需的订单事实及支付依据；售后不直接读取或写入交易 Mapper。 */
public interface AfterSaleOrderService {
    Order find(Long orderId);
    Order lock(Long orderId);
    OrderItem item(Long orderItemId);
    Shipment shipment(Long orderId);
    /** 返回成功且金额匹配的支付尝试，异常订单拒绝进入退款。 */
    PaymentAttempt paidAttempt(Order order);
}
