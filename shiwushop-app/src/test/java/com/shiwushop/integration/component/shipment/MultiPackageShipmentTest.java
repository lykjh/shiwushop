package com.shiwushop.integration.component.shipment;

import com.shiwushop.common.BusinessException;
import com.shiwushop.aftersale.dto.AfterSaleCreateDTO;
import com.shiwushop.aftersale.dto.AfterSaleReviewDTO;
import com.shiwushop.aftersale.service.AfterSaleService;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.trade.dto.shipment.ShipmentPackageCreateDTO;
import com.shiwushop.trade.service.ShipmentService;
import com.shiwushop.trade.mapper.order.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.autoconfigure.exclude=", "server.port=18127", "shop.member.enabled=false",
        "shop.marketing.enabled=false", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=28379", "spring.data.redis.database=12"})
class MultiPackageShipmentTest {
    @DynamicPropertySource
    static void databaseCredentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.username",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")));
        properties.add("spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ShipmentService shipments;
    @Autowired
    AfterSaleService afterSales;
    @Autowired
    OrderMapper orders;
    CommerceFixture fixture;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertEquals(true, connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"));
        }
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:18127");
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            jdbc.update("DELETE sc FROM shipment_corrections sc JOIN orders o ON o.id=sc.order_id "
                    + "WHERE o.user_id=?", fixture.buyer.id());
            jdbc.update("DELETE a FROM marketing_refund_allocations a "
                    + "JOIN after_sale_items ai ON ai.id=a.after_sale_item_id "
                    + "JOIN after_sale_cases c ON c.id=ai.case_id "
                    + "JOIN orders o ON o.id=c.order_id WHERE o.user_id=?", fixture.buyer.id());
            jdbc.update("DELETE si FROM shipment_items si JOIN shipments s ON s.id=si.shipment_id "
                    + "JOIN orders o ON o.id=s.order_id WHERE o.user_id=?", fixture.buyer.id());
            fixture.close();
        }
    }

    @Test
    void singlePackageKeepsLegacyShipmentAndReceiptRoutes() throws Exception {
        var checkout = fixture.client.post("/checkouts/direct", fixture.direct("legacy-one", 1, "19.90"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = new ObjectMapper().readTree(checkout.body()).path("data").path("orders").get(0)
                .path("id").asLong();
        jdbc.update("UPDATE orders SET status='PAID', paid_at=NOW() WHERE id=?", order);
        var shipped = fixture.merchantClient.post("/merchant/shops/" + fixture.catalog.shop + "/orders/"
                + order + "/shipment", Map.of("carrierName", "顺丰", "trackingNo", "SF-LEGACY"));
        assertEquals(200, shipped.statusCode(), shipped.body());
        assertEquals(1, shipments.buyerPackages(fixture.buyer.id(), order).size());
        var confirmed = fixture.client.post("/orders/" + order + "/shipment/confirm", Map.of());
        assertEquals(200, confirmed.statusCode(), confirmed.body());
        assertEquals("RECEIVED", orders.fulfillmentProgress(order).getReceiptProgress());
        assertNotNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                java.time.LocalDateTime.class, order));
        assertEquals(200, fixture.client.post("/orders/" + order + "/shipment/confirm", Map.of()).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE target_type='SHIPMENT' "
                + "AND action='SHIPMENT_CONFIRMED_BY_BUYER' AND target_id IN "
                + "(SELECT id FROM shipments WHERE order_id=?)", Integer.class, order));
    }

    @Test
    void parcelsTrackQuantitiesAndOnlyLastReceiptCompletesOrder() throws Exception {
        var checkout = fixture.client.post("/checkouts/direct", fixture.direct("three-units", 3, "59.70"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = new ObjectMapper().readTree(checkout.body()).path("data").path("orders").get(0)
                .path("id").asLong();
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        jdbc.update("UPDATE orders SET status='PAID', paid_at=NOW() WHERE id=?", order);

        var firstRequest = new ShipmentPackageCreateDTO("parcel-one", "顺丰", "SF-ONE",
                List.of(new ShipmentPackageCreateDTO.Item(item, 1)));
        var first = shipments.shipPackage(fixture.catalog.merchant.id(), fixture.catalog.shop, order, firstRequest);
        assertEquals("PARTIAL", orders.fulfillmentProgress(order).getShippingProgress());
        assertEquals("NOT_RECEIVED", orders.fulfillmentProgress(order).getReceiptProgress());
        assertEquals(first.shipmentId(), shipments.shipPackage(
                fixture.catalog.merchant.id(), fixture.catalog.shop, order, firstRequest).shipmentId());
        assertEquals(10, Duration.between(first.shippedAt(), first.autoConfirmAt()).toDays());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> shipments.shipPackage(fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                        new ShipmentPackageCreateDTO("parcel-too-many", "顺丰", "SF-EXCESS",
                                List.of(new ShipmentPackageCreateDTO.Item(item, 3))))).getCode());
        var second = shipments.shipPackage(fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                new ShipmentPackageCreateDTO("parcel-two", "顺丰", "SF-TWO",
                        List.of(new ShipmentPackageCreateDTO.Item(item, 2))));
        assertEquals(2, shipments.buyerPackages(fixture.buyer.id(), order).size());
        Map<String, Object> correction = Map.of("originalVersion", 0, "carrierName", "中通",
                "trackingNo", "ZT-ONE", "reason", "录错单号");
        String merchantPath = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order;
        assertEquals(409, fixture.merchantClient.post(merchantPath + "/shipment/corrections",
                correction).statusCode());
        assertEquals(200, fixture.merchantClient.post(merchantPath + "/shipments/" + first.shipmentId()
                + "/corrections", correction).statusCode());
        assertEquals(first.shipmentId(), jdbc.queryForObject(
                "SELECT shipment_id FROM shipment_corrections WHERE order_id=?", Long.class, order));
        assertEquals(200, fixture.client.get("/orders/" + order + "/shipments/" + first.shipmentId()
                + "/corrections").statusCode());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> shipments.confirmByBuyer(fixture.buyer.id(), order)).getCode());

        shipments.confirmPackageByBuyer(fixture.buyer.id(), order, first.shipmentId());
        assertEquals("PARTIAL", orders.fulfillmentProgress(order).getReceiptProgress());
        assertNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                java.time.LocalDateTime.class, order));
        shipments.confirmPackageByBuyer(fixture.buyer.id(), order, second.shipmentId());
        assertEquals("RECEIVED", orders.fulfillmentProgress(order).getReceiptProgress());
        var detail = fixture.client.get("/orders/" + order);
        assertEquals(200, detail.statusCode(), detail.body());
        assertEquals("RECEIVED", new ObjectMapper().readTree(detail.body())
                .path("data").path("fulfillment").path("receiptProgress").asText());
        assertNotNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                java.time.LocalDateTime.class, order));
        shipments.confirmPackageByBuyer(fixture.buyer.id(), order, second.shipmentId());
        assertEquals(3, jdbc.queryForObject("SELECT SUM(quantity) FROM shipment_items si "
                + "JOIN shipments s ON s.id=si.shipment_id WHERE s.order_id=?", Integer.class, order));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE target_type='SHIPMENT' "
                + "AND action='SHIPMENT_CONFIRMED_BY_BUYER' AND target_id IN "
                + "(SELECT id FROM shipments WHERE order_id=?)", Integer.class, order));
    }

    @Test
    void refundingOneUnshippedUnitLeavesOneUnitAvailableForAnotherParcel() throws Exception {
        var checkout = fixture.client.post("/checkouts/direct", fixture.direct("partial-zero", 3, "59.70"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = new ObjectMapper().readTree(checkout.body()).path("data").path("orders").get(0)
                .path("id").asLong();
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        jdbc.update("UPDATE orders SET status='PAID', settlement_kind='ZERO_CASH', pricing_version=1, total_amount=0, "
                + "discount_amount=original_amount, "
                + "pay_expires_at=NULL, paid_at=NOW() "
                + "WHERE id=?", order);
        jdbc.update("UPDATE order_items SET payable_amount=0, "
                + "discount_amount=original_unit_price*quantity WHERE id=?", item);
        var first = shipments.shipPackage(fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                new ShipmentPackageCreateDTO("partial-first", "顺丰", "SF-PARTIAL-1",
                        List.of(new ShipmentPackageCreateDTO.Item(item, 1))));
        int stockBeforeRefund = jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku);
        var sale = afterSales.create(fixture.buyer.id(), order, "refund-unshipped-one",
                new AfterSaleCreateDTO("REFUND_ONLY", "未发货部分退款",
                        List.of(new AfterSaleCreateDTO.Item(item, 1, null, "UNSHIPPED"))));
        assertEquals("UNSHIPPED", jdbc.queryForObject("SELECT source_kind FROM after_sale_items WHERE case_id=?",
                String.class, sale.id()));
        afterSales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id(),
                new AfterSaleReviewDTO(true, null));
        assertEquals("REFUNDED", afterSales.refund(fixture.catalog.merchant.id(), fixture.catalog.shop,
                sale.id()).status());
        assertEquals(1, orders.fulfillmentProgress(order).getRefundedUnshippedQuantity());
        assertEquals(1, orders.fulfillmentProgress(order).getAvailableToShipQuantity());
        assertEquals("READY", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                String.class, order));
        assertEquals(stockBeforeRefund + 1, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",
                Integer.class, fixture.sku));
        var second = shipments.shipPackage(fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                new ShipmentPackageCreateDTO("partial-second", "顺丰", "SF-PARTIAL-2",
                        List.of(new ShipmentPackageCreateDTO.Item(item, 1))));
        assertEquals(2, jdbc.queryForObject("SELECT SUM(quantity) FROM shipment_items si "
                + "JOIN shipments s ON s.id=si.shipment_id WHERE s.order_id=?", Integer.class, order));
        shipments.confirmPackageByBuyer(fixture.buyer.id(), order, first.shipmentId());
        assertNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                java.time.LocalDateTime.class, order));
        shipments.confirmPackageByBuyer(fixture.buyer.id(), order, second.shipmentId());
        assertNotNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                java.time.LocalDateTime.class, order));
    }

    @Test
    void directFullZeroCashRefundStopsUnshippedFulfillmentOnlyOnce() throws Exception {
        var checkout = fixture.client.post("/checkouts/direct", fixture.direct("full-zero-refund", 1, "19.90"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = new ObjectMapper().readTree(checkout.body()).path("data").path("orders").get(0)
                .path("id").asLong();
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        jdbc.update("UPDATE orders SET status='PAID', settlement_kind='ZERO_CASH', pricing_version=1, total_amount=0, "
                + "discount_amount=original_amount,pay_expires_at=NULL,paid_at=NOW() WHERE id=?", order);
        jdbc.update("UPDATE order_items SET payable_amount=0,discount_amount=original_unit_price*quantity WHERE id=?",
                item);
        var sale = afterSales.create(fixture.buyer.id(), order, "full-zero-refund-request",
                new AfterSaleCreateDTO("REFUND_ONLY", "未发货全额退款",
                        List.of(new AfterSaleCreateDTO.Item(item, 1, null, "UNSHIPPED"))));
        afterSales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id(),
                new AfterSaleReviewDTO(true, null));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var refunded = workers.submit(() -> {
                start.await();
                return afterSales.refund(fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id()).status();
            });
            var shipped = workers.submit(() -> {
                start.await();
                return assertThrows(BusinessException.class, () -> shipments.shipPackage(
                        fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                        new ShipmentPackageCreateDTO("racing-full-refund", "顺丰", "SF-RACE",
                                List.of(new ShipmentPackageCreateDTO.Item(item, 1))))).getCode();
            });
            start.countDown();
            assertEquals("REFUNDED", refunded.get(10, TimeUnit.SECONDS));
            assertEquals(409, shipped.get(10, TimeUnit.SECONDS));
        }
        assertEquals("REFUNDED", afterSales.refund(fixture.catalog.merchant.id(), fixture.catalog.shop,
                sale.id()).status());
        assertEquals("STOPPED", jdbc.queryForObject("SELECT fulfillment_state FROM orders WHERE id=?",
                String.class, order));
        assertEquals(20, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
        assertEquals(409, assertThrows(BusinessException.class, () -> shipments.shipPackage(
                fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                new ShipmentPackageCreateDTO("after-full-refund", "顺丰", "SF-FULL",
                        List.of(new ShipmentPackageCreateDTO.Item(item, 1))))).getCode());
    }

    @Test
    void refundedUnitInsideParcelDoesNotBlockRemainingUnitOrEarlyAutoConfirm() throws Exception {
        var checkout = fixture.client.post("/checkouts/direct", fixture.direct("shipped-refund", 3, "59.70"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = new ObjectMapper().readTree(checkout.body()).path("data").path("orders").get(0)
                .path("id").asLong();
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        jdbc.update("UPDATE orders SET status='PAID', settlement_kind='ZERO_CASH', pricing_version=1, total_amount=0, "
                + "discount_amount=original_amount, pay_expires_at=NULL, paid_at=NOW() WHERE id=?", order);
        jdbc.update("UPDATE order_items SET payable_amount=0, "
                + "discount_amount=original_unit_price*quantity WHERE id=?", item);
        var first = shipments.shipPackage(fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                new ShipmentPackageCreateDTO("shipped-first", "顺丰", "SF-SHIPPED-1",
                        List.of(new ShipmentPackageCreateDTO.Item(item, 2))));
        int stockBeforeRefund = jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku);
        var sale = afterSales.create(fixture.buyer.id(), order, "refund-shipped-one",
                new AfterSaleCreateDTO("REFUND_ONLY", "已发一件退款",
                        List.of(new AfterSaleCreateDTO.Item(item, 1, first.shipmentId(), "SHIPMENT"))));
        assertEquals(first.shipmentId(), jdbc.queryForObject(
                "SELECT source_shipment_id FROM after_sale_items WHERE case_id=?", Long.class, sale.id()));
        afterSales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id(),
                new AfterSaleReviewDTO(true, null));
        assertEquals("REFUNDED", afterSales.refund(fixture.catalog.merchant.id(), fixture.catalog.shop,
                sale.id()).status());
        assertEquals(1, orders.fulfillmentProgress(order).getRefundedShippedQuantity());
        assertEquals(1, orders.fulfillmentProgress(order).getEffectiveShippedQuantity());
        assertEquals(stockBeforeRefund, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",
                Integer.class, fixture.sku));
        assertEquals(false, shipments.confirmDue(first.shipmentId()));
        jdbc.update("UPDATE shipments SET shipped_at=NOW()-INTERVAL 11 DAY, "
                + "auto_confirm_at=NOW()-INTERVAL 1 DAY WHERE id=?", first.shipmentId());
        assertEquals(true, shipments.confirmDue(first.shipmentId()));
        assertEquals(true, shipments.confirmDue(first.shipmentId()));
        assertNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                java.time.LocalDateTime.class, order));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE target_type='SHIPMENT' "
                + "AND action='SHIPMENT_CONFIRMED_BY_SYSTEM' AND target_id=?", Integer.class, first.shipmentId()));
        var second = shipments.shipPackage(fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                new ShipmentPackageCreateDTO("shipped-second", "顺丰", "SF-SHIPPED-2",
                        List.of(new ShipmentPackageCreateDTO.Item(item, 1))));
        shipments.confirmPackageByBuyer(fixture.buyer.id(), order, second.shipmentId());
        assertNotNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                java.time.LocalDateTime.class, order));
    }

    @Test
    void refundingLastUnshippedUnitAfterParcelConfirmationCompletesReceiptAtConfirmationTime()
            throws Exception {
        var checkout = fixture.client.post("/checkouts/direct", fixture.direct("late-unshipped-refund", 3, "59.70"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = new ObjectMapper().readTree(checkout.body()).path("data").path("orders").get(0)
                .path("id").asLong();
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        jdbc.update("UPDATE orders SET status='PAID', settlement_kind='ZERO_CASH', pricing_version=1, total_amount=0, "
                + "discount_amount=original_amount, pay_expires_at=NULL, paid_at=NOW() WHERE id=?", order);
        jdbc.update("UPDATE order_items SET payable_amount=0, "
                + "discount_amount=original_unit_price*quantity WHERE id=?", item);
        var parcel = shipments.shipPackage(fixture.catalog.merchant.id(), fixture.catalog.shop, order,
                new ShipmentPackageCreateDTO("late-refund-parcel", "顺丰", "SF-LATE",
                        List.of(new ShipmentPackageCreateDTO.Item(item, 2))));
        shipments.confirmPackageByBuyer(fixture.buyer.id(), order, parcel.shipmentId());
        assertNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                java.time.LocalDateTime.class, order));
        var sale = afterSales.create(fixture.buyer.id(), order, "late-unshipped-one",
                new AfterSaleCreateDTO("REFUND_ONLY", "余量退款",
                        List.of(new AfterSaleCreateDTO.Item(item, 1, null, "UNSHIPPED"))));
        afterSales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale.id(),
                new AfterSaleReviewDTO(true, null));
        assertEquals("REFUNDED", afterSales.refund(fixture.catalog.merchant.id(), fixture.catalog.shop,
                sale.id()).status());
        assertEquals(jdbc.queryForObject("SELECT confirmed_at FROM shipments WHERE id=?",
                        java.time.LocalDateTime.class, parcel.shipmentId()),
                jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?",
                        java.time.LocalDateTime.class, order));
    }
}
