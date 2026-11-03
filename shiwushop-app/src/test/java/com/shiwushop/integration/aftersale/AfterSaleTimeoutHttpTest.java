package com.shiwushop.integration.aftersale;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.aftersale.service.AfterSaleTimeoutService;
import com.shiwushop.common.BusinessException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.shiwushop.support.PaymentHttpFixture;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AfterSaleTimeoutHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SdkAlipayGateway gateway;
    @Autowired AfterSaleTimeoutService service;
    @Autowired PlatformTransactionManager transactions;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void adminCanListTimeoutTasks() throws Exception {
        try (var accounts = new TestAccounts(jdbc)) {
            var admin = accounts.create("ADMIN", "Shop123!");
            try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
                var result = client.get("/admin/after-sale-timeouts");
                assertEquals(200, result.statusCode(), result.body());
            }
        }
    }
    @Test
    void adminClaimsAndClosesWithEvidenceAndConclusion() throws Exception {
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try (var accounts = new TestAccounts(jdbc)) {
            var admin = accounts.create("ADMIN", "Shop123!");
            var otherAdmin = accounts.create("ADMIN", "Shop123!");
            var buyer = accounts.create("USER", "Shop123!");
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','REQUESTED','测试',1,NOW())", order);
            Long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class, order);
            jdbc.update("INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at,opened_at) SELECT id,'REVIEW',requested_at,NOW(),1,'OPEN',NOW(),NOW() FROM after_sale_cases WHERE id=?", sale);
            Long task = jdbc.queryForObject("SELECT id FROM after_sale_timeout_tasks WHERE case_id=?", Long.class, sale);
            try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin);
                 var other = CommerceFixture.login("http://127.0.0.1:" + port, otherAdmin);
                 var ordinary = CommerceFixture.login("http://127.0.0.1:" + port, buyer)) {
                String path = "/admin/after-sale-timeouts/" + task;
                assertEquals(403, ordinary.get(path).statusCode());
                assertEquals(200, client.get(path).statusCode());
                assertEquals(403, client.request("POST", path + "/claim", Map.of(), false, Map.of()).statusCode());
                assertEquals(200, client.post(path + "/claim", Map.of()).statusCode());
                assertEquals(200, client.post(path + "/claim", Map.of()).statusCode());
                assertEquals(409, other.post(path + "/claim", Map.of()).statusCode());
                assertEquals(400, client.post(path + "/close", Map.of("evidence", "工单记录", "conclusion", " ")).statusCode());
                assertEquals(409, other.post(path + "/close", Map.of("evidence", "工单记录", "conclusion", "已核实")).statusCode());
                assertEquals(200, client.post(path + "/close", Map.of("evidence", "工单记录", "conclusion", "已核实")).statusCode());
                assertEquals(200, client.post(path + "/close", Map.of("evidence", "工单记录", "conclusion", "已核实")).statusCode());
                assertEquals(409, client.post(path + "/close", Map.of("evidence", "工单记录", "conclusion", "新结论")).statusCode());
                assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?", String.class, sale));
            } finally {
                jdbc.update("DELETE FROM after_sale_timeout_tasks WHERE id=?", task);
            }
        } finally {
            fixture.close();
        }
    }
    @Test
    void concurrentAdminsHaveExactlyOneClaimant() throws Exception {
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try (var accounts = new TestAccounts(jdbc)) {
            var first = accounts.create("ADMIN", "Shop123!");
            var second = accounts.create("ADMIN", "Shop123!");
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','REQUESTED','测试',1,NOW())", order);
            Long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class, order);
            jdbc.update("INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at,opened_at) SELECT id,'REVIEW',requested_at,NOW(),1,'OPEN',NOW(),NOW() FROM after_sale_cases WHERE id=?", sale);
            Long task = jdbc.queryForObject("SELECT id FROM after_sale_timeout_tasks WHERE case_id=?", Long.class, sale);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var barrier = new CyclicBarrier(2);
                var one = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return service.claim(first.id(), task); });
                var two = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return service.claim(second.id(), task); });
                int success = 0;
                int conflicts = 0;
                for (var result : java.util.List.of(one, two)) {
                    try {
                        result.get(10, TimeUnit.SECONDS);
                        success++;
                    } catch (ExecutionException e) {
                        assertInstanceOf(BusinessException.class, e.getCause());
                        assertEquals(409, ((BusinessException) e.getCause()).getCode());
                        conflicts++;
                    }
                }
                assertEquals(1, success);
                assertEquals(1, conflicts);
            } finally {
                jdbc.update("DELETE FROM after_sale_timeout_tasks WHERE id=?", task);
            }
        } finally {
            fixture.close();
        }
    }
    @Test
    void scheduledAndNeverOpenedStaleTasksAreHidden() throws Exception {
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try (var accounts = new TestAccounts(jdbc)) {
            var admin = accounts.create("ADMIN", "Shop123!");
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at) VALUES (?,'REFUND_ONLY','REQUESTED','测试',1,NOW())", order);
            Long sale = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class, order);
            new TransactionTemplate(transactions).executeWithoutResult(status -> service.onStageChanged(sale));
            Long task = jdbc.queryForObject("SELECT id FROM after_sale_timeout_tasks WHERE case_id=?", Long.class, sale);
            try (var client = CommerceFixture.login("http://127.0.0.1:" + port, admin)) {
                String path = "/admin/after-sale-timeouts/" + task;
                assertEquals(404, client.get(path).statusCode());
                assertEquals(409, client.post(path + "/claim", Map.of()).statusCode());
                var list = json.readTree(client.get("/admin/after-sale-timeouts").body()).path("data").path("items");
                for (var item : list) assertFalse(item.path("id").asLong() == task);
                jdbc.update("UPDATE after_sale_cases SET status='CLOSED' WHERE id=?", sale);
                new TransactionTemplate(transactions).executeWithoutResult(status -> service.onStageChanged(sale));
                assertEquals("STALE", jdbc.queryForObject("SELECT status FROM after_sale_timeout_tasks WHERE id=?", String.class, task));
                assertEquals(404, client.get(path).statusCode());
                list = json.readTree(client.get("/admin/after-sale-timeouts").body()).path("data").path("items");
                for (var item : list) assertFalse(item.path("id").asLong() == task);
            }
        } finally {
            fixture.close();
        }
    }
}
