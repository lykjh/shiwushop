package com.shiwushop.system.shipment;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.SystemEnvironment;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShipmentCorrectionSystemIT {
    @Test void approvalChangesTrackingButPreservesDeadlineAndPublishesHistory() throws Exception {
        String base = SystemEnvironment.base();
        var jdbc = SystemEnvironment.database();
        var json = new ObjectMapper();
        try (var accounts = new TestAccounts(jdbc);
             var fixture = new CommerceFixture(jdbc, base)) {
            var administrator = accounts.create("ADMIN", "Admin123!");
            try (var admin = CommerceFixture.login(base, administrator)) {
                try {
                    long order = fixture.order("shipment-correction-system");
                    assertEquals(1, jdbc.update("UPDATE orders SET status='PAID', paid_at=NOW() WHERE id=? AND status='UNPAID'", order));
                    String shipment = "/merchant/shops/" + fixture.catalog.shop + "/orders/" + order + "/shipment";
                    var shipped = fixture.merchantClient.post(shipment,
                            Map.of("carrierName", "顺丰速运", "trackingNo", "SF100001"));
                    assertEquals(200, shipped.statusCode(), shipped.body());
                    var before = jdbc.queryForMap("SELECT shipped_at,auto_confirm_at FROM shipments WHERE order_id=?", order);
                    var invalid = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                            "originalVersion", 1, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
                    assertEquals(409, invalid.statusCode(), invalid.body());
                    var submitted = fixture.merchantClient.post(shipment + "/corrections", Map.of(
                            "originalVersion", 0, "carrierName", "中通快递", "trackingNo", "ZT100001", "reason", "录入错误"));
                    assertEquals(200, submitted.statusCode(), submitted.body());
                    long id = json.readTree(submitted.body()).path("data").path("id").asLong();
                    var approved = admin.post("/admin/shipment-corrections/" + id + "/decision",
                            Map.of("approved", true, "remark", "核查通过"));
                    assertEquals(200, approved.statusCode(), approved.body());
                    var status = fixture.merchantClient.get(shipment + "/corrections/" + id);
                    assertEquals(200, status.statusCode(), status.body());
                    assertEquals("APPROVED", json.readTree(status.body()).path("data").path("status").asString());
                    var historyResponse = fixture.client.get("/orders/" + order + "/shipment/corrections");
                    assertEquals(200, historyResponse.statusCode(), historyResponse.body());
                    var history = json.readTree(historyResponse.body()).path("data");
                    assertEquals(1, history.path("currentVersion").asInt());
                    assertEquals("中通快递", history.path("items").get(0).path("newCarrierName").asString());
                    assertFalse(history.path("items").get(0).has("remark"));
                    assertEquals(before, jdbc.queryForMap("SELECT shipped_at,auto_confirm_at FROM shipments WHERE order_id=?", order));
                } finally {
                    jdbc.update("DELETE FROM shipment_corrections WHERE order_id IN (SELECT id FROM orders WHERE user_id=?)", fixture.buyer.id());
                }
            }
        }
    }
}
