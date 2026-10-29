package com.shiwushop.payment;

import com.shiwushop.test.TestRegistration;

import com.shiwushop.commerce.payment.AlipayGateway;
import com.shiwushop.commerce.payment.SdkAlipayGateway;
import com.shiwushop.entity.status.OrderStatus;
import com.shiwushop.entity.status.PaymentAttemptStatus;

import com.shiwushop.entity.status.ProductStatus;

import com.shiwushop.catalog.entity.category.Category;
import com.shiwushop.commerce.entity.order.Order;
import com.shiwushop.catalog.entity.product.Product;
import com.shiwushop.catalog.entity.shop.Shop;
import com.shiwushop.catalog.entity.product.Sku;
import com.shiwushop.entity.user.UserProfile;
import com.shiwushop.dto.user.RegisterDTO;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.common.BusinessException;
import com.shiwushop.catalog.mapper.category.CategoryMapper;
import com.shiwushop.commerce.mapper.order.OrderMapper;
import com.shiwushop.commerce.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.catalog.mapper.product.ProductMapper;
import com.shiwushop.catalog.mapper.shop.ShopMapper;
import com.shiwushop.catalog.mapper.product.SkuMapper;
import com.shiwushop.service.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.shiwushop.commerce.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.auth.CaptchaProofStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.startsWith;

