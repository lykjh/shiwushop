package com.shiwushop.integration.component.payment;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.service.MarketingOrderSettlement;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.trade.dto.shipment.ShipmentCreateDTO;
import com.shiwushop.trade.service.OrderPaymentService;
import com.shiwushop.trade.service.ShipmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.member.enabled=false", "shop.marketing.enabled=false",
        "server.port=18127",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=28379", "spring.data.redis.database=12",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ZeroCashCancellationConcurrencyTest {
    @DynamicPropertySource
    static void databaseCredentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.username",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")));
        properties.add("spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderPaymentService payments;
    @Autowired
    ShipmentService shipments;
    @MockitoSpyBean
    MarketingOrderSettlement promotions;
    @Autowired
    PlatformTransactionManager transactions;
    CommerceFixture fixture;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"));
        }
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
    }

    @AfterEach
    void close() {
        if (fixture != null)
            fixture.close();
    }

    @Test
    void simultaneousCancellationsReturnStockOnceAndBlockShipping() throws Exception {
        long order = zeroCashOrder();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Future<String> first = workers.submit(() -> {
                start.await();
                return payments.cancelOrder(fixture.buyer.id(), order);
            });
            Future<String> second = workers.submit(() -> {
                start.await();
                return payments.cancelOrder(fixture.buyer.id(), order);
            });
            start.countDown();
            assertEquals("CANCELLED", first.get(10, TimeUnit.SECONDS));
            assertEquals("CANCELLED", second.get(10, TimeUnit.SECONDS));
        }
        assertEquals("CANCELLED", payments.cancelOrder(fixture.buyer.id(), order));
        assertEquals("STOPPED", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                String.class, order));
        assertEquals(1, count("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=? AND status='REFUNDED'", order));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN "
                + "(SELECT id FROM order_items WHERE order_id=?) AND reason='REFUND_RETURN'", order));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE action='ZERO_CASH_CANCELLED' "
                + "AND target_id IN (SELECT id FROM after_sale_cases WHERE order_id=?)", order));
        assertEquals(20, count("SELECT stock FROM skus WHERE id=?", fixture.sku));
        assertEquals(409, shipResult(order));
        assertEquals(0, count("SELECT COUNT(*) FROM shipments WHERE order_id=?", order));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", order));
    }

    @Test
    void cancellationAndShippingCannotBothWin() throws Exception {
        long order = zeroCashOrder();
        var cancelThread = new AtomicReference<Thread>();
        var snapshotRead = new CountDownLatch(1);
        var shipmentCommitted = new CountDownLatch(1);
        new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                doAnswer(invocation -> {
                    var source = invocation.callRealMethod();
                    if (Thread.currentThread() == cancelThread.get()) {
                        snapshotRead.countDown();
                        if (!shipmentCommitted.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("发货等待超时");
                    }
                    return source;
                }).when(promotions).lockSource(order));
        List<String> results;
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<String> cancel = workers.submit(() -> {
                cancelThread.set(Thread.currentThread());
                try {
                    return payments.cancelOrder(fixture.buyer.id(), order);
                } catch (BusinessException conflict) {
                    return String.valueOf(conflict.getCode());
                }
            });
            try {
                assertTrue(snapshotRead.await(10, TimeUnit.SECONDS));
                Future<String> ship = workers.submit(() -> String.valueOf(shipResult(order)));
                String shipped = ship.get(10, TimeUnit.SECONDS);
                shipmentCommitted.countDown();
                results = List.of(cancel.get(10, TimeUnit.SECONDS), shipped);
            } finally {
                shipmentCommitted.countDown();
            }
        }
        String fulfillment = jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?", String.class,
                order);
        int stock = count("SELECT stock FROM skus WHERE id=?", fixture.sku);
        assertEquals(List.of("409", "200"), results,
                () -> "fulfillment=" + fulfillment + ", stock=" + stock
                        + ", shipments=" + count("SELECT COUNT(*) FROM shipments WHERE order_id=?", order)
                        + ", refunds=" + count("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=?", order));
        assertEquals("READY", fulfillment);
        assertEquals(results.get(0).equals("CANCELLED") ? 1 : 0,
                count("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=?", order));
        assertEquals(results.get(1).equals("200") ? 1 : 0,
                count("SELECT COUNT(*) FROM shipments WHERE order_id=?", order));
        assertEquals(results.get(0).equals("CANCELLED") ? 1 : 0,
                count("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id IN "
                        + "(SELECT id FROM order_items WHERE order_id=?) AND reason='REFUND_RETURN'", order));
        assertEquals(results.get(0).equals("CANCELLED") ? 1 : 0,
                count("SELECT COUNT(*) FROM audit_events WHERE action='ZERO_CASH_CANCELLED' "
                        + "AND target_id IN (SELECT id FROM after_sale_cases WHERE order_id=?)", order));
        assertEquals(results.get(0).equals("CANCELLED") ? 20 : 19,
                count("SELECT stock FROM skus WHERE id=?", fixture.sku));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", order));
    }

    @Test
    void completedLegacyCancellationOnlyClosesFulfillmentGate() throws Exception {
        long order = zeroCashOrder();
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        String key = "ZERO_CANCEL:" + UUID.randomUUID();
        jdbc.update("INSERT INTO after_sale_cases "
                        + "(order_id,request_key,type,status,case_origin,reason,refund_amount,requested_at,finished_at) "
                        + "VALUES (?,?,'REFUND_ONLY','REFUNDED','ZERO_CASH','旧版零现金取消',0,NOW(),NOW())",
                order, key);
        long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=? AND request_key=?",
                Long.class, order, key);
        jdbc.update("INSERT INTO after_sale_items "
                        + "(case_id,order_item_id,quantity,refund_amount,unit_allocation) "
                        + "VALUES (?,?,1,0,'[{\"from\":1,\"to\":1,\"cashMinor\":0,\"pointUnits\":0}]')",
                sale, item);
        jdbc.update("UPDATE skus SET stock=20 WHERE id=?", fixture.sku);
        jdbc.update("INSERT INTO inventory_movements "
                        + "(sku_id,order_item_id,reason,delta,balance_after,operation_key) "
                        + "VALUES (?,?,'REFUND_RETURN',1,20,?)",
                fixture.sku, item, "REFUND_SUCCESS:" + sale + ":" + item);
        jdbc.update("INSERT INTO audit_events "
                        + "(actor_user_id,action,target_type,target_id,summary,occurred_at) "
                        + "VALUES (?,'ZERO_CASH_CANCELLED','AFTER_SALE',?,'旧版零现金取消',NOW())",
                fixture.buyer.id(), sale);

        assertEquals("CANCELLED", payments.cancelOrder(fixture.buyer.id(), order));
        assertEquals("CANCELLED", payments.cancelOrder(fixture.buyer.id(), order));
        assertEquals("STOPPED", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                String.class, order));
        assertEquals(1, count("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=?", order));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id=? "
                + "AND reason='REFUND_RETURN'", item));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE target_id=? "
                + "AND action='ZERO_CASH_CANCELLED'", sale));
        assertEquals(20, count("SELECT stock FROM skus WHERE id=?", fixture.sku));
        assertEquals(409, shipResult(order));
        assertEquals(0, count("SELECT COUNT(*) FROM shipments WHERE order_id=?", order));
    }

    private long zeroCashOrder() throws Exception {
        long order = fixture.order("zero-cancel-concurrency");
        // 将真实下单夹具切到零现金已结算状态，隔离本测试所需的交易竞态。
        jdbc.update("UPDATE orders SET status='PAID', settlement_kind='ZERO_CASH', total_amount=0, "
                + "original_amount=19.90, discount_amount=19.90, paid_at=NOW(), pay_expires_at=NULL WHERE id=?", order);
        jdbc.update("UPDATE order_items SET original_unit_price=19.90, discount_amount=19.90, "
                + "payable_amount=0 WHERE order_id=?", order);
        return order;
    }

    private int shipResult(long order) {
        try {
            shipments.ship(fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                    new ShipmentCreateDTO("顺丰", "ZERO-CANCEL-RACE"));
            return 200;
        } catch (BusinessException conflict) {
            return conflict.getCode();
        }
    }

    private int count(String sql, Object id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }
}
