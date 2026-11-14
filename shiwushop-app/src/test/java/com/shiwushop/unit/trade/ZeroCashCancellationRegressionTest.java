package com.shiwushop.unit.trade;

import com.shiwushop.aftersale.service.AfterSaleService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.marketing.service.MarketingOrderSettlement;
import com.shiwushop.member.service.MemberRewardService;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.payment.service.AlipayGateway;
import com.shiwushop.product.service.ProductStockService;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.shipment.Shipment;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentItemMapper;
import com.shiwushop.trade.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentAuditMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.service.impl.OrderPaymentServiceImpl;
import com.shiwushop.trade.service.impl.ShipmentServiceImpl;
import com.shiwushop.user.service.AccountAccess;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZeroCashCancellationRegressionTest {
    @Test
    void cancellationStopsFulfillmentAndRepeatedRequestDoesNotCreateAnotherAfterSale() {
        OrderMapper orders = mock(OrderMapper.class);
        OrderItemMapper items = mock(OrderItemMapper.class);
        AfterSaleService afterSales = mock(AfterSaleService.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        Order order = new Order();
        order.setId(10L);
        order.setUserId(7L);
        order.setShopId(5L);
        order.setStatus(OrderStatus.PAID);
        order.setSettlementKind("ZERO_CASH");
        order.setFulfillmentState("READY");
        when(orders.selectForUpdate(10L)).thenReturn(order);
        when(orders.stopZeroCashFulfillment(10L)).thenReturn(1);
        when(items.selectForOrder(10L)).thenReturn(List.of());

        OrderPaymentServiceImpl service = new OrderPaymentServiceImpl(orders, items,
                mock(PaymentAttemptMapper.class), mock(AlipayGateway.class), mock(ProductStockService.class),
                transactions, mock(AccountAccess.class), Clock.systemUTC(), mock(MemberRewardService.class),
                mock(MarketingOrderSettlement.class), afterSales);

        assertEquals("CANCELLED", service.cancelOrder(7L, 10L));
        when(orders.selectOne(any())).thenReturn(order);
        assertEquals("PAID", service.status(7L, 10L).orderStatus());
        assertEquals("CANCELLED", service.cancelOrder(7L, 10L));
        ShipmentMapper shipments = mock(ShipmentMapper.class);
        ShipmentServiceImpl shipping = new ShipmentServiceImpl(shipments, mock(ShipmentItemMapper.class),
                orders, mock(OrderItemMapper.class),
                mock(OutboxEventMapper.class), mock(ShipmentAuditMapper.class),
                mock(MerchantShopAccess.class), mock(AccountAccess.class), Clock.systemUTC(),
                mock(MemberRewardService.class));
        BusinessException stopped = assertThrows(BusinessException.class,
                () -> shipping.ship(8L, 5L, 10L, new ShipmentCreateDTO("顺丰", "SF-1")));
        assertAll(
                () -> assertEquals("STOPPED", order.getFulfillmentState()),
                () -> assertEquals(409, stopped.getCode()),
                () -> verify(shipments, never()).insert(any(Shipment.class)),
                () -> verify(orders, times(1)).stopZeroCashFulfillment(10L),
                () -> verify(afterSales, times(1)).cancelUnshippedZeroCash(order, List.of()));
    }
}