@SpringBootTest(properties = "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@Transactional
class PaymentFlowTests {
    @Autowired private CaptchaProofStore registrationProofs;
    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentAttemptMapper paymentMapper;
    @Autowired private UserService userService;
    @Autowired private PrivacyCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private SkuMapper skuMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private Clock businessClock;
    @MockitoBean private SdkAlipayGateway gateway;

    @BeforeEach
    void gatewayReady() {
        when(gateway.configured()).thenReturn(true);
        when(gateway.appId()).thenReturn("sandbox-app");
        when(gateway.sellerId()).thenReturn("sandbox-seller");
        when(gateway.channel()).thenReturn("ALIPAY_SANDBOX");
        when(gateway.qrCode(any(), any(), any())).thenReturn(new AlipayGateway.QrCode(
                "https://qr.alipay.com/test-payment", LocalDateTime.now(businessClock).plusMinutes(14)));
    }

    @Test
    void productionGatewayUsesProductionChannelAndPage() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        when(gateway.channel()).thenReturn("ALIPAY");
        mockMvc.perform(post("/orders/{id}/payment", fixture.order().getId())
                        .session(session(fixture.buyer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("ALIPAY"));
        var attempt = paymentMapper.selectLatest(fixture.order().getId());
        assertEquals("ALIPAY", attempt.getChannel());
        mockMvc.perform(get("/payments/checkout/{outTradeNo}", attempt.getOutTradeNo()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("请使用支付宝 App 扫码付款")));

        // 切换支付环境后，旧的待支付记录不能被新环境继续使用。
        when(gateway.channel()).thenReturn("ALIPAY_SANDBOX");
        mockMvc.perform(post("/orders/{id}/payment", fixture.order().getId())
                        .session(session(fixture.buyer())))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/payments/checkout/{outTradeNo}", attempt.getOutTradeNo()))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/orders/{id}/payment/sync", fixture.order().getId())
                        .session(session(fixture.buyer())))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyProviderResultPaysOrderAndDuplicatesDoNotIncreaseSales() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        UserProfile other = user();
        assertEquals(0L, productMapper.getSoldCount(fixture.product().getId()));
        mockMvc.perform(post("/orders/{id}/payment", fixture.order().getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/orders/{id}/payment", fixture.order().getId()).session(session(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/orders/{id}/payment", fixture.order().getId()).session(session(fixture.buyer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.payUrl", startsWith("http://localhost/payments/checkout/")));
        Long paymentId = paymentMapper.selectLatest(fixture.order().getId()).getId();
        String outTradeNo = paymentMapper.selectById(paymentId).getOutTradeNo();
        mockMvc.perform(get("/payments/checkout/{outTradeNo}", outTradeNo))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("支付宝沙箱扫码支付")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<svg")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<form"))));
        assertNotNull(paymentMapper.selectById(paymentId).getQrExpiresAt());
        mockMvc.perform(get("/payments/checkout/{outTradeNo}", outTradeNo))
                .andExpect(status().isOk());
        verify(gateway, times(1)).qrCode(any(), any(), any());
        mockMvc.perform(get("/payments/checkout/missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/orders/{id}/payment", fixture.order().getId()).session(session(fixture.buyer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentId").value(paymentId));
        mockMvc.perform(get("/orders/{id}/payment", fixture.order().getId()).session(session(fixture.buyer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("UNPAID"));
        assertEquals(0L, productMapper.getSoldCount(fixture.product().getId()));

        when(gateway.query(outTradeNo)).thenReturn(new AlipayGateway.TradeResult(
                "TRADE_SUCCESS", outTradeNo, "alipay-trade-1", new BigDecimal("20.00")));
        mockMvc.perform(post("/orders/{id}/payment/sync", fixture.order().getId())
                        .session(session(fixture.buyer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("PAID"));
        assertNotNull(orderMapper.selectById(fixture.order().getId()).getPaidAt());
        assertEquals(2L, productMapper.getSoldCount(fixture.product().getId()));
        assertEquals(1, searchEvents(fixture.product()));
        mockMvc.perform(get("/payments/checkout/{outTradeNo}", outTradeNo))
                .andExpect(status().isNotFound());

        when(gateway.verify(anyMap())).thenReturn(true);
        for (int i = 0; i < 20; i++) {
            notifyForm(outTradeNo, "20.00", "alipay-trade-1")
                    .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("success"));
        }
        assertEquals(2L, productMapper.getSoldCount(fixture.product().getId()));
        assertEquals(1, searchEvents(fixture.product()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE order_id=? AND status='SUCCESS'",
                Integer.class, fixture.order().getId()));
        mockMvc.perform(post("/orders/{id}/payment", fixture.order().getId()).session(session(fixture.buyer())))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/orders/{id}/pay", fixture.order().getId()).session(session(fixture.buyer())))
                .andExpect(status().isNotFound());
    }

    @Test
    void forgedOrMismatchedNotificationNeverPaysOrder() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(fixture.buyer().getId(), fixture.order().getId());
        String outTradeNo = paymentMapper.selectLatest(fixture.order().getId()).getOutTradeNo();
        notifyForm(outTradeNo, "20.00", "trade-2").andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("failure"));
        when(gateway.verify(anyMap())).thenReturn(true);
        notifyForm(outTradeNo, "19.00", "trade-2").andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("failure"));
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(fixture.order().getId()).getStatus());
        assertEquals(0L, productMapper.getSoldCount(fixture.product().getId()));
    }

    @Test
    void missingConfigurationAndHistoricalOrdersCannotStartPayment() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        when(gateway.configured()).thenReturn(false);
        mockMvc.perform(post("/orders/{id}/payment", fixture.order().getId())
                        .session(session(fixture.buyer())))
                .andExpect(status().isServiceUnavailable());
        assertNull(paymentMapper.selectLatest(fixture.order().getId()));

        when(gateway.configured()).thenReturn(true);
        Fixture historical = fixture(null);
        mockMvc.perform(post("/orders/{id}/payment", historical.order().getId())
                        .session(session(historical.buyer())))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/orders/{id}/payment", historical.order().getId())
                        .session(session(historical.buyer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.payment").value((Object) null));
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(historical.order().getId()).getStatus());
    }

    @Test
    void checkoutPageRejectsExpiredPayment() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(fixture.buyer().getId(), fixture.order().getId());
        String outTradeNo = paymentMapper.selectLatest(fixture.order().getId()).getOutTradeNo();
        fixture.order().setPayExpiresAt(LocalDateTime.now(businessClock).minusSeconds(1));
        orderMapper.updateById(fixture.order());

        mockMvc.perform(get("/payments/checkout/{outTradeNo}", outTradeNo))
                .andExpect(status().isConflict());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void invalidGatewayQrIsNeverStored() {
        Fixture blank = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(blank.buyer().getId(), blank.order().getId());
        String blankTradeNo = paymentMapper.selectLatest(blank.order().getId()).getOutTradeNo();
        when(gateway.qrCode(any(), any(), any())).thenReturn(new AlipayGateway.QrCode(
                " ", LocalDateTime.now(businessClock).plusMinutes(14)));
        assertEquals(503, assertThrows(BusinessException.class,
                () -> paymentService.checkoutPage(blankTradeNo)).getCode());
        assertNull(paymentMapper.selectByOutTradeNo(blankTradeNo).getQrCode());

        Fixture late = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(late.buyer().getId(), late.order().getId());
        String lateTradeNo = paymentMapper.selectLatest(late.order().getId()).getOutTradeNo();
        when(gateway.qrCode(any(), any(), any())).thenReturn(new AlipayGateway.QrCode(
                "https://qr.alipay.com/late", LocalDateTime.now(businessClock).plusMinutes(20)));
        assertEquals(503, assertThrows(BusinessException.class,
                () -> paymentService.checkoutPage(lateTradeNo)).getCode());
        assertNull(paymentMapper.selectByOutTradeNo(lateTradeNo).getQrCode());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void qrGatewayDoesNotHoldOrderLockAndConcurrentRequestDoesNotDuplicatePrecreate() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(fixture.buyer().getId(), fixture.order().getId());
        String outTradeNo = paymentMapper.selectLatest(fixture.order().getId()).getOutTradeNo();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.qrCode(any(), any(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new AlipayGateway.QrCode("https://qr.alipay.com/test-payment",
                    LocalDateTime.now(businessClock).plusMinutes(14));
        });

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> paymentService.checkoutPage(outTradeNo));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            mockMvc.perform(get("/payments/checkout/{outTradeNo}", outTradeNo))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("二维码生成中，请稍后重试"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 2000, "第二个请求被渠道调用期间的数据库行锁阻塞");
            System.out.println("P01 concurrent checkout conflict latency_ms=" + elapsedMs);
        } finally {
            release.countDown();
        }
        assertTrue(first.get(5, TimeUnit.SECONDS).contains("<svg"));
        verify(gateway, times(1)).qrCode(any(), any(), any());
        assertNotNull(paymentMapper.selectByOutTradeNo(outTradeNo).getQrCode());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void paidCallbackWinsAgainstLateQrResult() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(fixture.buyer().getId(), fixture.order().getId());
        String outTradeNo = paymentMapper.selectLatest(fixture.order().getId()).getOutTradeNo();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(gateway.qrCode(any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new AlipayGateway.QrCode("https://qr.alipay.com/late",
                    LocalDateTime.now(businessClock).plusMinutes(14));
        });
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try {
                paymentService.checkoutPage(outTradeNo);
                return 200;
            } catch (BusinessException e) {
                return e.getCode();
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            when(gateway.verify(anyMap())).thenReturn(true);
            paymentService.notifyPaid(Map.of("out_trade_no", outTradeNo, "trade_no", "paid-" + UUID.randomUUID(),
                    "trade_status", "TRADE_SUCCESS", "total_amount", "20.00",
                    "app_id", "sandbox-app", "seller_id", "sandbox-seller"));
        } finally {
            release.countDown();
        }
        assertEquals(409, first.get(5, TimeUnit.SECONDS));
        assertEquals(OrderStatus.PAID, orderMapper.selectById(fixture.order().getId()).getStatus());
        assertNull(paymentMapper.selectByOutTradeNo(outTradeNo).getQrCode());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredQrClaimQueriesOriginalTradeBeforeRetry() {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(fixture.buyer().getId(), fixture.order().getId());
        String outTradeNo = paymentMapper.selectLatest(fixture.order().getId()).getOutTradeNo();
        when(gateway.qrCode(any(), any(), any())).thenThrow(new BusinessException(503, "模拟渠道断开"));
        assertEquals(503, assertThrows(BusinessException.class,
                () -> paymentService.checkoutPage(outTradeNo)).getCode());
        assertNotNull(paymentMapper.selectByOutTradeNo(outTradeNo).getQrClaimToken());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> paymentService.checkoutPage(outTradeNo)).getCode());

        jdbc.update("UPDATE payment_attempts SET qr_claim_expires_at = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE out_trade_no = ?", outTradeNo);
        when(gateway.query(outTradeNo)).thenReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", outTradeNo, null, null));
        doReturn(new AlipayGateway.QrCode(
                "https://qr.alipay.com/recovered", LocalDateTime.now(businessClock).plusMinutes(14)))
                .when(gateway).qrCode(any(), any(), any());
        assertTrue(paymentService.checkoutPage(outTradeNo).contains("<svg"));
        verify(gateway).query(outTradeNo);
        verify(gateway, times(2)).qrCode(any(), any(), any());
        assertNull(paymentMapper.selectByOutTradeNo(outTradeNo).getQrClaimToken());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredLeaseOwnerCannotOverwriteReplacementQr() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(fixture.buyer().getId(), fixture.order().getId());
        String outTradeNo = paymentMapper.selectLatest(fixture.order().getId()).getOutTradeNo();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(gateway.qrCode(any(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new AlipayGateway.QrCode("https://qr.alipay.com/stale",
                        LocalDateTime.now(businessClock).plusMinutes(14));
            }
            return new AlipayGateway.QrCode("https://qr.alipay.com/replacement",
                    LocalDateTime.now(businessClock).plusMinutes(14));
        });
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try {
                paymentService.checkoutPage(outTradeNo);
                return 200;
            } catch (BusinessException e) {
                return e.getCode();
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            jdbc.update("UPDATE payment_attempts SET qr_claim_expires_at = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                    + "WHERE out_trade_no = ?", outTradeNo);
            when(gateway.query(outTradeNo)).thenReturn(new AlipayGateway.TradeResult(
                    "NOT_FOUND", outTradeNo, null, null));
            assertTrue(paymentService.checkoutPage(outTradeNo).contains("<svg"));
        } finally {
            release.countDown();
        }
        assertEquals(409, first.get(5, TimeUnit.SECONDS));
        assertEquals("https://qr.alipay.com/replacement",
                paymentMapper.selectByOutTradeNo(outTradeNo).getQrCode());
        assertEquals(2, calls.get());
    }

    @Test
    void confirmedCloseCancelsOnceAndRestoresStockOnce() {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        Sku sku = fixture.sku();
        sku.setStock(1);
        skuMapper.updateById(sku);
        com.shiwushop.commerce.entity.payment.PaymentAttempt attempt = pendingAttempt(fixture, false);
        when(gateway.query(attempt.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                "WAIT_BUYER_PAY", attempt.getOutTradeNo(), null, new BigDecimal("20.00")));
        when(gateway.close(attempt.getOutTradeNo())).thenReturn(true);

        paymentService.expireOrder(fixture.order().getId());
        assertEquals(OrderStatus.CANCELLED, orderMapper.selectById(fixture.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.CLOSED, paymentMapper.selectById(attempt.getId()).getStatus());
        assertEquals(3, skuMapper.selectById(sku.getId()).getStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",
                Integer.class, fixture.order().getId()));
        paymentService.expireOrder(fixture.order().getId());
        assertEquals(3, skuMapper.selectById(sku.getId()).getStock());
        assertEquals(1, searchEvents(fixture.product()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",
                Integer.class, fixture.order().getId()));
    }

    @Test
    void expiryLeaseAllowsOneOwnerAndRejectsStaleChannelResult() {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        var attempt = pendingAttempt(fixture, true);
        Long orderId = fixture.order().getId();
        LocalDateTime now = LocalDateTime.now(businessClock);
        assertEquals(1, orderMapper.claimExpiry(orderId, "first", now, 120));
        assertEquals(0, orderMapper.claimExpiry(orderId, "second", now, 120));
        jdbc.update("UPDATE orders SET expiry_claim_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=?", orderId);
        assertEquals(1, orderMapper.claimExpiry(orderId, "second", now, 120));
        assertEquals(0, orderMapper.ownsExpiry(orderId, "first"));
        assertEquals(1, orderMapper.ownsExpiry(orderId, "second"));
        orderMapper.releaseExpiry(orderId, "second");

        doAnswer(invocation -> {
            // Simulate a new owner taking the lease while the first owner waits on the channel.
            jdbc.update("UPDATE orders SET expiry_claim_token=?, expiry_claim_until=? WHERE id=?",
                    "new-owner", LocalDateTime.now(businessClock).plusMinutes(2), orderId);
            return new AlipayGateway.TradeResult("TRADE_SUCCESS", attempt.getOutTradeNo(),
                    "late-trade-" + orderId, new BigDecimal("20.00"));
        }).when(gateway).query(attempt.getOutTradeNo());

        paymentService.expireOrder(orderId);

        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(orderId).getStatus());
        assertEquals(PaymentAttemptStatus.PENDING, paymentMapper.selectById(attempt.getId()).getStatus());
        assertEquals(0, orderMapper.releaseExpiry(orderId, "first"));
    }

    @Test
    void expiredGuardedQrWithNoTradeCancelsOnceButLegacyAttemptStaysPending() {
        Fixture guarded = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        guarded.sku().setStock(1);
        skuMapper.updateById(guarded.sku());
        com.shiwushop.commerce.entity.payment.PaymentAttempt safe = pendingAttempt(guarded, true);
        when(gateway.query(safe.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", safe.getOutTradeNo(), null, null));
        when(gateway.cancel(safe.getOutTradeNo())).thenReturn(AlipayGateway.CancelResult.NO_TRADE);

        paymentService.expireOrder(guarded.order().getId());
        paymentService.expireOrder(guarded.order().getId());
        assertEquals(OrderStatus.CANCELLED, orderMapper.selectById(guarded.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.CLOSED, paymentMapper.selectById(safe.getId()).getStatus());
        assertEquals(3, skuMapper.selectById(guarded.sku().getId()).getStock());
        assertEquals(1, searchEvents(guarded.product()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",
                Integer.class, guarded.order().getId()));

        Fixture legacy = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        legacy.sku().setStock(1);
        skuMapper.updateById(legacy.sku());
        com.shiwushop.commerce.entity.payment.PaymentAttempt unsafe = pendingAttempt(legacy, false);
        when(gateway.query(unsafe.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", unsafe.getOutTradeNo(), null, null));
        when(gateway.cancel(unsafe.getOutTradeNo())).thenReturn(AlipayGateway.CancelResult.NO_TRADE);
        paymentService.expireOrder(legacy.order().getId());
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(legacy.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.PENDING, paymentMapper.selectById(unsafe.getId()).getStatus());
        assertEquals(1, skuMapper.selectById(legacy.sku().getId()).getStock());
        assertEquals(0, searchEvents(legacy.product()));

        Fixture awaitingQr = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        com.shiwushop.commerce.entity.payment.PaymentAttempt qrStillValid = pendingAttempt(awaitingQr, true);
        qrStillValid.setQrExpiresAt(LocalDateTime.now(businessClock).plusMinutes(1));
        paymentMapper.updateById(qrStillValid);
        when(gateway.query(qrStillValid.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", qrStillValid.getOutTradeNo(), null, null));
        when(gateway.cancel(qrStillValid.getOutTradeNo())).thenReturn(AlipayGateway.CancelResult.NO_TRADE);
        paymentService.expireOrder(awaitingQr.order().getId());
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(awaitingQr.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.PENDING, paymentMapper.selectById(qrStillValid.getId()).getStatus());
    }

    @Test
    void simulatedGatewayTimeoutDisconnectAndUnknownResultKeepPaymentPending() {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        fixture.sku().setStock(1);
        skuMapper.updateById(fixture.sku());
        com.shiwushop.commerce.entity.payment.PaymentAttempt attempt = pendingAttempt(fixture, true);
        String outTradeNo = attempt.getOutTradeNo();

        when(gateway.query(outTradeNo)).thenThrow(new BusinessException(503, "模拟查询超时"));
        assertThrows(BusinessException.class, () -> paymentService.expireOrder(fixture.order().getId()));
        assertPendingWithoutReturn(fixture, attempt);

        doThrow(new BusinessException(503, "模拟连接断开")).when(gateway).query(outTradeNo);
        assertThrows(BusinessException.class, () -> paymentService.expireOrder(fixture.order().getId()));
        assertPendingWithoutReturn(fixture, attempt);

        doReturn(new AlipayGateway.TradeResult(
                "WAIT_BUYER_PAY", outTradeNo, null, new BigDecimal("20.00"))).when(gateway).query(outTradeNo);
        when(gateway.close(outTradeNo)).thenReturn(false);
        paymentService.expireOrder(fixture.order().getId());
        assertPendingWithoutReturn(fixture, attempt);

        doReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", outTradeNo, null, null)).when(gateway).query(outTradeNo);
        when(gateway.cancel(outTradeNo)).thenReturn(AlipayGateway.CancelResult.UNRESOLVED);
        paymentService.expireOrder(fixture.order().getId());
        assertPendingWithoutReturn(fixture, attempt);

        doReturn(new AlipayGateway.TradeResult("TRADE_SUCCESS", outTradeNo,
                "recovered-trade-" + fixture.order().getId(), new BigDecimal("20.00")))
                .when(gateway).query(outTradeNo);
        paymentService.expireOrder(fixture.order().getId());
        assertEquals(OrderStatus.PAID, orderMapper.selectById(fixture.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.SUCCESS, paymentMapper.selectById(attempt.getId()).getStatus());
        assertEquals(2L, productMapper.getSoldCount(fixture.product().getId()));
        assertEquals(1, searchEvents(fixture.product()));
        assertEquals(1, skuMapper.selectById(fixture.sku().getId()).getStock());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",
                Integer.class, fixture.order().getId()));
    }

    private void assertPendingWithoutReturn(Fixture fixture, com.shiwushop.commerce.entity.payment.PaymentAttempt attempt) {
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(fixture.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.PENDING, paymentMapper.selectById(attempt.getId()).getStatus());
        assertEquals(1, skuMapper.selectById(fixture.sku().getId()).getStock());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",
                Integer.class, fixture.order().getId()));
        assertEquals(0, searchEvents(fixture.product()));
    }

    private com.shiwushop.commerce.entity.payment.PaymentAttempt pendingAttempt(Fixture fixture, boolean guardedQr) {
        com.shiwushop.commerce.entity.payment.PaymentAttempt attempt = new com.shiwushop.commerce.entity.payment.PaymentAttempt();
        attempt.setOrderId(fixture.order().getId());
        attempt.setChannel("ALIPAY_SANDBOX");
        attempt.setOutTradeNo("test-" + UUID.randomUUID());
        attempt.setAppId("sandbox-app");
        attempt.setSellerId("sandbox-seller");
        attempt.setAmount(new BigDecimal("20.00"));
        attempt.setStatus(PaymentAttemptStatus.PENDING);
        attempt.setQrTimeoutGuaranteed(guardedQr);
        paymentMapper.insert(attempt);
        return attempt;
    }

    @Test
    void expiredOrderWithoutPaymentRestoresStockOnlyOnce() {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        fixture.sku().setStock(1);
        skuMapper.updateById(fixture.sku());

        paymentService.expireOrder(fixture.order().getId());
        paymentService.expireOrder(fixture.order().getId());

        assertEquals(OrderStatus.CANCELLED, orderMapper.selectById(fixture.order().getId()).getStatus());
        assertNull(paymentMapper.selectLatest(fixture.order().getId()));
        assertEquals(3, skuMapper.selectById(fixture.sku().getId()).getStock());
        assertEquals(1, searchEvents(fixture.product()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",
                Integer.class, fixture.order().getId()));
    }

    private int searchEvents(Product product) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' AND aggregate_id = ?",
                Integer.class, product.getId());
    }

    private org.springframework.test.web.servlet.ResultActions notifyForm(String outTradeNo,
                                                                            String amount, String tradeNo) throws Exception {
        return mockMvc.perform(post("/payments/alipay/notify")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", outTradeNo).param("trade_no", tradeNo)
                .param("trade_status", "TRADE_SUCCESS")
                .param("total_amount", amount).param("app_id", "sandbox-app")
                .param("seller_id", "sandbox-seller").param("sign_type", "RSA2").param("sign", "test"));
    }

    private Fixture fixture(LocalDateTime expiresAt) {
        UserProfile buyer = user();
        Shop shop = new Shop();
        shop.setOwnerUserId(buyer.getId());
        shop.setName("支付店铺-" + UUID.randomUUID());
        shop.setEnabled(1);
        shopMapper.insert(shop);
        Category category = new Category();
        category.setName("支付分类-" + UUID.randomUUID());
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        Product product = new Product();
        product.setCategoryId(category.getId());
        product.setShopId(shop.getId());
        product.setName("支付商品");
        product.setStatus(ProductStatus.ON_SALE);
        productMapper.insert(product);
        jdbc.update("INSERT INTO product_sales_stats (product_id, sold_count) VALUES (?, 0)", product.getId());
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        String signature = UUID.randomUUID().toString().replace("-", "");
        sku.setOptionSignature(signature + signature);
        sku.setPrice(new BigDecimal("10.00"));
        sku.setStock(3);
        skuMapper.insert(sku);
        Order order = new Order();
        order.setUserId(buyer.getId());
        order.setShopId(shop.getId());
        order.setTotalAmount(new BigDecimal("20.00"));
        order.setConsigneeCiphertext(cipher.encrypt(buyer.getId(), "orders:consignee", "张三"));
        order.setPhoneCiphertext(cipher.encrypt(buyer.getId(), "orders:phone", "13800138000"));
        order.setProvince("上海市");
        order.setCity("上海市");
        order.setDistrict("浦东新区");
        order.setDetailCiphertext(cipher.encrypt(buyer.getId(), "orders:detail", "测试地址"));
        order.setShippingKeyVersion(1);
        order.setStatus(OrderStatus.UNPAID);
        order.setPayExpiresAt(expiresAt == null ? LocalDateTime.now(businessClock).minusSeconds(1) : expiresAt);
        orderMapper.insert(order);
        orderMapper.insertCheckoutItem(order.getId(), sku.getId(), product.getName(), "标准版",
                2, sku.getPrice());
        assertNotNull(order.getId());
        return new Fixture(buyer, order, product, sku);
    }

    private UserProfile user() {
        String username = "payment-" + UUID.randomUUID().toString().replace("-", "");
        TestRegistration.register(userService, registrationProofs, new RegisterDTO(username, "Shop123!", null));
        UserProfile user = new UserProfile();
        user.setId(jdbc.queryForObject("SELECT user_id FROM user_accounts WHERE username = ?", Long.class, username));
        return user;
    }

    private MockHttpSession session(UserProfile user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user.getId());
        return session;
    }

    private record Fixture(UserProfile buyer, Order order, Product product, Sku sku) {}
}
