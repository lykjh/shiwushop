package com.shiwushop.unit.order;

import com.shiwushop.common.BusinessException;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.trade.entity.payment.PaymentAttempt;
import com.shiwushop.trade.entity.status.PaymentAttemptStatus;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.order.OrderItemMapper;
import com.shiwushop.trade.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.trade.mapper.shipment.ShipmentMapper;
import com.shiwushop.trade.service.impl.AfterSaleOrderServiceImpl;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AfterSaleOrderServiceImplTests {
    final OrderMapper orders = mock(OrderMapper.class);
    final PaymentAttemptMapper payments = mock(PaymentAttemptMapper.class);
    final AfterSaleOrderServiceImpl service = new AfterSaleOrderServiceImpl(orders,
            mock(OrderItemMapper.class), mock(ShipmentMapper.class), payments);

    @Test
    void orderLockRemainsInTheTradingModule() {
        service.lock(5L);
        verify(orders).selectForUpdate(5L);
    }

    @Test
    void refundRequiresASuccessfulPaymentOfTheSameAmount() {
        Order order = new Order(); order.setId(5L); order.setTotalAmount(BigDecimal.TEN);
        PaymentAttempt attempt = new PaymentAttempt(); attempt.setStatus(PaymentAttemptStatus.SUCCESS);
        attempt.setAlipayTradeNo("202611020005"); attempt.setAmount(BigDecimal.TEN);
        when(payments.selectLatest(5L)).thenReturn(attempt);
        assertSame(attempt, service.paidAttempt(order));
        attempt.setAmount(BigDecimal.ONE);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.paidAttempt(order)).getCode());
        attempt.setAmount(BigDecimal.TEN); attempt.setStatus(PaymentAttemptStatus.PENDING);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.paidAttempt(order)).getCode());
    }

    @Test
    void missingPaymentIsRejectedBeforeCallingTheRefundChannel() {
        Order order = new Order(); order.setId(5L); order.setTotalAmount(BigDecimal.TEN);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.paidAttempt(order)).getCode());
    }
}
