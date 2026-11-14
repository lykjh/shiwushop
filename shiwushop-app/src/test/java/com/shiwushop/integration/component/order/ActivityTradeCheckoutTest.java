package com.shiwushop.integration.component.order;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.privacy.PrivacyCipher;
import com.shiwushop.aftersale.dto.AfterSaleCreateDTO;
import com.shiwushop.aftersale.dto.AfterSaleReviewDTO;
import com.shiwushop.aftersale.service.AfterSaleService;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.payment.service.RefundGateway;
import com.shiwushop.trade.payment.LocalSimulationGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.trade.dto.cart.DirectCheckoutDTO;
import com.shiwushop.trade.dto.shipment.ShipmentPackageCreateDTO;
import com.shiwushop.trade.service.CheckoutService;
import com.shiwushop.trade.service.OrderPaymentService;
import com.shiwushop.trade.service.ShipmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.autoconfigure.exclude=", "server.port=18128", "shop.member.enabled=true",
        "shop.marketing.enabled=true", "shop.member.reward-start-at=2026-11-01T00:00:00",
        "shop.member.scan-delay-ms=3600000",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_test4_20261105b"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=28379", "spring.data.redis.database=12"})
class ActivityTradeCheckoutTest {
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.username",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")));
        properties.add("spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired CheckoutService checkout;
    @Autowired OrderPaymentService payments;
    @Autowired AfterSaleService afterSales;
    @Autowired MemberAccountService memberAccounts;
    @Autowired ShipmentService shipments;
    @Autowired PrivacyCipher privacyCipher;
    @MockitoBean(name = "localSimulationGateway") LocalSimulationGateway gateway;
    CommerceFixture fixture;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_test4_20261105b"));
        }
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:18128");
    }

    @AfterEach
    void close() {
        if (fixture != null)
            fixture.close();
    }

    @Test
    void firstFlashOrderUsesActivityPoolAndReplaysOneOrder() {
        transactions.executeWithoutResult(status -> {
            long scope = activity("FLASH");
            var request = direct("FLASH", scope, "flash-first");
            int normalStock = jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku);
            long order = checkout.checkoutDirect(fixture.buyer.id(), request).orders().getFirst().id();
            assertEquals(order, checkout.checkoutDirect(fixture.buyer.id(), request).orders().getFirst().id());
            assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE id=?", order));
            assertEquals(1, count("SELECT COUNT(*) FROM marketing_participations WHERE order_id=?", order));
            assertEquals(1, count("SELECT held_qty FROM marketing_stock_pools WHERE activity_sku_id=?", scope));
            assertEquals(1, count("SELECT reserved_qty FROM marketing_activity_skus WHERE id=?", scope));
            assertEquals(normalStock, count("SELECT stock FROM skus WHERE id=?", fixture.sku));
            assertEquals("UNPAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, order));
            status.setRollbackOnly();
        });
    }

    @Test
    void firstGroupOrderWaitsForGroupBeforeShipment() {
        transactions.executeWithoutResult(status -> {
            long scope = activity("GROUP");
            long order = checkout.checkoutDirect(fixture.buyer.id(), direct("GROUP", scope, "group-first"))
                    .orders().getFirst().id();
            assertEquals("WAIT_GROUP", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                    String.class, order));
            assertEquals(1, count("SELECT COUNT(*) FROM marketing_groups WHERE activity_sku_id=?", scope));
            assertEquals(409, assertThrows(BusinessException.class, () -> shipments.shipPackage(
                    fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                    new ShipmentPackageCreateDTO("group-before-success", "顺丰", "SF-GROUP",
                            List.of(new ShipmentPackageCreateDTO.Item(
                                    jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class,
                                            order), 1))))).getCode());
            status.setRollbackOnly();
        });
    }

    @Test
    void secondPaidMemberCompletesTwoPersonGroup() {
        successfulGateway();
        transactions.executeWithoutResult(status -> {
            long scope = activity("GROUP");
            long first = checkout.checkoutDirect(fixture.buyer.id(), direct("GROUP", scope, "group-two-first"))
                    .orders().getFirst().id();
            long group = jdbc.queryForObject("SELECT group_id FROM marketing_participations WHERE order_id=?",
                    Long.class, first);
            payments.start(fixture.buyer.id(), first);
            payments.notifyPaid(paidNotice(first));
            assertEquals("FORMING", jdbc.queryForObject("SELECT status FROM marketing_groups WHERE id=?",
                    String.class, group));
            assertEquals("WAIT_GROUP", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                    String.class, first));

            long secondBuyer = new TestAccounts(jdbc).create("USER", "Shop123!").id();
            jdbc.update("INSERT INTO addresses(user_id,consignee_ciphertext,phone_ciphertext,province,city,"
                            + "district,detail_ciphertext,key_version) VALUES(?,?,?,?,?,?,?,1)",
                    secondBuyer, privacyCipher.encrypt(secondBuyer, "consignee", "测试收货人"),
                    privacyCipher.encrypt(secondBuyer, "phone", "13812345678"),
                    "江苏省", "南京市", "鼓楼区",
                    privacyCipher.encrypt(secondBuyer, "detail", "中山北路28号"));
            long secondAddress = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            var request = direct("GROUP", scope, "group-two-second");
            request.setAddressId(secondAddress);
            request.setGroupId(group);
            long second = checkout.checkoutDirect(secondBuyer, request).orders().getFirst().id();
            payments.start(secondBuyer, second);
            payments.notifyPaid(paidNotice(second));
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM marketing_groups WHERE id=?",
                    String.class, group));
            assertEquals(2, count("SELECT paid_members FROM marketing_groups WHERE id=?", group));
            assertEquals("READY", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                    String.class, first));
            assertEquals("READY", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                    String.class, second));
            status.setRollbackOnly();
        });
    }

    @Test
    void cancelledFlashOrderReleasesOnlyActivityPoolOnce() {
        transactions.executeWithoutResult(status -> {
            long scope = activity("FLASH");
            long order = checkout.checkoutDirect(fixture.buyer.id(), direct("FLASH", scope, "flash-cancel"))
                    .orders().getFirst().id();
            int normalStock = count("SELECT stock FROM skus WHERE id=?", fixture.sku);
            assertEquals("CANCELLED", payments.cancelOrder(fixture.buyer.id(), order));
            assertEquals("CANCELLED", payments.cancelOrder(fixture.buyer.id(), order));
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class,
                    order));
            assertEquals("RELEASED", jdbc.queryForObject(
                    "SELECT status FROM marketing_participations WHERE order_id=?", String.class, order));
            assertEquals(2, count("SELECT available_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                    scope));
            assertEquals(0, count("SELECT held_qty FROM marketing_stock_pools WHERE activity_sku_id=?", scope));
            assertEquals(normalStock, count("SELECT stock FROM skus WHERE id=?", fixture.sku));
            status.setRollbackOnly();
        });
    }

    @Test
    void latePaidFailedGroupCreatesOneRefundAndNeverShips() {
        successfulGateway();
        transactions.executeWithoutResult(status -> {
            long scope = activity("GROUP");
            long order = checkout.checkoutDirect(fixture.buyer.id(), direct("GROUP", scope, "group-late"))
                    .orders().getFirst().id();
            payments.start(fixture.buyer.id(), order);
            jdbc.update("UPDATE marketing_groups SET status='FAILED' WHERE id=(SELECT group_id "
                    + "FROM marketing_participations WHERE order_id=?)", order);
            var notice = paidNotice(order);
            payments.notifyPaid(notice);
            payments.notifyPaid(notice);
            assertEquals("FAILED", jdbc.queryForObject("SELECT g.status FROM marketing_groups g "
                    + "JOIN marketing_participations p ON p.group_id=g.id WHERE p.order_id=?", String.class,
                    order));
            assertEquals("REFUNDED", jdbc.queryForObject(
                    "SELECT status FROM marketing_participations WHERE order_id=?", String.class, order));
            assertEquals("STOPPED", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                    String.class, order));
            assertEquals(1, count("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=?", order));
            assertEquals(1, count("SELECT COUNT(*) FROM refund_attempts r JOIN after_sale_cases c "
                    + "ON c.id=r.case_id WHERE c.order_id=?", order));
            assertEquals(2, count("SELECT available_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                    scope));
            assertEquals(409, assertThrows(BusinessException.class, () -> shipments.shipPackage(
                    fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                    new ShipmentPackageCreateDTO("failed-group", "顺丰", "SF-FAILED",
                            List.of(new ShipmentPackageCreateDTO.Item(jdbc.queryForObject(
                                    "SELECT id FROM order_items WHERE order_id=?", Long.class, order), 1)))))
                    .getCode());
            verify(gateway, times(1)).refund(anyString(), anyString(), anyString(), any());
            status.setRollbackOnly();
        });
    }

    @Test
    void groupThatFailsAfterPaymentIsRefundedByScheduledScan() {
        successfulGateway();
        transactions.executeWithoutResult(status -> {
            long scope = activity("GROUP");
            long order = checkout.checkoutDirect(fixture.buyer.id(), direct("GROUP", scope, "group-paid-first"))
                    .orders().getFirst().id();
            payments.start(fixture.buyer.id(), order);
            payments.notifyPaid(paidNotice(order));
            assertEquals("PAID", jdbc.queryForObject(
                    "SELECT status FROM marketing_participations WHERE order_id=?", String.class, order));
            assertEquals("WAIT_GROUP", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                    String.class, order));
            jdbc.update("UPDATE marketing_groups SET status='FAILED' WHERE id=(SELECT group_id "
                    + "FROM marketing_participations WHERE order_id=?)", order);
            payments.expireDueOrders();
            payments.expireDueOrders();
            assertEquals("REFUNDED", jdbc.queryForObject(
                    "SELECT status FROM marketing_participations WHERE order_id=?", String.class, order));
            assertEquals("STOPPED", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                    String.class, order));
            assertEquals(1, count("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=?", order));
            assertEquals(1, count("SELECT COUNT(*) FROM refund_attempts r JOIN after_sale_cases c "
                    + "ON c.id=r.case_id WHERE c.order_id=?", order));
            assertEquals(2, count("SELECT available_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                    scope));
            verify(gateway, times(1)).refund(anyString(), anyString(), anyString(), any());
            status.setRollbackOnly();
        });
    }

    @Test
    void pointsCheckoutAndCancellationRestoreFourLedgersWithoutGateway() {
        transactions.executeWithoutResult(status -> {
            seedPoints();
            long scope = activity("POINTS");
            var request = direct("POINTS", scope, "points-cancel");
            long order = checkout.checkoutDirect(fixture.buyer.id(), request).orders().getFirst().id();
            assertEquals(order, checkout.checkoutDirect(fixture.buyer.id(), request).orders().getFirst().id());
            assertPointsOrder(order, scope, 500, 1);
            assertEquals("CANCELLED", payments.cancelOrder(fixture.buyer.id(), order));
            assertEquals("CANCELLED", payments.cancelOrder(fixture.buyer.id(), order));
            assertPointsReturned(order, scope);
            status.setRollbackOnly();
        });
    }

    @Test
    void pointsKeysAreCaseSensitiveAndChangedRequestConflicts() {
        transactions.executeWithoutResult(status -> {
            seedPoints();
            long scope = activity("POINTS");
            var upper = direct("POINTS", scope, "points-Aa");
            long first = checkout.checkoutDirect(fixture.buyer.id(), upper).orders().getFirst().id();
            assertEquals(first, checkout.checkoutDirect(fixture.buyer.id(), upper).orders().getFirst().id());
            long second = checkout.checkoutDirect(fixture.buyer.id(), direct("POINTS", scope, "points-aa"))
                    .orders().getFirst().id();
            assertTrue(first != second);
            var changed = direct("POINTS", scope, "points-Aa");
            changed.setQuantity(2);
            changed.setConfirmedPointUnits(1000L);
            assertEquals(409, assertThrows(BusinessException.class,
                    () -> checkout.checkoutDirect(fixture.buyer.id(), changed)).getCode());
            assertEquals(2, count("SELECT COUNT(*) FROM orders WHERE user_id=?", fixture.buyer.id()));
            assertEquals(0, count("SELECT available_point_units FROM member_accounts WHERE user_id=?",
                    fixture.buyer.id()));
            assertEquals(2, count("SELECT consumed_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                    scope));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_attempts WHERE order_id IN (?,?)", Integer.class, first, second));
            status.setRollbackOnly();
        });
    }

    @Test
    void pointsDirectRefundRestoresFourLedgersWithoutGateway() {
        transactions.executeWithoutResult(status -> {
            seedPoints();
            long scope = activity("POINTS");
            long order = checkout.checkoutDirect(fixture.buyer.id(), direct("POINTS", scope, "points-refund"))
                    .orders().getFirst().id();
            assertPointsOrder(order, scope, 500, 1);
            long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            var sale = afterSales.create(fixture.buyer.id(), order, "points-sale",
                    new AfterSaleCreateDTO("REFUND_ONLY", "不再兑换", List.of(
                            new AfterSaleCreateDTO.Item(item, 1))));
            afterSales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id(),
                    new AfterSaleReviewDTO(true, null));
            assertEquals("REFUNDED", afterSales.refund(
                    fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id()).status());
            assertEquals("REFUNDED", afterSales.refund(
                    fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id()).status());
            assertPointsReturned(order, scope);
            status.setRollbackOnly();
        });
    }

    @Test
    void pointsPartialRefundKeepsRemainingUnitFulfillable() {
        transactions.executeWithoutResult(status -> {
            seedPoints();
            long scope = activity("POINTS");
            var request = direct("POINTS", scope, "points-partial");
            request.setQuantity(2);
            request.setConfirmedPointUnits(1000L);
            long order = checkout.checkoutDirect(fixture.buyer.id(), request).orders().getFirst().id();
            long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            var sale = afterSales.create(fixture.buyer.id(), order, "points-partial-sale",
                    new AfterSaleCreateDTO("REFUND_ONLY", "退回一件", List.of(
                            new AfterSaleCreateDTO.Item(item, 1))));
            afterSales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id(),
                    new AfterSaleReviewDTO(true, null));
            assertEquals("REFUNDED", afterSales.refund(
                    fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id()).status());
            assertEquals("REFUNDED", afterSales.refund(
                    fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id()).status());
            assertEquals(500, count("SELECT available_point_units FROM member_accounts WHERE user_id=?",
                    fixture.buyer.id()));
            assertEquals(500, count("SELECT spent_units FROM member_point_lots WHERE user_id=?",
                    fixture.buyer.id()));
            assertEquals(500, count("SELECT returned_point_units FROM point_redemptions WHERE order_id=?", order));
            assertEquals(1, count("SELECT available_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                    scope));
            assertEquals(1, count("SELECT consumed_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                    scope));
            assertEquals("READY", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                    String.class, order));
            assertEquals(0, count("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", order));
            assertEquals("RETURNING", jdbc.queryForObject("SELECT status FROM point_redemptions WHERE order_id=?",
                    String.class, order));
            status.setRollbackOnly();
        });
    }

    private void seedPoints() {
        memberAccounts.initializeAndLock(fixture.buyer.id());
        jdbc.update("UPDATE member_accounts SET available_point_units=1000 WHERE user_id=?", fixture.buyer.id());
        String key = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO member_point_lots(user_id,source_key,issued_units,available_units,earned_at,"
                        + "expires_at,status) VALUES(?,?,1000,1000,NOW(),NOW()+INTERVAL 30 DAY,'ACTIVE')",
                fixture.buyer.id(), "POINTS-TEST:" + key);
        long lot = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO member_point_entries(user_id,event_kind,operation_key,available_delta,"
                        + "available_after,held_after,debt_after) VALUES(?,'EARN',?,1000,1000,0,0)",
                fixture.buyer.id(), "POINTS-TEST:EARN:" + key);
        long entry = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO member_point_allocations(entry_id,lot_id,allocation_kind,units) "
                + "VALUES(?,?,'EARN',1000)", entry, lot);
    }

    private void assertPointsOrder(long order, long scope, int remaining, int consumed) {
        assertEquals("POINTS", jdbc.queryForObject("SELECT settlement_kind FROM orders WHERE id=?",
                String.class, order));
        assertEquals(0, jdbc.queryForObject("SELECT total_amount FROM orders WHERE id=?",
                BigDecimal.class, order).signum());
        assertEquals("REDEEM", jdbc.queryForObject("SELECT item_kind FROM order_items WHERE order_id=?",
                String.class, order));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", order));
        assertEquals(remaining, count("SELECT available_point_units FROM member_accounts WHERE user_id=?",
                fixture.buyer.id()));
        assertEquals(consumed, count("SELECT consumed_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                scope));
        assertEquals(500, count("SELECT spent_point_units FROM point_redemptions WHERE order_id=?", order));
        assertEquals(1, count("SELECT COUNT(*) FROM member_point_entries WHERE order_id=? AND event_kind='SPEND'",
                order));
    }

    private void assertPointsReturned(long order, long scope) {
        assertEquals(1000, count("SELECT available_point_units FROM member_accounts WHERE user_id=?",
                fixture.buyer.id()));
        assertEquals(0, count("SELECT spent_units FROM member_point_lots WHERE user_id=?", fixture.buyer.id()));
        assertEquals(2, count("SELECT available_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                scope));
        assertEquals(0, count("SELECT consumed_qty FROM marketing_stock_pools WHERE activity_sku_id=?",
                scope));
        assertEquals(500, count("SELECT returned_point_units FROM point_redemptions WHERE order_id=?", order));
        assertEquals(1, count("SELECT COUNT(*) FROM member_point_entries WHERE order_id=? AND event_kind='RELEASE'",
                order));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_attempts WHERE order_id=?", order));
        assertEquals("RETURNED", jdbc.queryForObject("SELECT status FROM point_redemptions WHERE order_id=?",
                String.class, order));
    }

    private void successfulGateway() {
        when(gateway.configured()).thenReturn(true);
        when(gateway.channel()).thenReturn("ALIPAY_SANDBOX");
        when(gateway.appId()).thenReturn("sandbox-app");
        when(gateway.sellerId()).thenReturn("sandbox-seller");
        when(gateway.verify(any())).thenReturn(true);
        when(gateway.refund(anyString(), anyString(), anyString(), any())).thenAnswer(call ->
                new RefundGateway.Result(true, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3)));
    }

    private Map<String, String> paidNotice(long order) {
        String outTradeNo = jdbc.queryForObject("SELECT out_trade_no FROM payment_attempts WHERE order_id=?",
                String.class, order);
        return Map.of("out_trade_no", outTradeNo, "trade_no", "group-trade-" + order,
                "trade_status", "TRADE_SUCCESS", "total_amount", "5.00",
                "app_id", "sandbox-app", "seller_id", "sandbox-seller");
    }

    private long activity(String type) {
        String key = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO marketing_activities(activity_code,version_no,name,activity_type,status,start_at,"
                        + "end_at,eligible_level_codes,creator_user_id,rules_text,request_scope,request_key,request_hash) "
                        + "VALUES(?,1,'trade source',?,'RUNNING',NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY,"
                        + "JSON_ARRAY('ORDINARY'),?,'source test','TRADE_TEST',?,UNHEX(REPEAT('a',64)))",
                key, type, fixture.catalog.merchant.id(), key);
        long activity = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO marketing_activity_skus(activity_id,sku_id,scope_role,activity_price,"
                        + "point_cost_units,per_user_limit,sales_limit,team_size,participation_seconds) "
                        + "VALUES(?,?,?,?,?,2,2,?,?)",
                activity, fixture.sku, "POINTS".equals(type) ? "REDEEM" : "SALE",
                "POINTS".equals(type) ? null : new BigDecimal("5.00"),
                "POINTS".equals(type) ? 500L : null,
                "GROUP".equals(type) ? 2 : null, "GROUP".equals(type) ? 3600 : null);
        long scope = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO marketing_stock_pools(activity_sku_id,allocated_qty,available_qty) VALUES(?,2,2)",
                scope);
        return scope;
    }

    private DirectCheckoutDTO direct(String type, long scope, String key) {
        var request = new DirectCheckoutDTO();
        request.setSkuId(fixture.sku);
        request.setQuantity(1);
        request.setAddressId(fixture.address);
        request.setBusinessType(type);
        request.setActivitySkuId(scope);
        request.setConfirmedTotal("POINTS".equals(type) ? BigDecimal.ZERO : new BigDecimal("5.00"));
        if ("POINTS".equals(type)) request.setConfirmedPointUnits(500L);
        request.setIdempotencyKey(key);
        return request;
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

}
