package com.shiwushop.integration.shipment;

import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.support.HttpAccountClient;
import com.shiwushop.merchant.service.ShopService;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Map;
import java.sql.DriverManager;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShipmentCorrectionHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShopService shopService;
    @MockitoBean SdkAlipayGateway gateway;
    CommerceFixture fixture;
    TestAccounts accounts;
    HttpAccountClient admin;
    final ObjectMapper json = new ObjectMapper();
    @Value("${spring.datasource.url}") String url;
    @Value("${spring.datasource.username}") String dbUser;
    @Value("${spring.datasource.password}") String dbPassword;

    @BeforeEach void prepare() throws Exception {
        PaymentHttpFixture.configure(gateway);
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        accounts = new TestAccounts(jdbc);
        var administrator = accounts.create("ADMIN", "Shop123!");
        admin = CommerceFixture.login("http://127.0.0.1:" + port, administrator);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM shipment_corrections WHERE shop_id=?", fixture.catalog.shop);
        admin.close();
        fixture.close();
        accounts.close();
    }

    @Test void merchantCanRequestCorrectionForShippedOrder() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        var shipped = fixture.merchantClient.post(shipment, Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001"));
        assertEquals(200, shipped.statusCode(), shipped.body());
        var response = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        assertEquals(200, response.statusCode(), response.body());
    }

    @Test void approvedCorrectionPreservesShipmentScheduleAndShowsPublicHistory() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var before = jdbc.queryForMap("SELECT shipped_at,auto_confirm_at FROM shipments WHERE order_id=?", order);
        int outbox = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type='SHIPMENT'", Integer.class);
        var request = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(request.body()).path("data").path("id").asLong();
        var pending = admin.get("/admin/shipment-corrections");
        assertEquals(200, pending.statusCode(), pending.body());
        var decision = admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", true, "remark", "内部核查"));
        assertEquals(200, decision.statusCode(), decision.body());
        assertEquals(200, admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", true)).statusCode());
        assertEquals(409, admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", false)).statusCode());
        assertEquals(before, jdbc.queryForMap("SELECT shipped_at,auto_confirm_at FROM shipments WHERE order_id=?", order));
        assertEquals(outbox, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type='SHIPMENT'", Integer.class));
        var historyResponse = fixture.client.get("/orders/" + order + "/shipment/corrections");
        assertEquals(200, historyResponse.statusCode(), historyResponse.body());
        var history = json.readTree(historyResponse.body()).path("data");
        assertEquals(1, history.path("currentVersion").asInt());
        assertEquals("中通快递", history.path("items").get(0).path("newCarrierName").asString());
        assertFalse(history.path("items").get(0).has("remark"));
    }

    @Test void rejectsStalePendingAfterReceiptAndKeepsShipmentUnchanged() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var submitted = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(submitted.body()).path("data").path("id").asLong();
        assertEquals(200, fixture.client.post("/orders/" + order + "/shipment/confirm", Map.of()).statusCode());
        assertEquals(409, admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", true)).statusCode());
        assertEquals(200, admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", false)).statusCode());
        assertEquals("顺丰速运", jdbc.queryForObject("SELECT carrier_name FROM shipments WHERE order_id=?", String.class, order));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM shipment_corrections WHERE id=?", String.class, id));
    }

    @Test void transferAllowsAdminToRejectStaleRequestAndNewOwnerToSubmit() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var submitted = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(submitted.body()).path("data").path("id").asLong();
        var newOwner = accounts.create("MERCHANT", "Shop123!");
        assertEquals(200, admin.request("PUT", "/admin/shops/" + fixture.catalog.shop + "/owner",
                Map.of("ownerUserId", newOwner.id())).statusCode());
        assertEquals(409, admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", true)).statusCode());
        assertEquals(200, admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", false)).statusCode());
        try (var newMerchant = CommerceFixture.login("http://127.0.0.1:" + port, newOwner)) {
            assertEquals(200, newMerchant.post(shipment + "/corrections", Map.of(
                    "originalVersion", 0, "carrierName", "圆通速递", "trackingNo", "YT100001", "reason", "重新申请")).statusCode());
        }
    }

    record RaceResult(HttpResponse<String> first, HttpResponse<String> second) {}

    RaceResult raceOnRow(String table, long rowId, Callable<HttpResponse<String>> first,
                         Callable<HttpResponse<String>> second) throws Exception {
        try (var lock = DriverManager.getConnection(url, dbUser, dbPassword);
             var observer = DriverManager.getConnection(url, dbUser, dbPassword);
             var pool = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
                statement.setLong(1, rowId);
                statement.executeQuery().close();
            }
            long connectionId;
            try (var statement = lock.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next(); connectionId = rows.getLong(1);
            }
            try {
                var a = pool.submit(first);
                var b = pool.submit(second);
                String query = """
                        SELECT COUNT(DISTINCT w.REQUESTING_THREAD_ID)
                        FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks d ON d.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.threads t ON t.THREAD_ID=w.BLOCKING_THREAD_ID
                        WHERE t.PROCESSLIST_ID=? AND d.OBJECT_SCHEMA=DATABASE() AND d.OBJECT_NAME=?
                          AND d.INDEX_NAME='PRIMARY' AND d.LOCK_DATA=?
                        """;
                int waiting = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                try (var statement = observer.prepareStatement(query)) {
                    statement.setLong(1, connectionId);
                    statement.setString(2, table);
                    statement.setString(3, Long.toString(rowId));
                    while (waiting < 2 && System.nanoTime() < deadline) {
                        try (var rows = statement.executeQuery()) { rows.next(); waiting = rows.getInt(1); }
                        Thread.yield();
                    }
                }
                assertEquals(2, waiting, "both requests must wait on the held row");
                lock.commit();
                return new RaceResult(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            } finally {
                lock.rollback();
            }
        }
    }

    @Test void simultaneousApprovalsApplyOnlyOnce() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var request = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(request.body()).path("data").path("id").asLong();
        String path = "/admin/shipment-corrections/" + id + "/decision";
        var result = raceOnRow("shops", fixture.catalog.shop,
                () -> admin.post(path, Map.of("approved", true)),
                () -> admin.post(path, Map.of("approved", true)));
        assertEquals(200, result.first().statusCode(), result.first().body());
        assertEquals(200, result.second().statusCode(), result.second().body());
        assertEquals(1, jdbc.queryForObject("SELECT correction_version FROM shipments WHERE order_id=?", Integer.class, order));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM shipment_corrections WHERE id=?", String.class, id));
    }

    @Test void approvalRacingReceiptNeverChangesConfirmedShipmentAfterConfirmation() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var request = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(request.body()).path("data").path("id").asLong();
        var result = raceOnRow("orders", order,
                () -> admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", true)),
                () -> fixture.client.post("/orders/" + order + "/shipment/confirm", Map.of()));
        assertEquals(200, result.second().statusCode(), result.second().body());
        assertTrue(result.first().statusCode() == 200 || result.first().statusCode() == 409, result.first().body());
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM shipments WHERE order_id=?", String.class, order));
        int version = jdbc.queryForObject("SELECT correction_version FROM shipments WHERE order_id=?", Integer.class, order);
        assertEquals(result.first().statusCode() == 200 ? 1 : 0, version);
    }

    @Test void approvalRacingTransferRequiresOwnerAtLockAcquisition() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var request = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(request.body()).path("data").path("id").asLong();
        var newOwner = accounts.create("MERCHANT", "Shop123!");
        var result = raceOnRow("shops", fixture.catalog.shop,
                () -> admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", true)),
                () -> admin.request("PUT", "/admin/shops/" + fixture.catalog.shop + "/owner", Map.of("ownerUserId", newOwner.id())));
        assertEquals(200, result.second().statusCode(), result.second().body());
        assertTrue(result.first().statusCode() == 200 || result.first().statusCode() == 409, result.first().body());
        int version = jdbc.queryForObject("SELECT correction_version FROM shipments WHERE order_id=?", Integer.class, order);
        assertEquals(result.first().statusCode() == 200 ? 1 : 0, version);
        if (version == 0) assertEquals(200, admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", false)).statusCode());
    }

    @Test void shopCoordinationLockRequiresAnExistingTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> shopService.lockById(fixture.catalog.shop));
    }

    @Test void concurrentDifferentRequestsLeaveOnePendingApplication() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        String path = shipment + "/corrections";
        var result = raceOnRow("shops", fixture.catalog.shop,
                () -> fixture.merchantClient.post(path, Map.of("originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误")),
                () -> fixture.merchantClient.post(path, Map.of("originalVersion", 0, "carrierName", "圆通速递", "trackingNo", "YT100001", "reason", "录入错误")));
        assertTrue((result.first().statusCode() == 200 && result.second().statusCode() == 409)
                || (result.first().statusCode() == 409 && result.second().statusCode() == 200));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM shipment_corrections WHERE order_id=? AND status='PENDING'", Integer.class, order));
    }

    @Test void rejectsUnchangedOrStaleRequestAndHidesHistoryFromOthers() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        assertEquals(409, fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "顺丰速运", "trackingNo", "SF100001", "reason", "不变")).statusCode());
        assertEquals(409, fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 1, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "错误版本")).statusCode());
        var stranger = accounts.create("USER", "Shop123!");
        try (var client = CommerceFixture.login("http://127.0.0.1:" + port, stranger)) {
            assertEquals(404, client.get("/orders/" + order + "/shipment/corrections").statusCode());
            assertEquals(403, client.get("/admin/shipment-corrections").statusCode());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM shipment_corrections WHERE order_id=?", Integer.class, order));
    }

    @Test void duplicateSubmissionAndRejectedAdminPagesKeepTheirContracts() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        long[] ids = new long[3];
        for (int n = 0; n < 3; n++) {
            var body = Map.<String, Object>of("originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT10000" + n, "reason", "录入错误");
            var created = fixture.merchantClient.post(shipment + "/corrections", body);
            assertEquals(200, created.statusCode(), created.body());
            ids[n] = json.readTree(created.body()).path("data").path("id").asLong();
            var repeated = fixture.merchantClient.post(shipment + "/corrections", body);
            assertEquals(ids[n], json.readTree(repeated.body()).path("data").path("id").asLong());
            assertEquals(200, admin.post("/admin/shipment-corrections/" + ids[n] + "/decision", Map.of("approved", false)).statusCode());
        }
        var first = json.readTree(admin.get("/admin/shipment-corrections?status=REJECTED&size=2").body()).path("data");
        assertTrue(first.path("hasMore").asBoolean());
        assertEquals(ids[2], first.path("items").get(0).path("id").asLong());
        assertEquals(ids[1], first.path("nextCursor").asLong());
        var last = json.readTree(admin.get("/admin/shipment-corrections?status=REJECTED&size=2&afterId=" + ids[1]).body()).path("data");
        assertEquals(ids[0], last.path("items").get(0).path("id").asLong());
        assertFalse(last.path("hasMore").asBoolean());
        var history = json.readTree(fixture.client.get("/orders/" + order + "/shipment/corrections").body()).path("data");
        assertEquals(0, history.path("currentVersion").asInt());
        assertEquals(0, history.path("items").size());
    }

    @Test void merchantCanReadPendingThenRejectedApplicationWithoutInternalRemark() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var submitted = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(submitted.body()).path("data").path("id").asLong();
        String path = shipment + "/corrections/" + id;
        var pending = fixture.merchantClient.get(path);
        assertEquals(200, pending.statusCode(), pending.body());
        assertEquals("PENDING", json.readTree(pending.body()).path("data").path("status").asString());
        assertEquals(200, admin.post("/admin/shipment-corrections/" + id + "/decision",
                Map.of("approved", false, "remark", "内部核查")).statusCode());
        var rejected = fixture.merchantClient.get(path);
        assertEquals(200, rejected.statusCode(), rejected.body());
        var data = json.readTree(rejected.body()).path("data");
        assertEquals(id, data.path("id").asLong());
        assertEquals("REJECTED", data.path("status").asString());
        assertFalse(data.has("remark"));
    }

    @Test void merchantCanReadApprovedApplication() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var submitted = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(submitted.body()).path("data").path("id").asLong();
        assertEquals(200, admin.post("/admin/shipment-corrections/" + id + "/decision", Map.of("approved", true)).statusCode());
        var response = fixture.merchantClient.get(shipment + "/corrections/" + id);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("APPROVED", json.readTree(response.body()).path("data").path("status").asString());
    }

    @Test void applicationStatusRejectsWrongOrderShopAndFormerOwner() throws Exception {
        long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
        String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
        assertEquals(200, fixture.merchantClient.post(shipment,
                Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001")).statusCode());
        var submitted = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
        long id = json.readTree(submitted.body()).path("data").path("id").asLong();
        assertEquals(404, fixture.merchantClient.get("/merchant/shops/" + fixture.catalog.shop
                + "/orders/" + Long.MAX_VALUE + "/shipment/corrections/" + id).statusCode());
        try (var other = new com.shiwushop.support.CatalogFixture(jdbc)) {
            assertEquals(404, fixture.merchantClient.get("/merchant/shops/" + other.shop
                    + "/orders/" + order + "/shipment/corrections/" + id).statusCode());
        }
        var newOwner = accounts.create("MERCHANT", "Shop123!");
        assertEquals(200, admin.request("PUT", "/admin/shops/" + fixture.catalog.shop + "/owner",
                Map.of("ownerUserId", newOwner.id())).statusCode());
        assertEquals(404, fixture.merchantClient.get(shipment + "/corrections/" + id).statusCode());
        try (var newMerchant = CommerceFixture.login("http://127.0.0.1:" + port, newOwner)) {
            assertEquals(200, newMerchant.get(shipment + "/corrections/" + id).statusCode());
        }
    }
}
