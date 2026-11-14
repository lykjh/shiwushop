package com.shiwushop.trade.service.impl;

import com.shiwushop.member.service.MemberRewardService;
import com.shiwushop.member.service.MemberTaskService;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.service.AfterSaleOrderService;
import com.shiwushop.trade.service.OrderRewardService;
import com.shiwushop.trade.entity.status.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderRewardServiceImpl implements OrderRewardService {
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final ShipmentMapper shipments;
    private final AfterSaleOrderService paymentFacts;
    private final MemberRewardService rewards;
    private final MemberTaskService tasks;

    /** 与付款/退款持同一个订单锁；补发和冲回一并提交。 */
    @Override
    @Transactional
    public void reconcile(Long orderId, Long taskId, String token) {
        var order = orders.selectForUpdate(orderId);
        if (!tasks.ownsClaim(taskId, token))
            return;
        if (order.getStatus() != OrderStatus.PAID || !"CASH".equals(order.getSettlementKind())) {
            tasks.release(taskId, token, null);
            return;
        }
        var payment = paymentFacts.paidAttempt(order);
        var lines = items.rewardLines(orderId);
        rewards.paymentConfirmed(order.getUserId(), orderId, payment.getPaidAt(), lines);
        if (order.getFullyReceivedAt() != null) {
            rewards.receiptConfirmed(orderId, order.getFullyReceivedAt());
            rewards.growthDue(order.getUserId(), orderId, order.getFullyReceivedAt(),
                    shipments.unresolvedAfterSaleCount(orderId) > 0, lines);
        }
        tasks.release(taskId, token, null);
    }
}
