package com.shiwushop.integration.commerce;

import com.shiwushop.support.*;
import com.shiwushop.trade.payment.LocalSimulationGateway;
import com.shiwushop.trade.shipment.ShipmentExpiryJob;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.autoconfigure.exclude=",
        "spring.datasource.hikari.maximum-pool-size=2", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "shop.shipment.simulation-enabled=true", "shop.shipment.scan-delay-ms=600000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShipmentHttpTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ShipmentExpiryJob expiry;
    @MockitoBean(name = "localSimulationGateway")
    LocalSimulationGateway gateway;
    CommerceFixture f;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void prepare() throws Exception {
        PaymentHttpFixture.configure(gateway);
        f = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
    }

    @AfterEach
    void cleanup() {
        if (f != null)
            f.close();
    }

    @Test
    void eachParcelUsesItsOwnSigningAndManualReceiptStillWorks() throws Exception {
        var checkout = f.client.post("/checkouts/direct", f.direct("two-parcels", 2, "39.80"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = json.readTree(checkout.body()).path("data").path("orders").get(0).path("id").asLong();
        var started = f.client.post("/orders/" + order + "/payment", Map.of());
        assertEquals(200, started.statusCode(), started.body());
        var notice = PaymentHttpFixture.notice(jdbc, order);
        notice.put("total_amount", "39.80");
        var paid = f.client.form("/payments/alipay/notify", notice);
        assertEquals("success", paid.body());
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        String merchant = "/merchant/shops/" + f.catalog.shop + "/orders/" + order + "/shipments";
        String buyer = "/orders/" + order + "/shipments";
        long[] packages = new long[2];
        for (int i = 0; i < packages.length; i++) {
            var shipped = f.merchantClient.post(merchant, Map.of("requestKey", "parcel-" + i,
                    "carrierName", "演示物流", "trackingNo", "DEMO-" + i,
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1))));
            assertEquals(200, shipped.statusCode(), shipped.body());
            var data = json.readTree(shipped.body()).path("data");
            packages[i] = data.path("shipmentId").asLong();
            assertTrue(data.path("autoConfirmAt").isNull());
        }
        String simulate = merchant + "/" + packages[0] + "/simulate-tracking";
        var transit = f.merchantClient.post(simulate, Map.of("step", "IN_TRANSIT"));
        assertEquals(200, transit.statusCode(), transit.body());
        var signed = f.merchantClient.post(simulate, Map.of("step", "SIGNED"));
        assertEquals(200, signed.statusCode(), signed.body());
        var data = json.readTree(signed.body()).path("data");
        assertTrue(data.path("simulatedInTransitAt").isString());
        var signedAt = LocalDateTime.parse(data.path("carrierSignedAt").asString());
        assertEquals(signedAt.plusDays(10), LocalDateTime.parse(data.path("autoConfirmAt").asString()));
        jdbc.update("""
                UPDATE shipments SET shipped_at=NOW()-INTERVAL 12 DAY,
                    simulated_in_transit_at=NOW()-INTERVAL 11 DAY,
                    carrier_signed_at=NOW()-INTERVAL 11 DAY, auto_confirm_at=NOW()-INTERVAL 1 DAY
                WHERE id=?
                """, packages[0]);
        expiry.confirmDue();
        assertEquals("SYSTEM", jdbc.queryForObject("SELECT confirm_source FROM shipments WHERE id=?",
                String.class, packages[0]));
        assertNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?", LocalDateTime.class, order));
        for (var parcels : List.of(f.client.get(buyer), f.merchantClient.get(merchant))) {
            assertEquals(200, parcels.statusCode(), parcels.body());
            var list = json.readTree(parcels.body()).path("data");
            assertEquals(2, list.size());
            assertEquals("CONFIRMED", list.get(0).path("status").asString());
            assertEquals("SHIPPED", list.get(1).path("status").asString());
            assertTrue(list.get(1).path("carrierSignedAt").isNull());
        }
        var confirmed = f.client.post(buyer + "/" + packages[1] + "/confirm", Map.of());
        assertEquals(200, confirmed.statusCode(), confirmed.body());
        assertEquals("BUYER", json.readTree(confirmed.body()).path("data").path("confirmSource").asString());
        assertNotNull(jdbc.queryForObject("SELECT fully_received_at FROM orders WHERE id=?", LocalDateTime.class, order));
    }

    @Test
    void paidOrderShipsIdempotentlyAndBuyerConfirmsOnce() throws Exception {
        long order = PaymentHttpFixture.paidOrder(f, jdbc);
        String path = "/merchant/shops/" + f.catalog.shop + "/orders/" + order + "/shipment";
        Map<String, Object> body = Map.of("carrierName", "顺丰速运", "trackingNo", "SF202611010001");
        var first = f.merchantClient.post(path, body);
        assertEquals(200, first.statusCode(), first.body());
        var retry = f.merchantClient.post(path, body);
        assertEquals(200, retry.statusCode());
        var firstData = json.readTree(first.body()).path("data");
        var retryData = json.readTree(retry.body()).path("data");
        for (String field : List.of("orderId", "status", "carrierName", "trackingNo"))
            assertEquals(firstData.path(field), retryData.path(field));
        // 当前 DATETIME 按秒落库；验证同一发货事实，不把响应纳秒与落库舍入差异当作重复发货。
        var firstTime = java.time.LocalDateTime.parse(firstData.path("shippedAt").asString());
        var storedTime = java.time.LocalDateTime.parse(retryData.path("shippedAt").asString());
        assertTrue(java.time.Duration.between(firstTime, storedTime).abs().toMillis() <= 1000);
        assertEquals(409, f.merchantClient.post(path, Map.of("carrierName", "顺丰速运", "trackingNo", "SF202611010002"))
                .statusCode());
        for (int n = 0; n < 2; n++) {
            var response = f.client.post("/orders/" + order + "/shipment/confirm", Map.of());
            assertEquals(200, response.statusCode(), response.body());
        }
        assertEquals("CONFIRMED",
                jdbc.queryForObject("SELECT status FROM shipments WHERE order_id=?", String.class, order));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, order));
    }

    @Test
    void unpaidOrderCannotBeShipped() throws Exception {
        long order = f.order("unpaid-shipment");
        var response = f.merchantClient.post("/merchant/shops/" + f.catalog.shop + "/orders/" + order + "/shipment",
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF202611010003"));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, order));
    }
}
