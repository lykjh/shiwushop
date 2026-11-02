package com.shiwushop.trade.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.order.OrderItem;
import com.shiwushop.trade.entity.payment.PaymentAttempt;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.trade.entity.status.PaymentAttemptStatus;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.trade.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.service.AfterSaleOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 保留原售后订单锁顺序；查询和锁都使用调用方事务连接。 */
@Service
@RequiredArgsConstructor
public class AfterSaleOrderServiceImpl implements AfterSaleOrderService {
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final ShipmentMapper shipments;
    private final PaymentAttemptMapper payments;

    @Override
    public Order find(Long orderId) { return orders.selectById(orderId); }
    @Override
    public Order lock(Long orderId) { return orders.selectForUpdate(orderId); }
    @Override
    public OrderItem item(Long itemId) { return items.selectById(itemId); }
    @Override
    public Shipment shipment(Long orderId) { return shipments.selectByOrderId(orderId); }
    @Override
    public PaymentAttempt paidAttempt(Order order) {
        PaymentAttempt paid = payments.selectLatest(order.getId());
        if (paid == null || paid.getStatus() != PaymentAttemptStatus.SUCCESS
                || paid.getAlipayTradeNo() == null || paid.getAmount().compareTo(order.getTotalAmount()) != 0)
            throw new BusinessException(409, "订单支付记录异常");
        return paid;
    }
}
