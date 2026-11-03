package com.shiwushop.integration.commerce;

import com.shiwushop.aftersale.dto.AfterSaleReviewDTO;
import com.shiwushop.aftersale.dto.ReturnShipmentDTO;
import com.shiwushop.aftersale.service.AfterSaleService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import com.shiwushop.trade.service.AfterSaleOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AfterSaleWithdrawalConcurrencyTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AfterSaleService service;
    @MockitoBean SdkAlipayGateway gateway;
    @MockitoSpyBean AfterSaleOrderService orders;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reviewBeforeWithdrawalOrderLockAllowsOnlyReview() throws Exception {
        PaymentHttpFixture.configure(gateway);
        try (var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port)) {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            var body = Map.of("type", "REFUND_ONLY", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "withdraw-race"));
            assertEquals(200, created.statusCode(), created.body());
            long sale = json.readTree(created.body()).path("data").path("id").asLong();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(call -> {
                if (Thread.currentThread().getName().equals("withdraw-writer")) {
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                return call.callRealMethod();
            }).when(orders).lock(eq(order));
            try (var pool = Executors.newSingleThreadExecutor()) {
                var pending = pool.submit(() -> {
                    Thread.currentThread().setName("withdraw-writer");
                    return service.withdraw(fixture.buyer.id(), sale);
                });
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    service.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale, new AfterSaleReviewDTO(true, null));
                } finally {
                    release.countDown();
                }
                var failure = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
                assertInstanceOf(BusinessException.class, failure.getCause());
                assertEquals(409, ((BusinessException) failure.getCause()).getCode());
                assertEquals("REFUNDING", jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?", String.class, sale));
            } finally {
                release.countDown();
            }
        }
    }
    @Test
    void shipmentBeforeWithdrawalOrderLockAllowsOnlyShipment() throws Exception {
        PaymentHttpFixture.configure(gateway);
        try (var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port)) {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            String merchant = "/merchant/shops/" + fixture.catalog.shop;
            assertEquals(200, fixture.merchantClient.post(merchant + "/orders/" + order + "/shipment",
                    Map.of("carrierName", "顺丰", "trackingNo", "SF10006")).statusCode());
            var body = Map.of("type", "RETURN_REFUND", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "return-withdraw-race"));
            assertEquals(200, created.statusCode(), created.body());
            long sale = json.readTree(created.body()).path("data").path("id").asLong();
            jdbc.update("UPDATE after_sale_cases SET status='WAITING_RETURN' WHERE id=?", sale);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(call -> {
                if (Thread.currentThread().getName().equals("withdraw-writer")) {
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                return call.callRealMethod();
            }).when(orders).lock(eq(order));
            try (var pool = Executors.newSingleThreadExecutor()) {
                var pending = pool.submit(() -> {
                    Thread.currentThread().setName("withdraw-writer");
                    return service.withdraw(fixture.buyer.id(), sale);
                });
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                    service.sendReturn(fixture.buyer.id(), sale, new ReturnShipmentDTO("圆通", "YT10006"));
                } finally {
                    release.countDown();
                }
                var failure = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
                assertInstanceOf(BusinessException.class, failure.getCause());
                assertEquals(409, ((BusinessException) failure.getCause()).getCode());
                assertEquals("WAITING_RECEIPT", jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?", String.class, sale));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM return_shipments WHERE case_id=?", Integer.class, sale));
            } finally {
                release.countDown();
            }
        }
    }
    @Test
    void withdrawalHoldingOrderLockMakesConcurrentReviewFail() throws Exception {
        PaymentHttpFixture.configure(gateway);
        try (var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port)) {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            var body = Map.of("type", "REFUND_ONLY", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "withdraw-first-race"));
            assertEquals(200, created.statusCode(), created.body());
            long sale = json.readTree(created.body()).path("data").path("id").asLong();
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var reviewStarted = new CountDownLatch(1);
            doAnswer(call -> {
                var result = call.callRealMethod();
                if (Thread.currentThread().getName().equals("withdraw-writer")) {
                    locked.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                return result;
            }).when(orders).lock(eq(order));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var withdrawal = pool.submit(() -> {
                    Thread.currentThread().setName("withdraw-writer");
                    return service.withdraw(fixture.buyer.id(), sale);
                });
                try {
                    assertTrue(locked.await(10, TimeUnit.SECONDS));
                    var review = pool.submit(() -> {
                        reviewStarted.countDown();
                        return service.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale, new AfterSaleReviewDTO(true, null));
                    });
                    assertTrue(reviewStarted.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> review.get(300, TimeUnit.MILLISECONDS));
                    release.countDown();
                    assertEquals("CLOSED", withdrawal.get(10, TimeUnit.SECONDS).status());
                    var failure = assertThrows(ExecutionException.class, () -> review.get(10, TimeUnit.SECONDS));
                    assertInstanceOf(BusinessException.class, failure.getCause());
                    assertEquals(409, ((BusinessException) failure.getCause()).getCode());
                    assertEquals("CLOSED", jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?", String.class, sale));
                } finally {
                    release.countDown();
                }
            }
        }
    }
}
