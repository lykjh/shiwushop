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
import com.shiwushop.trade.mapper.shipment.ShipmentItemMapper;
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
    private final ShipmentItemMapper shipmentItems;
    private final PaymentAttemptMapper payments;

    @Override
    public java.util.List<com.shiwushop.member.dto.RewardLine> rewardLines(Long orderId) {
        return items.rewardLines(orderId);
    }

    @Override
    public Order find(Long orderId) {
        return orders.selectById(orderId);
    }

    @Override
    public Order lock(Long orderId) {
        return orders.selectForUpdate(orderId);
    }

    @Override
    public OrderItem item(Long itemId) {
        return items.selectById(itemId);
    }

    @Override
    public Shipment shipment(Long orderId) {
        return shipments.selectByOrderIdForUpdate(orderId);
    }

    @Override
    public java.util.List<Shipment> shipments(Long orderId) {
        return shipments.selectAllByOrderId(orderId);
    }

    @Override
    public int shippedQuantity(Long orderItemId) {
        return shipmentItems.shippedQuantity(orderItemId);
    }

    @Override
    public int shipmentQuantity(Long shipmentId, Long orderItemId) {
        return shipmentItems.quantity(shipmentId, orderItemId);
    }

    @Override
    public void stopZeroCashWhenFullyRefunded(Order order) {
        if (!("ZERO_CASH".equals(order.getSettlementKind()) || "POINTS".equals(order.getSettlementKind()))
                || !shipments.selectAllByOrderId(order.getId()).isEmpty()
                || !orders.fullyRefunded(order.getId()))
            return;
        if (orders.stopZeroCashFulfillment(order.getId()) != 1)
            throw new BusinessException(409, "订单履约状态冲突");
    }

    @Override
    public void stopFailedGroupFulfillment(Long orderId) {
        if (orders.stopFailedGroupFulfillment(orderId) != 1)
            throw new BusinessException(409, "失败团履约状态冲突");
    }

    @Override
    public PaymentAttempt paidAttempt(Order order) {
        if (!"CASH".equals(order.getSettlementKind()))
            throw new BusinessException(409, "非现金订单没有渠道支付记录");
        PaymentAttempt paid = payments.selectLatest(order.getId());
        if (paid == null || paid.getStatus() != PaymentAttemptStatus.SUCCESS
                || paid.getAlipayTradeNo() == null || paid.getAmount().compareTo(order.getTotalAmount()) != 0)
            throw new BusinessException(409, "订单支付记录异常");
        return paid;
    }
}
