package com.shiwushop.unit.trade;

import com.shiwushop.common.BusinessException;
import com.shiwushop.payment.service.AlipayGateway;
import com.shiwushop.product.service.ProductStockService;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.trade.service.impl.OrderPaymentServiceImpl;
import com.shiwushop.user.service.AccountAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderPaymentCancellationTests {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final AlipayGateway gateway = mock(AlipayGateway.class);
    private final ProductStockService stock = mock(ProductStockService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final OrderPaymentServiceImpl service = new OrderPaymentServiceImpl(orders, mock(OrderItemMapper.class),
            mock(PaymentAttemptMapper.class), gateway, stock, transactions, mock(AccountAccess.class), Clock.systemUTC());

    @BeforeEach void transaction() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test void cancelledRetryDoesNotRequestGatewayOrReturnStock() {
        Order order = new Order(); order.setUserId(7L); order.setStatus(OrderStatus.CANCELLED);
        when(orders.selectForUpdate(10L)).thenReturn(order);
        assertEquals("CANCELLED", service.cancelOrder(7L, 10L));
        verify(orders, never()).requestCancellation(any(), any());
        verifyNoInteractions(gateway, stock);
    }

    @Test void anotherBuyersOrderIsHiddenAndPaidOrderConflicts() {
        Order order = new Order(); order.setUserId(8L); order.setStatus(OrderStatus.UNPAID);
        when(orders.selectForUpdate(10L)).thenReturn(order);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.cancelOrder(7L, 10L)).getCode());
        order.setUserId(7L); order.setStatus(OrderStatus.PAID);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.cancelOrder(7L, 10L)).getCode());
        verify(orders, never()).requestCancellation(any(), any());
        verifyNoInteractions(gateway, stock);
    }
}
