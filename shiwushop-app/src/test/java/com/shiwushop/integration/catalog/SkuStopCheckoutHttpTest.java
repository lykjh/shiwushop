package com.shiwushop.integration.catalog;

import com.shiwushop.trade.payment.LocalSimulationGateway;
import com.shiwushop.product.service.ProductService;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkuStopCheckoutHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductService products;
    @Autowired TransactionTemplate transactions;
    @MockitoBean(name = "localSimulationGateway") LocalSimulationGateway gateway;
    CommerceFixture fixture;

    @BeforeEach
    void prepare() throws Exception {
        PaymentHttpFixture.configure(gateway);
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
    }

    @AfterEach
    void cleanup() {
        if (fixture != null)
            fixture.close();
    }

    @Test
    void stoppedSkuRejectsNewCheckoutButOldUnpaidOrderCanStillPay() throws Exception {
        long order = fixture.order("before-stop");
        int stock = jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku);
        var stopped = fixture.merchantClient.request("PUT", "/merchant/products/" + fixture.product
                + "/skus/" + fixture.sku + "/status", Map.of("status", "STOPPED"));
        assertEquals(200, stopped.statusCode(), stopped.body());
        assertEquals(stock, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku).intValue());
        var rejected = fixture.client.post("/checkouts/direct", fixture.direct("after-stop", 1, "19.90"));
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class,
                fixture.buyer.id()).intValue());
        var payment = fixture.client.post("/orders/" + order + "/payment", Map.of());
        assertEquals(200, payment.statusCode(), payment.body());
        var notice = fixture.client.form("/payments/alipay/notify", PaymentHttpFixture.notice(jdbc, order));
        assertEquals(200, notice.statusCode(), notice.body());
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, order));
    }

    @Test
    void checkoutWaitingOnShopLockRechecksStoppedSku() throws Exception {
        CountDownLatch shopLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var stopper = pool.submit(() -> transactions.execute(tx -> {
                jdbc.queryForObject("SELECT id FROM shops WHERE id=? FOR UPDATE", Long.class, fixture.catalog.shop);
                products.updateSkuStatus(fixture.catalog.merchant.id(), fixture.product, fixture.sku, "STOPPED");
                shopLocked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("下单交错未释放店铺锁");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            try {
                assertTrue(shopLocked.await(5, TimeUnit.SECONDS));
                var checkout = pool.submit(() -> fixture.client.post("/checkouts/direct",
                        fixture.direct("race-" + UUID.randomUUID(), 1, "19.90")));
                assertThrows(TimeoutException.class, () -> checkout.get(200, TimeUnit.MILLISECONDS));
                release.countDown();
                stopper.get(5, TimeUnit.SECONDS);
                var rejected = checkout.get(5, TimeUnit.SECONDS);
                assertEquals(409, rejected.statusCode(), rejected.body());
            } finally {
                release.countDown();
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class,
                fixture.buyer.id()).intValue());
    }
}
