package com.shiwushop.integration.component.order;

import com.shiwushop.common.BusinessException;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.trade.dto.cart.CheckoutDTO;
import com.shiwushop.trade.dto.cart.DirectCheckoutDTO;
import com.shiwushop.trade.service.CheckoutService;
import com.shiwushop.trade.service.OrderPaymentService;
import com.shiwushop.user.service.impl.AccountDeletionRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.autoconfigure.exclude=", "server.port=18127", "shop.member.enabled=false",
        "shop.marketing.enabled=false", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=28379", "spring.data.redis.database=12"})
class CheckoutDeletionGateTest {
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.username",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")));
        properties.add("spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CheckoutService checkout;
    @Autowired OrderPaymentService payments;
    @Autowired AccountDeletionRequestService deletion;
    CommerceFixture fixture;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"));
        }
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:18127");
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            jdbc.update("DELETE FROM account_deletion_requests WHERE user_id=?", fixture.buyer.id());
            fixture.close();
        }
    }

    @Test
    void pendingRejectsBothNewCheckoutEntrancesWithoutTakingStock() throws Exception {
        var added = fixture.client.post("/cart/items", Map.of("skuId", fixture.sku, "quantity", 1));
        assertEquals(200, added.statusCode(), added.body());
        long cartId = new tools.jackson.databind.ObjectMapper().readTree(added.body()).path("data").path("id").asLong();
        assertEquals("PENDING", requestDeletion());

        var directError = assertThrows(BusinessException.class,
                () -> checkout.checkoutDirect(fixture.buyer.id(), direct("pending-direct")));
        assertEquals(409, directError.getCode());
        assertEquals("ACCOUNT_DELETION_PENDING", ((Map<?, ?>) directError.getData()).get("reason"));
        var cart = new CheckoutDTO();
        cart.setCartItemIds(List.of(cartId));
        cart.setAddressId(fixture.address);
        cart.setConfirmedTotal(new BigDecimal("19.90"));
        cart.setIdempotencyKey("pending-cart");
        var cartError = assertThrows(BusinessException.class,
                () -> checkout.checkoutSelected(fixture.buyer.id(), cart));
        assertEquals(409, cartError.getCode());
        assertEquals("ACCOUNT_DELETION_PENDING", ((Map<?, ?>) cartError.getData()).get("reason"));
        assertEquals(0, count("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?"));
        assertEquals(0, count("SELECT COUNT(*) FROM orders WHERE user_id=?"));
        assertEquals(20, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
        assertEquals(1, count("SELECT COUNT(*) FROM cart_items WHERE user_id=?"));
    }

    @Test
    void checkoutCommittedBeforeDeletionKeepsItsCancellationResponsibility() {
        long order = checkout.checkoutDirect(fixture.buyer.id(), direct("before-deletion")).orders().getFirst().id();
        assertEquals(order, checkout.checkoutDirect(fixture.buyer.id(), direct("before-deletion"))
                .orders().getFirst().id());
        assertEquals("PENDING", requestDeletion());
        assertEquals("CANCELLED", payments.cancelOrder(fixture.buyer.id(), order));
        assertEquals(20, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
        assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE user_id=?"));
    }

    @Test
    void completedCartCheckoutReplaysAfterCartRowsAreRemoved() throws Exception {
        var added = fixture.client.post("/cart/items", Map.of("skuId", fixture.sku, "quantity", 1));
        assertEquals(200, added.statusCode(), added.body());
        long cartId = new tools.jackson.databind.ObjectMapper().readTree(added.body()).path("data").path("id").asLong();
        var cart = new CheckoutDTO();
        cart.setCartItemIds(List.of(cartId));
        cart.setAddressId(fixture.address);
        cart.setConfirmedTotal(new BigDecimal("19.90"));
        cart.setIdempotencyKey("cart-replay");
        long order = checkout.checkoutSelected(fixture.buyer.id(), cart).orders().getFirst().id();
        assertEquals(0, count("SELECT COUNT(*) FROM cart_items WHERE user_id=?"));
        assertEquals(order, checkout.checkoutSelected(fixture.buyer.id(), cart).orders().getFirst().id());
        assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE user_id=?"));
    }

    @Test
    void concurrentDeletionAndCheckoutHaveOneConsistentWinner() throws Exception {
        String result;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var created = workers.submit(() -> {
                start.await();
                try {
                    checkout.checkoutDirect(fixture.buyer.id(), direct("race-deletion"));
                    return "CREATED";
                } catch (BusinessException conflict) {
                    assertEquals(409, conflict.getCode());
                    assertEquals("ACCOUNT_DELETION_PENDING", ((Map<?, ?>) conflict.getData()).get("reason"));
                    return "BLOCKED";
                }
            });
            var requested = workers.submit(() -> {
                start.await();
                return requestDeletion();
            });
            start.countDown();
            result = created.get(15, TimeUnit.SECONDS);
            assertEquals("PENDING", requested.get(15, TimeUnit.SECONDS));
        }
        int expected = "CREATED".equals(result) ? 1 : 0;
        assertEquals(expected, count("SELECT COUNT(*) FROM orders WHERE user_id=?"));
        assertEquals(expected, count("SELECT COUNT(*) FROM checkout_requests WHERE user_id=?"));
        assertEquals(20 - expected, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class,
                fixture.sku));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> checkout.checkoutDirect(fixture.buyer.id(), direct("after-race"))).getCode());
    }

    private DirectCheckoutDTO direct(String key) {
        var request = new DirectCheckoutDTO();
        request.setSkuId(fixture.sku);
        request.setQuantity(1);
        request.setAddressId(fixture.address);
        request.setConfirmedTotal(new BigDecimal("19.90"));
        request.setIdempotencyKey(key);
        return request;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, fixture.buyer.id());
    }

    private String requestDeletion() {
        int version = jdbc.queryForObject("SELECT auth_version FROM user_accounts WHERE user_id=?",
                Integer.class, fixture.buyer.id());
        return deletion.request(fixture.buyer.id(), version).status();
    }
}
