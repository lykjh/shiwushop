package com.shiwushop.payment;

import com.shiwushop.commerce.payment.AlipayGateway;
import com.shiwushop.commerce.payment.SdkAlipayGateway;
import com.shiwushop.commerce.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "shop.payment.expiry-scan-delay-ms=600000",
        "spring.datasource.hikari.maximum-pool-size=2"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentExpiryClaimConcurrencyTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService payments;
    @Autowired private Clock clock;
    @MockitoBean private SdkAlipayGateway gateway;

    @Test
    void twoScannersQueryTheSameExpiredOrderOnlyOnce() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String username = "expiry-owner-" + suffix;
        String shopName = "到期扫描店铺-" + suffix;
        String outTradeNo = "EXPIRY" + suffix.replace("-", "");
        jdbc.update("INSERT INTO users(nickname) VALUES (?)", username);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE nickname=?", Long.class, username);
        jdbc.update("INSERT INTO shops(owner_user_id,name,enabled) VALUES (?,?,1)", userId, shopName);
        Long shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, shopName);
        jdbc.update("""
                INSERT INTO orders(user_id,shop_id,total_amount,status,pay_expires_at,
                                   consignee_ciphertext,phone_ciphertext,province,city,district,
                                   detail_ciphertext,shipping_key_version)
                VALUES (?,?,20,'UNPAID',?,?,?,?,?,?,?,1)
                """, userId, shopId, LocalDateTime.now(clock).minusMinutes(1),
                new byte[]{1}, new byte[]{1}, "江苏", "南京", "玄武", new byte[]{1});
        Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE user_id=? AND shop_id=?",
                Long.class, userId, shopId);
        jdbc.update("""
                INSERT INTO payment_attempts(order_id,channel,out_trade_no,app_id,seller_id,amount,status)
                VALUES (?,'ALIPAY_SANDBOX',?,'sandbox-app','sandbox-seller',20,'PENDING')
                """, orderId, outTradeNo);
        when(gateway.configured()).thenReturn(true);
        when(gateway.channel()).thenReturn("ALIPAY_SANDBOX");
        when(gateway.appId()).thenReturn("sandbox-app");
        when(gateway.sellerId()).thenReturn("sandbox-seller");
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        when(gateway.query(outTradeNo)).thenAnswer(call -> {
            queryStarted.countDown();
            assertTrue(releaseQuery.await(5, TimeUnit.SECONDS));
            return new AlipayGateway.TradeResult("WAIT_BUYER_PAY", outTradeNo, null,
                    new BigDecimal("20.00"));
        });
        when(gateway.close(outTradeNo)).thenReturn(false);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> payments.expireOrder(orderId));
            assertTrue(queryStarted.await(5, TimeUnit.SECONDS));
            var second = workers.submit(() -> payments.expireOrder(orderId));
            second.get(5, TimeUnit.SECONDS);
            releaseQuery.countDown();
            first.get(5, TimeUnit.SECONDS);
            verify(gateway, times(1)).query(outTradeNo);
            assertEquals("UNPAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?",
                    String.class, orderId));
        } finally {
            releaseQuery.countDown();
            jdbc.update("DELETE FROM payment_attempts WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
            jdbc.update("DELETE FROM shops WHERE id=?", shopId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
    }
}
