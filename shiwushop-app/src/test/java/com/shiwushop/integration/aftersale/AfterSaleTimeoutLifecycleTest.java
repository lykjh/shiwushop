package com.shiwushop.integration.aftersale;

import com.shiwushop.common.BusinessException;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AfterSaleTimeoutLifecycleTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SdkAlipayGateway gateway;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void transitionsReplaceDeadlineAndRefundAttemptStopsReminder() throws Exception {
        PaymentHttpFixture.configure(gateway);
        when(gateway.refund(anyString(), anyString(), anyString(), any())).thenThrow(new BusinessException(503, "渠道不可用"));
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            String merchant = "/merchant/shops/" + fixture.catalog.shop;
            assertEquals(200, fixture.merchantClient.post(merchant + "/orders/" + order + "/shipment",
                    Map.of("carrierName", "顺丰", "trackingNo", "SF10008")).statusCode());
            var profile = Map.of("contactName", "王明", "contactPhone", "13912345678", "consignee", "张明",
                    "phone", "13812345678", "province", "江苏省", "city", "南京市", "district", "鼓楼区", "detail", "中山北路28号");
            assertEquals(200, fixture.merchantClient.request("PUT", merchant + "/return-profile", profile).statusCode());
            var body = Map.of("type", "RETURN_REFUND", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "timeout-lifecycle"));
            assertEquals(200, created.statusCode(), created.body());
            long sale = json.readTree(created.body()).path("data").path("id").asLong();
            String path = "/after-sales/" + sale;
            assertStage(sale, "REVIEW", "SCHEDULED");
            assertEquals(200, fixture.merchantClient.post(merchant + path + "/review", Map.of("approved", true)).statusCode());
            assertStage(sale, "REVIEW", "STALE");
            assertStage(sale, "RETURN", "SCHEDULED");
            assertEquals(200, fixture.client.post(path + "/return-shipment", Map.of("carrierName", "圆通", "trackingNo", "YT10008")).statusCode());
            assertStage(sale, "RETURN", "STALE");
            assertStage(sale, "RECEIPT", "SCHEDULED");
            assertEquals(200, fixture.merchantClient.post(merchant + path + "/receive", Map.of("restockable", false)).statusCode());
            assertStage(sale, "RECEIPT", "STALE");
            assertStage(sale, "REFUND", "SCHEDULED");
            assertEquals(200, fixture.merchantClient.post(merchant + path + "/refund", Map.of()).statusCode());
            assertStage(sale, "REFUND", "STALE");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts WHERE case_id=? AND status='UNKNOWN'", Integer.class, sale));
        } finally {
            jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", fixture.catalog.shop);
            fixture.close();
        }
    }

    private void assertStage(long sale, String stage, String expected) {
        assertEquals(expected, jdbc.queryForObject("SELECT status FROM after_sale_timeout_tasks WHERE case_id=? AND stage=?", String.class, sale, stage));
    }
}
