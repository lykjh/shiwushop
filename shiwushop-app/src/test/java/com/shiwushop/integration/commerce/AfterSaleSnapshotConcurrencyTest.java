package com.shiwushop.integration.commerce;

import com.shiwushop.aftersale.dto.AfterSaleReviewDTO;
import com.shiwushop.aftersale.service.AfterSaleService;
import com.shiwushop.merchant.dto.shop.ShopReturnProfileSaveDTO;
import com.shiwushop.merchant.service.MerchantShopAccess;
import com.shiwushop.merchant.service.ShopReturnProfileService;
import com.shiwushop.merchant.service.ShopService;
import com.shiwushop.common.BusinessException;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.payment.service.impl.SdkAlipayGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "spring.datasource.hikari.maximum-pool-size=2",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AfterSaleSnapshotConcurrencyTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AfterSaleService sales;
    @Autowired ShopReturnProfileService profiles;
    @Autowired ShopService shops;
    @MockitoBean SdkAlipayGateway gateway;
    @MockitoSpyBean MerchantShopAccess access;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void approvalAndProfileUpdateSerializeOnShopLock() throws Exception {
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        long sale = 0;
        try {
            long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
            Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
            String merchant = "/merchant/shops/" + fixture.catalog.shop;
            assertEquals(200, fixture.merchantClient.post(merchant + "/orders/" + order + "/shipment",
                    Map.of("carrierName", "顺丰", "trackingNo", "SF10005")).statusCode());
            profiles.save(fixture.catalog.shop, fixture.catalog.merchant.id(), profile("张明"));
            var body = Map.of("type", "RETURN_REFUND", "reason", "尺寸不合适",
                    "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
            var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                    Map.of("Idempotency-Key", "snapshot-race"));
            assertEquals(200, created.statusCode(), created.body());
            sale = json.readTree(created.body()).path("data").path("id").asLong();
            long caseId = sale;
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var updateStarted = new CountDownLatch(1);
            doAnswer(call -> {
                var shop = call.callRealMethod();
                if (Thread.currentThread().getName().equals("review-writer")) {
                    locked.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
                return shop;
            }).when(access).requireOwnedForUpdate(eq(fixture.catalog.merchant.id()), eq(fixture.catalog.shop), anyString());
            try (var pool = Executors.newFixedThreadPool(2)) {
                var review = pool.submit(() -> {
                    Thread.currentThread().setName("review-writer");
                    return sales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, caseId, new AfterSaleReviewDTO(true, null));
                });
                try {
                    assertTrue(locked.await(10, TimeUnit.SECONDS));
                    var update = pool.submit(() -> {
                        updateStarted.countDown();
                        return profiles.save(fixture.catalog.shop, fixture.catalog.merchant.id(), profile("李华"));
                    });
                    assertTrue(updateStarted.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> update.get(300, TimeUnit.MILLISECONDS));
                    release.countDown();
                    assertEquals("张明", review.get(10, TimeUnit.SECONDS).returnDestination().consignee());
                    assertEquals("李华", update.get(10, TimeUnit.SECONDS).consignee());
                    assertEquals("张明", sales.buyerDetail(fixture.buyer.id(), caseId).returnDestination().consignee());
                } finally {
                    release.countDown();
                }
            }
        } finally {
            if (sale != 0) jdbc.update("DELETE FROM after_sale_return_destinations WHERE case_id=?", sale);
            jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", fixture.catalog.shop);
            fixture.close();
        }
    }

    @Test
    void transferBeforeApprovalShopLockRejectsOldOwner() throws Exception {
        PaymentHttpFixture.configure(gateway);
        var fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        try (var accounts = new TestAccounts(jdbc)) {
            var next = accounts.create("MERCHANT", "Shop123!");
            try {
                long order = PaymentHttpFixture.paidOrder(fixture, jdbc);
                Long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
                String merchant = "/merchant/shops/" + fixture.catalog.shop;
                assertEquals(200, fixture.merchantClient.post(merchant + "/orders/" + order + "/shipment",
                        Map.of("carrierName", "顺丰", "trackingNo", "SF10007")).statusCode());
                profiles.save(fixture.catalog.shop, fixture.catalog.merchant.id(), profile("张明"));
                var body = Map.of("type", "RETURN_REFUND", "reason", "尺寸不合适",
                        "items", List.of(Map.of("orderItemId", item, "quantity", 1)));
                var created = fixture.client.request("POST", "/orders/" + order + "/after-sales", body, true,
                        Map.of("Idempotency-Key", "transfer-review-race"));
                assertEquals(200, created.statusCode(), created.body());
                long sale = json.readTree(created.body()).path("data").path("id").asLong();
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                doAnswer(call -> {
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return call.callRealMethod();
                }).when(access).requireOwnedForUpdate(eq(fixture.catalog.merchant.id()), eq(fixture.catalog.shop), anyString());
                try (var pool = Executors.newSingleThreadExecutor()) {
                    var pending = pool.submit(() -> sales.review(fixture.catalog.merchant.id(), fixture.catalog.shop, sale, new AfterSaleReviewDTO(true, null)));
                    try {
                        assertTrue(entered.await(10, TimeUnit.SECONDS));
                        shops.changeOwner(fixture.catalog.shop, next.id());
                    } finally {
                        release.countDown();
                    }
                    var failure = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
                    assertInstanceOf(BusinessException.class, failure.getCause());
                    assertEquals(404, ((BusinessException) failure.getCause()).getCode());
                    assertEquals("REQUESTED", jdbc.queryForObject("SELECT status FROM after_sale_cases WHERE id=?", String.class, sale));
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_return_destinations WHERE case_id=?", Integer.class, sale));
                } finally {
                    release.countDown();
                }
            } finally {
                jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?", fixture.catalog.merchant.id(), fixture.catalog.shop);
            }
        } finally {
            jdbc.update("DELETE FROM shop_return_profiles WHERE shop_id=?", fixture.catalog.shop);
            fixture.close();
        }
    }

    private ShopReturnProfileSaveDTO profile(String consignee) {
        var dto = new ShopReturnProfileSaveDTO();
        dto.setContactName("王明");
        dto.setContactPhone("13912345678");
        dto.setConsignee(consignee);
        dto.setPhone("13812345678");
        dto.setProvince("江苏省");
        dto.setCity("南京市");
        dto.setDistrict("鼓楼区");
        dto.setDetail("中山北路28号");
        return dto;
    }
}
