package com.shiwushop.system.aftersale;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.SystemEnvironment;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独立应用进程验证调度器激活与管理员待办处理。 */
class AfterSaleTimeoutSystemIT {
    @Test
    void schedulerActivatesDueTaskAndAdminHandlesIt() throws Exception {
        String base = SystemEnvironment.base();
        var jdbc = SystemEnvironment.database();
        var json = new ObjectMapper();
        var fixture = new CommerceFixture(jdbc, base);
        long sale = 0;
        try (var accounts = new TestAccounts(jdbc)) {
            var admin = accounts.create("ADMIN", "Shop123!");
            try (var client = CommerceFixture.login(base, admin)) {
                long order = fixture.order("system-timeout");
                jdbc.update("UPDATE orders SET status='PAID',paid_at=NOW() WHERE id=?", order);
                jdbc.update("INSERT INTO payment_attempts(order_id,channel,out_trade_no,alipay_trade_no,app_id,seller_id,amount,status,paid_at) VALUES (?,'ALIPAY_SANDBOX',?,?, 'system-app','system-seller',19.90,'SUCCESS',NOW())", order, "system-pay-" + order, "system-trade-" + order);
                Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
                int stock = jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku);
                var body = Map.of("type", "REFUND_ONLY", "reason", "尺寸不合适", "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
                var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true, Map.of("Idempotency-Key", "system-timeout-sale"));
                assertEquals(200, created.statusCode(), created.body());
                sale = json.readTree(created.body()).path("data").path("id").asLong();
                // 以业务表实际存储的秒精度为依据，避免夹具的纳秒被两种 DATETIME 精度分别舍入。
                jdbc.update("UPDATE after_sale_cases SET requested_at=DATE_SUB(NOW(),INTERVAL 49 HOUR) WHERE id=?", sale);
                jdbc.update("UPDATE after_sale_timeout_tasks t JOIN after_sale_cases c ON c.id=t.case_id SET t.basis_at=c.requested_at,t.due_at=DATE_ADD(c.requested_at,INTERVAL 48 HOUR) WHERE c.id=? AND t.stage='REVIEW'", sale);
                Long taskId = jdbc.queryForObject("SELECT id FROM after_sale_timeout_tasks WHERE case_id=? AND stage='REVIEW'", Long.class, sale);
                String path = "/admin/after-sale-timeouts/" + taskId;
                long until = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
                boolean opened = false;
                while (System.nanoTime() < until) {
                    var detail = client.get(path);
                    if (detail.statusCode() == 200 && "OPEN".equals(json.readTree(detail.body()).path("data").path("status").asString())) {
                        opened = true;
                        break;
                    }
                    Thread.sleep(250);
                }
                assertTrue(opened, "独立进程调度器未在30秒内激活到期待办");
                assertEquals(200, client.post(path + "/claim", Map.of()).statusCode());
                var closed = client.post(path + "/close", Map.of("evidence", "售后工单记录", "conclusion", "已通知商家处理"));
                assertEquals(200, closed.statusCode(), closed.body());
                assertEquals("CLOSED", json.readTree(closed.body()).path("data").path("status").asString());
                assertEquals("REQUESTED", json.readTree(fixture.client.get("/after-sales/" + sale).body()).path("data").path("status").asString());
                assertEquals(stock, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.sku));
            } finally {
                if (sale != 0) jdbc.update("DELETE FROM after_sale_timeout_tasks WHERE case_id=?", sale);
            }
        } finally {
            fixture.close();
        }
    }
}
