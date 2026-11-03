package com.shiwushop.integration.component.payment;

import com.shiwushop.support.TestRegistration;

import com.shiwushop.payment.service.AlipayGateway;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.trade.entity.status.OrderStatus;
import com.shiwushop.trade.entity.status.PaymentAttemptStatus;

import com.shiwushop.product.entity.status.ProductStatus;

import com.shiwushop.product.entity.category.Category;
import com.shiwushop.trade.entity.order.Order;
import com.shiwushop.product.entity.product.Product;
import com.shiwushop.merchant.entity.shop.Shop;
import com.shiwushop.product.entity.product.Sku;
import com.shiwushop.user.entity.user.UserProfile;
import com.shiwushop.user.dto.user.RegisterDTO;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.common.BusinessException;
import com.shiwushop.product.mapper.category.CategoryMapper;
import com.shiwushop.trade.mapper.order.OrderMapper;
import com.shiwushop.trade.mapper.payment.PaymentAttemptMapper;
import com.shiwushop.product.mapper.product.ProductMapper;
import com.shiwushop.merchant.mapper.shop.ShopMapper;
import com.shiwushop.product.mapper.product.SkuMapper;
import com.shiwushop.user.service.RegisterService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.shiwushop.trade.service.OrderPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.shiwushop.common.captcha.CaptchaProofStore;
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
    @Autowired private OrderPaymentService paymentService;
    @Autowired private PaymentAttemptMapper paymentMapper;
    @Autowired private RegisterService userService;
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
    void cancellationWithoutAttemptReturnsStockOnceAndIsIdempotent() throws Exception {
        Fixture f = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        f.sku().setStock(1);
        skuMapper.updateById(f.sku());
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/orders/{id}/cancel", f.order().getId()).session(session(f.buyer())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }
        assertEquals(3, skuMapper.selectById(f.sku().getId()).getStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'", Integer.class, f.order().getId()));
    }

    @Test
    void cancellationNotFoundBeforeDeadlineRemainsPendingAndBlocksPayment() throws Exception {
        Fixture f = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        var attempt = pendingAttempt(f, true);
        when(gateway.query(attempt.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult("NOT_FOUND", attempt.getOutTradeNo(), null, null));
        mockMvc.perform(post("/orders/{id}/cancel", f.order().getId()).session(session(f.buyer())))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("CANCEL_PENDING"));
        assertNotNull(orderMapper.selectById(f.order().getId()).getCancelRequestedAt());
        mockMvc.perform(get("/orders/{id}", f.order().getId()).session(session(f.buyer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UNPAID"))
                .andExpect(jsonPath("$.data.cancelPending").value(true));
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(f.order().getId()).getStatus());
        mockMvc.perform(post("/orders/{id}/payment", f.order().getId()).session(session(f.buyer())))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/payments/checkout/{outTradeNo}", attempt.getOutTradeNo()))
                .andExpect(status().isConflict());
        when(gateway.query(any())).thenAnswer(call -> new AlipayGateway.TradeResult("NOT_FOUND", call.getArgument(0), null, null));
        when(gateway.query(attempt.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult("TRADE_CLOSED", attempt.getOutTradeNo(), null, new BigDecimal("20.00")));
        paymentService.expireDueOrders();
        assertEquals(OrderStatus.CANCELLED, orderMapper.selectById(f.order().getId()).getStatus());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancellationDuringQrGenerationKeepsStockUntilChannelClosed() throws Exception {
        Fixture f = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(f.buyer().getId(), f.order().getId());
        String trade = paymentMapper.selectLatest(f.order().getId()).getOutTradeNo();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(gateway.qrCode(any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new AlipayGateway.QrCode("https://qr.alipay.com/late", LocalDateTime.now(businessClock).plusMinutes(14));
        });
        when(gateway.query(trade)).thenReturn(new AlipayGateway.TradeResult("NOT_FOUND", trade, null, null));
        var qr = CompletableFuture.supplyAsync(() -> {
            try { paymentService.checkoutPage(trade); return 200; }
            catch (BusinessException e) { return e.getCode(); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("CANCEL_PENDING", paymentService.cancelOrder(f.buyer().getId(), f.order().getId()));
            assertEquals(OrderStatus.UNPAID, orderMapper.selectById(f.order().getId()).getStatus());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'", Integer.class, f.order().getId()));
        } finally { release.countDown(); }
        assertEquals(409, qr.get(5, TimeUnit.SECONDS));
        assertNull(paymentMapper.selectByOutTradeNo(trade).getQrCode());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancellationGatewayWaitReleasesOrderLockAndSecondCancellationSharesLease() throws Exception {
        Fixture f = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(f.buyer().getId(), f.order().getId());
        String trade = paymentMapper.selectLatest(f.order().getId()).getOutTradeNo();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(gateway.query(trade)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new AlipayGateway.TradeResult("WAIT_BUYER_PAY", trade, null, new BigDecimal("20.00"));
        });
        when(gateway.close(trade)).thenReturn(true);
        var first = CompletableFuture.supplyAsync(() -> paymentService.cancelOrder(f.buyer().getId(), f.order().getId()));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("CANCEL_PENDING", paymentService.cancelOrder(f.buyer().getId(), f.order().getId()));
            paymentService.expireOrder(f.order().getId());
        } finally { release.countDown(); }
        assertEquals("CANCELLED", first.get(5, TimeUnit.SECONDS));
        verify(gateway, times(1)).query(trade);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'", Integer.class, f.order().getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void paidCallbackWinsWhileCancellationQueryIsBlocked() throws Exception {
        Fixture f = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        paymentService.start(f.buyer().getId(), f.order().getId());
        String trade = paymentMapper.selectLatest(f.order().getId()).getOutTradeNo();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(gateway.query(trade)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new AlipayGateway.TradeResult("WAIT_BUYER_PAY", trade, null, new BigDecimal("20.00"));
        });
        when(gateway.close(trade)).thenReturn(true);
        when(gateway.verify(anyMap())).thenReturn(true);
        var cancellation = CompletableFuture.supplyAsync(() -> {
            try { paymentService.cancelOrder(f.buyer().getId(), f.order().getId()); return 200; }
            catch (BusinessException e) { return e.getCode(); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            paymentService.notifyPaid(Map.of("out_trade_no", trade, "trade_no", "paid-" + f.order().getId(),
                    "trade_status", "TRADE_SUCCESS", "total_amount", "20.00", "app_id", "sandbox-app", "seller_id", "sandbox-seller"));
        } finally { release.countDown(); }
        assertEquals(409, cancellation.get(5, TimeUnit.SECONDS));
        assertEquals(OrderStatus.PAID, orderMapper.selectById(f.order().getId()).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'", Integer.class, f.order().getId()));
    }

    @Test
    void cancellationQueryPaidAndGatewayTimeoutKeepCorrectState() {
        Fixture paid = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        var attempt = pendingAttempt(paid, true);
        when(gateway.query(attempt.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult("TRADE_SUCCESS", attempt.getOutTradeNo(), "paid-" + paid.order().getId(), new BigDecimal("20.00")));
        assertEquals(409, assertThrows(BusinessException.class, () -> paymentService.cancelOrder(paid.buyer().getId(), paid.order().getId())).getCode());
        assertEquals(OrderStatus.PAID, orderMapper.selectById(paid.order().getId()).getStatus());
        Fixture pending = fixture(LocalDateTime.now(businessClock).plusMinutes(15));
        var timedOut = pendingAttempt(pending, true);
        when(gateway.query(timedOut.getOutTradeNo())).thenThrow(new BusinessException(503, "渠道暂不可用"));
        assertEquals("CANCEL_PENDING", paymentService.cancelOrder(pending.buyer().getId(), pending.order().getId()));
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(pending.order().getId()).getStatus());
        assertNotNull(orderMapper.selectById(pending.order().getId()).getCancelRequestedAt());
    }

    @Test
    void notFoundAfterDeadlineWithUnresolvedQrLeaseKeepsStock() {
        Fixture f = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        var attempt = pendingAttempt(f, true);
        attempt.setQrClaimToken("unfinished-qr");
        attempt.setQrClaimExpiresAt(LocalDateTime.now(businessClock).minusMinutes(5));
        paymentMapper.updateById(attempt);
        when(gateway.query(attempt.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult("NOT_FOUND", attempt.getOutTradeNo(), null, null));
        paymentService.expireOrder(f.order().getId());
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(f.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.PENDING, paymentMapper.selectById(attempt.getId()).getStatus());
    }

    @Test
    void cancellationQueueRotatesMoreThanTwoHundredWhileBothScansUseRangeIndexes() {
        Fixture f = fixture(LocalDateTime.now(businessClock).plusDays(1));
        String digits = "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String columns = "user_id,shop_id,total_amount,status,pay_expires_at,consignee_ciphertext,phone_ciphertext,province,city,district,detail_ciphertext,shipping_key_version";
        jdbc.update("INSERT INTO orders (" + columns + ") SELECT o.user_id,o.shop_id,o.total_amount,'UNPAID',o.pay_expires_at,o.consignee_ciphertext,o.phone_ciphertext,o.province,o.city,o.district,o.detail_ciphertext,o.shipping_key_version FROM orders o CROSS JOIN " + digits + " a CROSS JOIN " + digits + " b CROSS JOIN " + digits + " c CROSS JOIN " + digits + " d WHERE o.id=?", f.order().getId());
        jdbc.update("INSERT INTO orders (" + columns + ",cancel_requested_at,cancel_queue_at) SELECT o.user_id,o.shop_id,o.total_amount,'UNPAID',o.pay_expires_at,o.consignee_ciphertext,o.phone_ciphertext,o.province,o.city,o.district,o.detail_ciphertext,o.shipping_key_version,'2001-01-01','2001-01-01' FROM orders o CROSS JOIN " + digits + " a CROSS JOIN " + digits + " b CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2) c WHERE o.id=? AND (c.n * 100 + b.n * 10 + a.n) < 210", f.order().getId());
        jdbc.update("INSERT INTO payment_attempts (order_id,channel,out_trade_no,app_id,seller_id,amount,status) SELECT id,'ALIPAY_SANDBOX',CONCAT('fair-',id),'sandbox-app','sandbox-seller',20,'PENDING' FROM orders WHERE user_id=? AND cancel_queue_at='2001-01-01'", f.buyer().getId());
        when(gateway.configured()).thenReturn(false);
        var expiryPlan = jdbc.queryForList("EXPLAIN SELECT id FROM orders WHERE status='UNPAID' AND pay_expires_at <= NOW() AND (expiry_claim_until IS NULL OR expiry_claim_until <= NOW(3)) ORDER BY pay_expires_at,id LIMIT 25");
        var queuePlan = jdbc.queryForList("EXPLAIN SELECT id FROM orders WHERE status='UNPAID' AND cancel_queue_at IS NOT NULL AND (expiry_claim_until IS NULL OR expiry_claim_until <= NOW(3)) ORDER BY cancel_queue_at,id LIMIT 25");
        System.out.println("W04 expiry EXPLAIN " + expiryPlan);
        System.out.println("W04 cancel EXPLAIN " + queuePlan);
        assertEquals("idx_orders_expiry_scan", expiryPlan.getFirst().get("key"));
        assertEquals("idx_orders_cancel_queue", queuePlan.getFirst().get("key"));
        assertEquals("range", expiryPlan.getFirst().get("type"));
        assertEquals("range", queuePlan.getFirst().get("type"));
        for (int i = 0; i < 9; i++) paymentService.expireDueOrders();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND cancel_queue_at='2001-01-01'", Integer.class, f.buyer().getId()));
        assertEquals(210, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND cancel_requested_at='2001-01-01'", Integer.class, f.buyer().getId()));
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

        CompletableFuture<com.shiwushop.trade.vo.payment.PaymentCheckoutVO> first =
                CompletableFuture.supplyAsync(() -> paymentService.checkoutPage(outTradeNo));
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
        assertEquals("https://qr.alipay.com/test-payment", first.get(5, TimeUnit.SECONDS).qrCode());
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
        assertEquals("https://qr.alipay.com/recovered", paymentService.checkoutPage(outTradeNo).qrCode());
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
            assertEquals("https://qr.alipay.com/replacement", paymentService.checkoutPage(outTradeNo).qrCode());
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
        com.shiwushop.trade.entity.payment.PaymentAttempt attempt = pendingAttempt(fixture, false);
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
        com.shiwushop.trade.entity.payment.PaymentAttempt safe = pendingAttempt(guarded, true);
        when(gateway.query(safe.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", safe.getOutTradeNo(), null, null));

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
        com.shiwushop.trade.entity.payment.PaymentAttempt unsafe = pendingAttempt(legacy, false);
        when(gateway.query(unsafe.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", unsafe.getOutTradeNo(), null, null));
        paymentService.expireOrder(legacy.order().getId());
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(legacy.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.PENDING, paymentMapper.selectById(unsafe.getId()).getStatus());
        assertEquals(1, skuMapper.selectById(legacy.sku().getId()).getStock());
        assertEquals(0, searchEvents(legacy.product()));

        Fixture awaitingQr = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        com.shiwushop.trade.entity.payment.PaymentAttempt qrStillValid = pendingAttempt(awaitingQr, true);
        qrStillValid.setQrExpiresAt(LocalDateTime.now(businessClock).plusMinutes(1));
        paymentMapper.updateById(qrStillValid);
        when(gateway.query(qrStillValid.getOutTradeNo())).thenReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", qrStillValid.getOutTradeNo(), null, null));
        paymentService.expireOrder(awaitingQr.order().getId());
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(awaitingQr.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.PENDING, paymentMapper.selectById(qrStillValid.getId()).getStatus());
    }

    @Test
    void simulatedGatewayTimeoutDisconnectAndUnknownResultKeepPaymentPending() {
        Fixture fixture = fixture(LocalDateTime.now(businessClock).minusSeconds(5));
        fixture.sku().setStock(1);
        skuMapper.updateById(fixture.sku());
        com.shiwushop.trade.entity.payment.PaymentAttempt attempt = pendingAttempt(fixture, true);
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

        attempt.setQrTimeoutGuaranteed(false);
        paymentMapper.updateById(attempt);
        doReturn(new AlipayGateway.TradeResult(
                "NOT_FOUND", outTradeNo, null, null)).when(gateway).query(outTradeNo);
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

    private void assertPendingWithoutReturn(Fixture fixture, com.shiwushop.trade.entity.payment.PaymentAttempt attempt) {
        assertEquals(OrderStatus.UNPAID, orderMapper.selectById(fixture.order().getId()).getStatus());
        assertEquals(PaymentAttemptStatus.PENDING, paymentMapper.selectById(attempt.getId()).getStatus());
        assertEquals(1, skuMapper.selectById(fixture.sku().getId()).getStock());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=?) AND reason='ORDER_CANCEL_RETURN'",
                Integer.class, fixture.order().getId()));
        assertEquals(0, searchEvents(fixture.product()));
    }

    private com.shiwushop.trade.entity.payment.PaymentAttempt pendingAttempt(Fixture fixture, boolean guardedQr) {
        com.shiwushop.trade.entity.payment.PaymentAttempt attempt = new com.shiwushop.trade.entity.payment.PaymentAttempt();
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
        long fixtureId = buyer.getId();
        Shop shop = new Shop();
        shop.setOwnerUserId(buyer.getId());
        shop.setName("青禾日用品店" + fixtureId);
        shop.setEnabled(1);
        shopMapper.insert(shop);
        Category category = new Category();
        category.setName("居家用品" + fixtureId);
        category.setLevel(1);
        category.setVisible(1);
        category.setSortOrder(0);
        categoryMapper.insert(category);
        Product product = new Product();
        product.setCategoryId(category.getId());
        product.setShopId(shop.getId());
        product.setName("棉质购物袋");
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
