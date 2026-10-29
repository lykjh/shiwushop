package com.shiwushop.payment;

import com.shiwushop.account.auth.AccountAccess;
import com.shiwushop.commerce.payment.AlipayGateway;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.commerce.entity.payment.PaymentAttempt;
import com.shiwushop.commerce.entity.status.OrderStatus;
import com.shiwushop.commerce.entity.status.PaymentAttemptStatus;
import com.shiwushop.commerce.mapper.order.OrderItemMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.common.outbox.OutboxEventMapper;
import com.shiwushop.commerce.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.catalog.mapper.product.InventoryMovementMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.commerce.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentQrLeaseUnitTests {
    @Mock OrderMapper orders;
    @Mock OrderItemMapper orderItems;
    @Mock PaymentAttemptMapper payments;
    @Mock SkuMapper skus;
    @Mock ProductMapper products;
    @Mock InventoryMovementMapper movements;
    @Mock OutboxEventMapper outbox;
    @Mock AlipayGateway gateway;
    @Mock PlatformTransactionManager transactions;
    @Mock AccountAccess accounts;
    @Mock Clock clock;
    @InjectMocks PaymentServiceImpl service;

    @Test
    void commitsClaimBeforeCallingGatewayAndCommitsSavedQrAfterward() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneId.of("Asia/Shanghai"));
        when(clock.instant()).thenAnswer(invocation -> fixed.instant());
        when(clock.getZone()).thenReturn(fixed.getZone());
        LocalDateTime now = LocalDateTime.now(fixed);
        Order order = new Order();
        order.setId(10L);
        order.setStatus(OrderStatus.UNPAID);
        order.setPayExpiresAt(now.plusMinutes(15));
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(20L);
        attempt.setOrderId(order.getId());
        attempt.setChannel("ALIPAY_SANDBOX");
        attempt.setOutTradeNo("SHIWUSHOP-test-20");
        attempt.setAmount(new BigDecimal("20.00"));
        attempt.setAppId("sandbox-app");
        attempt.setSellerId("sandbox-seller");
        attempt.setStatus(PaymentAttemptStatus.PENDING);
        when(payments.selectByOutTradeNo(attempt.getOutTradeNo())).thenReturn(attempt);
        when(orders.selectForUpdate(order.getId())).thenReturn(order);
        when(payments.selectLatestForUpdate(order.getId())).thenReturn(attempt);
        when(gateway.configured()).thenReturn(true);
        when(gateway.channel()).thenReturn("ALIPAY_SANDBOX");
        when(gateway.appId()).thenReturn(attempt.getAppId());
        when(gateway.sellerId()).thenReturn(attempt.getSellerId());
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(payments.claimQrCode(eq(attempt.getId()), any(), any(), any())).thenAnswer(invocation -> {
            attempt.setQrClaimToken(invocation.getArgument(1));
            attempt.setQrClaimExpiresAt(invocation.getArgument(2));
            return 1;
        });
        when(gateway.qrCode(eq(attempt.getOutTradeNo()), eq(attempt.getAmount()), eq(order.getPayExpiresAt())))
                .thenReturn(new AlipayGateway.QrCode("https://qr.alipay.com/unit", now.plusMinutes(14)));
        when(payments.storeQrCode(eq(attempt.getId()), any(), any(), any())).thenReturn(1);

        assertTrue(service.checkoutPage(attempt.getOutTradeNo()).contains("<svg"));

        InOrder sequence = inOrder(transactions, gateway, payments);
        sequence.verify(payments).claimQrCode(eq(attempt.getId()), any(), any(), any());
        sequence.verify(transactions).commit(any());
        sequence.verify(gateway).qrCode(eq(attempt.getOutTradeNo()), eq(attempt.getAmount()), eq(order.getPayExpiresAt()));
        sequence.verify(payments).storeQrCode(eq(attempt.getId()), any(), any(), eq(attempt.getQrClaimToken()));
        sequence.verify(transactions).commit(any());
    }
}
