package com.shiwushop.integration.component.after_sale;

import com.shiwushop.aftersale.dto.AfterSaleCreateDTO;
import com.shiwushop.aftersale.dto.AfterSaleReviewDTO;
import com.shiwushop.aftersale.service.AfterSaleService;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.autoconfigure.exclude=", "server.port=18127", "shop.member.enabled=false",
        "shop.marketing.enabled=false", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=28379", "spring.data.redis.database=12"})
class AfterSaleTimelineTest {
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.username",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")));
        properties.add("spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AfterSaleService afterSales;
    final ObjectMapper json = new ObjectMapper();
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
            jdbc.update("DELETE a FROM marketing_refund_allocations a "
                    + "JOIN after_sale_items i ON i.id=a.after_sale_item_id "
                    + "JOIN after_sale_cases c ON c.id=i.case_id "
                    + "JOIN orders o ON o.id=c.order_id WHERE o.user_id=?", fixture.buyer.id());
            fixture.close();
        }
    }

    @Test
    void buyerAndMerchantSeeStablePublicEventsButOutsiderCannotRead() throws Exception {
        var checkout = fixture.client.post("/checkouts/direct", fixture.direct("timeline-zero", 1, "19.90"));
        assertEquals(200, checkout.statusCode(), checkout.body());
        long order = json.readTree(checkout.body()).path("data").path("orders").get(0).path("id").asLong();
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        jdbc.update("UPDATE orders SET status='PAID', settlement_kind='ZERO_CASH', pricing_version=1, "
                + "total_amount=0, discount_amount=original_amount, pay_expires_at=NULL, paid_at=NOW() "
                + "WHERE id=?", order);
        jdbc.update("UPDATE order_items SET payable_amount=0, "
                + "discount_amount=original_unit_price*quantity WHERE id=?", item);
        long sale = afterSales.create(fixture.buyer.id(), order, "timeline-sale",
                new AfterSaleCreateDTO("REFUND_ONLY", "售后时间线测试",
                        List.of(new AfterSaleCreateDTO.Item(item, 1)))).id();
        afterSales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale,
                new AfterSaleReviewDTO(true, null));
        assertEquals("REFUNDED", afterSales.refund(fixture.catalog.merchant.id(), fixture.catalog.shop, sale).status());

        String buyerPath = "/after-sales/" + sale + "/timeline";
        var firstResponse = fixture.client.get(buyerPath + "?size=1");
        assertEquals(200, firstResponse.statusCode(), firstResponse.body());
        var first = json.readTree(firstResponse.body()).path("data");
        assertEquals("REFUNDED", first.path("currentStatus").asText());
        assertEquals(1, first.path("items").size());
        assertEquals("REFUND_SUCCEEDED", first.path("items").get(0).path("action").asText());
        assertFalse(first.path("items").get(0).has("actorUserId"));
        long before = first.path("nextBeforeId").asLong();
        var secondResponse = fixture.client.get(buyerPath + "?size=1&beforeId=" + before);
        assertEquals(200, secondResponse.statusCode(), secondResponse.body());
        var second = json.readTree(secondResponse.body()).path("data");
        assertEquals("AFTER_SALE_APPROVED", second.path("items").get(0).path("action").asText());
        assertTrue(second.path("items").get(0).path("id").asLong() < before);
        var merchantResponse = fixture.merchantClient.get("/merchant/shops/" + fixture.catalog.shop
                + "/after-sales/" + sale + "/timeline?size=10");
        assertEquals(200, merchantResponse.statusCode(), merchantResponse.body());
        assertEquals(3, json.readTree(merchantResponse.body()).path("data").path("items").size());
        assertEquals(404, fixture.merchantClient.get("/merchant/shops/" + (fixture.catalog.shop + 100000)
                + "/after-sales/" + sale + "/timeline").statusCode());
        var other = new TestAccounts(jdbc);
        var stranger = CommerceFixture.login("http://127.0.0.1:18127", other.create("USER", "Shop123!"));
        try {
            assertEquals(404, stranger.get(buyerPath).statusCode());
        } finally {
            stranger.close();
            other.close();
        }
    }
}
