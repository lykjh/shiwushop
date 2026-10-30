package com.shiwushop.after_sale;

import com.shiwushop.commerce.dto.after_sale.AfterSaleCreateDTO;
import com.shiwushop.commerce.dto.after_sale.AfterSaleReviewDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnReceiptDTO;
import com.shiwushop.commerce.dto.after_sale.ReturnShipmentDTO;
import com.shiwushop.commerce.vo.after_sale.AfterSaleVO;
import com.shiwushop.common.BusinessException;
import com.shiwushop.commerce.payment.RefundGateway;
import com.shiwushop.commerce.payment.SdkAlipayGateway;
import com.shiwushop.commerce.mapper.payment.RefundAttemptMapper;
import com.shiwushop.commerce.service.AfterSaleService;
import com.shiwushop.catalog.service.ReviewService;
import com.shiwushop.catalog.service.PublicReviewService;
import com.shiwushop.catalog.dto.review.ReviewCreateDTO;
import com.shiwushop.commerce.service.ShipmentService;
import com.shiwushop.commerce.dto.shipment.ShipmentCreateDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@Transactional
class AfterSaleFlowTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefundAttemptMapper refundAttempts;
    @Autowired private AfterSaleService service;
    @Autowired private ShipmentService shipments;
    @Autowired private ReviewService reviews;
    @Autowired private PublicReviewService publicReviews;
    @MockitoBean private SdkAlipayGateway gateway;

    @Test
    void completedRefundExcludesReviewOnlyAfterFullQuantityAndOnlyOnce() {
        Fixture fixture = fixture();
        Long productId = jdbc.queryForObject("SELECT product_id FROM skus WHERE id=?", Long.class, fixture.skuId());
        shipments.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(),
                new ShipmentCreateDTO("顺丰", "C03-REFUND"));
        shipments.confirmByBuyer(fixture.buyer(), fixture.orderId());
        reviews.create(fixture.buyer(), fixture.orderId(), fixture.itemId(),
                new ReviewCreateDTO(5, "退款前的真实评价", List.of()));
        assertEquals(1, publicReviews.summary(productId).reviewCount());

        when(gateway.configured()).thenReturn(true);
        when(gateway.refund(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), anyString(),
                eq(new BigDecimal("10.00")))).thenAnswer(invocation -> new RefundGateway.Result(true,
                fixture.outTradeNo(), fixture.tradeNo(), null, new BigDecimal("10.00")));
        for (int n = 0; n < 2; n++) {
            AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(),
                    new AfterSaleCreateDTO("REFUND_ONLY", "部分退款", List.of(
                            new AfterSaleCreateDTO.Item(fixture.itemId(), 1))));
            service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
            assertEquals("REFUNDED", service.refund(fixture.merchant(), fixture.shopId(), sale.id()).status());
            assertEquals(n == 0 ? 1 : 0, publicReviews.summary(productId).reviewCount());
            assertEquals("REFUNDED", service.refund(fixture.merchant(), fixture.shopId(), sale.id()).status());
            assertEquals(n == 0 ? 1 : 0, publicReviews.summary(productId).reviewCount());
        }
        assertEquals(0, jdbc.queryForObject("SELECT rating_sum FROM product_review_summaries WHERE product_id=?",
                Integer.class, productId));
    }

    @Test
    void partialRefundReservesQuantityAndReturnsUnshippedStockOnce() {
        Fixture fixture = fixture();
        String key = UUID.randomUUID().toString();
        AfterSaleCreateDTO request = new AfterSaleCreateDTO("REFUND_ONLY", "不需要了",
                List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 1)));
        AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), key, request);
        assertEquals(sale.id(), service.create(fixture.buyer(), fixture.orderId(), key, request).id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM after_sale_cases WHERE order_id=?",
                Integer.class, fixture.orderId()));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.create(fixture.buyer(),
                fixture.orderId(), key, new AfterSaleCreateDTO("REFUND_ONLY", "变更理由",
                        List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 1))))).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.create(fixture.buyer(),
                fixture.orderId(), UUID.randomUUID().toString(), new AfterSaleCreateDTO("REFUND_ONLY", "再退两件",
                        List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 2))))).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.buyerDetail(fixture.merchant(), sale.id())).getCode());

        service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
        when(gateway.configured()).thenReturn(true);
        when(gateway.refund(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), anyString(),
                eq(new BigDecimal("10.00")))).thenReturn(new RefundGateway.Result(true,
                fixture.outTradeNo(), fixture.tradeNo(), null, new BigDecimal("10.00")));
        assertEquals("REFUNDED", service.refund(fixture.merchant(), fixture.shopId(), sale.id()).status());
        assertEquals("REFUNDED", service.refund(fixture.merchant(), fixture.shopId(), sale.id()).status());
        assertEquals(2, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.skuId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE reason='REFUND_RETURN' " +
                "AND order_item_id=?", Integer.class, fixture.itemId()));
        assertEquals(1, searchEvents(fixture));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts WHERE case_id=?",
                Integer.class, sale.id()));
    }

    @Test
    void returnRequiresShipmentAndReceiptBeforeRefund() {
        Fixture fixture = fixture();
        AfterSaleCreateDTO request = new AfterSaleCreateDTO("RETURN_REFUND", "不合适",
                List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 1)));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.create(fixture.buyer(),
                fixture.orderId(), UUID.randomUUID().toString(), request)).getCode());
        shipments.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(),
                new ShipmentCreateDTO("顺丰", "SF-OUT"));
        AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(), request);
        service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.refund(fixture.merchant(), fixture.shopId(), sale.id())).getCode());
        assertEquals("WAITING_RECEIPT", service.sendReturn(fixture.buyer(), sale.id(),
                new ReturnShipmentDTO("圆通", "YT-123")).status());
        assertEquals("WAITING_RECEIPT", service.sendReturn(fixture.buyer(), sale.id(),
                new ReturnShipmentDTO("圆通", "YT-123")).status());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.sendReturn(fixture.buyer(), sale.id(), new ReturnShipmentDTO("圆通", "OTHER"))).getCode());
        assertEquals("REFUNDING", service.receiveReturn(fixture.merchant(), fixture.shopId(), sale.id(),
                new ReturnReceiptDTO(true)).status());
        assertEquals(2, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.skuId()));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.receiveReturn(
                fixture.merchant(), fixture.shopId(), sale.id(), new ReturnReceiptDTO(true))).getCode());
        assertEquals(1, searchEvents(fixture));
    }

    @Test
    void unsellableReturnDoesNotRefreshStockIndex() {
        Fixture fixture = fixture();
        shipments.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(),
                new ShipmentCreateDTO("顺丰", "SF-OUT"));
        AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(),
                new AfterSaleCreateDTO("RETURN_REFUND", "商品损坏",
                        List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 1))));
        service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
        service.sendReturn(fixture.buyer(), sale.id(), new ReturnShipmentDTO("圆通", "YT-123"));

        assertEquals("REFUNDING", service.receiveReturn(fixture.merchant(), fixture.shopId(), sale.id(),
                new ReturnReceiptDTO(false)).status());
        assertEquals(1, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.skuId()));
        assertEquals(0, searchEvents(fixture));
        when(gateway.configured()).thenReturn(true);
        when(gateway.refund(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), anyString(),
                eq(new BigDecimal("10.00")))).thenReturn(new RefundGateway.Result(true,
                fixture.outTradeNo(), fixture.tradeNo(), null, new BigDecimal("10.00")));
        assertEquals("REFUNDED", service.refund(fixture.merchant(), fixture.shopId(), sale.id()).status());
        assertEquals(1, searchEvents(fixture));
        assertEquals(1, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.skuId()));
    }

    @Test
    void unknownRefundKeepsOriginalRequestNumber() {
        Fixture fixture = fixture();
        AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(),
                new AfterSaleCreateDTO("REFUND_ONLY", "不需要了",
                        List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 1))));
        service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
        when(gateway.configured()).thenReturn(true);
        when(gateway.refund(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), anyString(),
                eq(new BigDecimal("10.00")))).thenReturn(new RefundGateway.Result(false,
                fixture.outTradeNo(), fixture.tradeNo(), null, null));
        AfterSaleVO first = service.refund(fixture.merchant(), fixture.shopId(), sale.id());
        assertEquals("UNKNOWN", first.refund().status());
        AfterSaleVO second = service.refund(fixture.merchant(), fixture.shopId(), sale.id());
        assertEquals(first.refund().outRequestNo(), second.refund().outRequestNo());
        assertEquals(0, searchEvents(fixture));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts WHERE case_id=?",
                Integer.class, sale.id()));
    }

    @Test
    void queryConfirmsUnknownRefundAndDoesNotRefundTwice() {
        Fixture fixture = fixture();
        AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(),
                new AfterSaleCreateDTO("REFUND_ONLY", "退款查询",
                        List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 1))));
        service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
        when(gateway.configured()).thenReturn(true);
        when(gateway.refund(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), anyString(),
                eq(new BigDecimal("10.00")))).thenReturn(new RefundGateway.Result(false,
                fixture.outTradeNo(), fixture.tradeNo(), null, null));
        AfterSaleVO first = service.refund(fixture.merchant(), fixture.shopId(), sale.id());
        jdbc.update("UPDATE refund_attempts SET requested_at=?, updated_at=? WHERE case_id=?",
                LocalDateTime.now().minusSeconds(20), LocalDateTime.now().minusSeconds(20), sale.id());
        when(gateway.query(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), eq(first.refund().outRequestNo())))
                .thenReturn(new RefundGateway.Result(true, fixture.outTradeNo(), fixture.tradeNo(),
                        first.refund().outRequestNo(), new BigDecimal("10.00")));
        service.reconcileUnknown();
        assertEquals("REFUNDED", service.buyerDetail(fixture.buyer(), sale.id()).status());
        assertEquals(2, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.skuId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE reason='REFUND_RETURN' " +
                "AND order_item_id=?", Integer.class, fixture.itemId()));
        assertEquals(1, searchEvents(fixture));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts WHERE case_id=?",
                Integer.class, sale.id()));
    }

    @Test
    void reconciliationDoesNotSettleAfterItsLeaseIsReplaced() {
        Fixture fixture = fixture();
        AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(),
                new AfterSaleCreateDTO("REFUND_ONLY", "核对租约测试",
                        List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 1))));
        service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
        when(gateway.configured()).thenReturn(true);
        when(gateway.refund(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), anyString(),
                eq(new BigDecimal("10.00")))).thenReturn(new RefundGateway.Result(false,
                fixture.outTradeNo(), fixture.tradeNo(), null, null));
        AfterSaleVO first = service.refund(fixture.merchant(), fixture.shopId(), sale.id());
        jdbc.update("UPDATE refund_attempts SET requested_at=?, updated_at=? WHERE case_id=?",
                LocalDateTime.now().minusSeconds(20), LocalDateTime.now().minusSeconds(20), sale.id());
        Long attemptId = jdbc.queryForObject("SELECT id FROM refund_attempts WHERE case_id=?",
                Long.class, sale.id());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastCheck = jdbc.queryForObject("SELECT updated_at FROM refund_attempts WHERE id=?",
                LocalDateTime.class, attemptId);
        assertEquals(1, refundAttempts.claimReconciliation(attemptId, "first", now.minusSeconds(10), 120));
        assertEquals(lastCheck, jdbc.queryForObject("SELECT updated_at FROM refund_attempts WHERE id=?",
                LocalDateTime.class, attemptId));
        assertEquals(0, refundAttempts.claimReconciliation(attemptId, "second", now.minusSeconds(10), 120));
        jdbc.update("UPDATE refund_attempts SET reconcile_claim_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND), " +
                "updated_at=updated_at WHERE id=?", attemptId);
        assertEquals(1, refundAttempts.claimReconciliation(attemptId, "second", now.minusSeconds(10), 120));
        refundAttempts.releaseReconciliation(attemptId, "second");
        assertEquals(lastCheck, jdbc.queryForObject("SELECT updated_at FROM refund_attempts WHERE id=?",
                LocalDateTime.class, attemptId));
        doAnswer(invocation -> {
            jdbc.update("UPDATE refund_attempts SET reconcile_claim_token=?, reconcile_claim_until=? WHERE case_id=?",
                    "new-owner", LocalDateTime.now().plusMinutes(2), sale.id());
            return new RefundGateway.Result(true, fixture.outTradeNo(), fixture.tradeNo(),
                    first.refund().outRequestNo(), new BigDecimal("10.00"));
        }).when(gateway).query(eq(fixture.outTradeNo()), eq(fixture.tradeNo()),
                eq(first.refund().outRequestNo()));

        service.reconcileUnknown();

        assertEquals("REFUNDING", service.buyerDetail(fixture.buyer(), sale.id()).status());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM refund_attempts WHERE case_id=?",
                String.class, sale.id()));
        assertEquals("new-owner", jdbc.queryForObject(
                "SELECT reconcile_claim_token FROM refund_attempts WHERE case_id=?", String.class, sale.id()));
    }

    @Test
    void rejectionReleasesQuantityAndMerchantListUsesCursor() {
        Fixture fixture = fixture();
        AfterSaleCreateDTO request = new AfterSaleCreateDTO("REFUND_ONLY", "商品问题",
                List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 2)));
        AfterSaleVO first = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(), request);
        assertEquals(first.id(), service.merchantList(fixture.merchant(), fixture.shopId(), null,
                "REQUESTED", 1).items().getFirst().id());
        service.review(fixture.merchant(), fixture.shopId(), first.id(),
                new AfterSaleReviewDTO(false, "不符合条件"));
        AfterSaleVO second = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(), request);
        assertEquals(second.id(), service.buyerList(fixture.buyer(), fixture.orderId(), null, 1)
                .items().getFirst().id());
        assertEquals(first.id(), service.buyerList(fixture.buyer(), fixture.orderId(), second.id(), 1)
                .items().getFirst().id());
        assertEquals(null, service.buyerList(fixture.buyer(), fixture.orderId(), second.id(), 1)
                .nextBeforeId());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.merchantList(fixture.merchant(), fixture.shopId(), null, "WRONG", 10)).getCode());
    }

    @Test
    void shippingWaitsForAfterSaleAndStopsAfterFullRefund() {
        Fixture fixture = fixture();
        AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(),
                new AfterSaleCreateDTO("REFUND_ONLY", "整单退款",
                        List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 2))));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> shipments.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(),
                        new ShipmentCreateDTO("顺丰", "SF-OUT"))).getCode());
        service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
        when(gateway.configured()).thenReturn(true);
        when(gateway.refund(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), anyString(),
                eq(new BigDecimal("20.00")))).thenReturn(new RefundGateway.Result(true,
                fixture.outTradeNo(), fixture.tradeNo(), null, new BigDecimal("20.00")));
        service.refund(fixture.merchant(), fixture.shopId(), sale.id());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> shipments.ship(fixture.merchant(), fixture.shopId(), fixture.orderId(),
                        new ShipmentCreateDTO("顺丰", "SF-OUT"))).getCode());
    }

    @Test
    void mismatchedProviderAmountNeverSettlesRefund() {
        Fixture fixture = fixture();
        AfterSaleVO sale = service.create(fixture.buyer(), fixture.orderId(), UUID.randomUUID().toString(),
                new AfterSaleCreateDTO("REFUND_ONLY", "金额核对",
                        List.of(new AfterSaleCreateDTO.Item(fixture.itemId(), 1))));
        service.review(fixture.merchant(), fixture.shopId(), sale.id(), new AfterSaleReviewDTO(true, null));
        when(gateway.configured()).thenReturn(true);
        when(gateway.refund(eq(fixture.outTradeNo()), eq(fixture.tradeNo()), anyString(),
                eq(new BigDecimal("10.00")))).thenReturn(new RefundGateway.Result(true,
                fixture.outTradeNo(), fixture.tradeNo(), null, new BigDecimal("9.00")));
        assertEquals("UNKNOWN", service.refund(fixture.merchant(), fixture.shopId(), sale.id()).refund().status());
        assertEquals("REFUNDING", service.buyerDetail(fixture.buyer(), sale.id()).status());
        assertEquals(1, jdbc.queryForObject("SELECT stock FROM skus WHERE id=?", Integer.class, fixture.skuId()));
        assertEquals(0, searchEvents(fixture));
    }

    private int searchEvents(Fixture fixture) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'SEARCH_PRODUCT_REFRESH' " +
                "AND aggregate_id = (SELECT product_id FROM skus WHERE id = ?)", Integer.class, fixture.skuId());
    }

    private Fixture fixture() {
        String token = UUID.randomUUID().toString().replace("-", "");
        Long buyer = user("buyer" + token, "USER");
        Long merchant = user("merchant" + token, "MERCHANT");
        jdbc.update("INSERT INTO shops (owner_user_id,name,enabled) VALUES (?,?,1)", merchant, "售后店铺" + token);
        Long shopId = jdbc.queryForObject("SELECT id FROM shops WHERE name=?", Long.class, "售后店铺" + token);
        jdbc.update("INSERT INTO categories (name,level,visible,sort_order) VALUES (?,1,1,0)", "售后分类" + token);
        Long categoryId = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, "售后分类" + token);
        jdbc.update("INSERT INTO products (shop_id,category_id,name,status) VALUES (?,?,?,'ON_SALE')",
                shopId, categoryId, "售后商品" + token);
        Long productId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, "售后商品" + token);
        jdbc.update("INSERT INTO skus (product_id,option_signature,price,stock) VALUES (?,?,10,1)",
                productId, token + token);
        Long skuId = jdbc.queryForObject("SELECT id FROM skus WHERE product_id=?", Long.class, productId);
        jdbc.update("INSERT INTO orders (user_id,shop_id,total_amount,status,pay_expires_at,paid_at," +
                        "consignee_ciphertext,phone_ciphertext,province,city,district,detail_ciphertext,shipping_key_version) " +
                        "VALUES (?, ?, 20, 'PAID', NOW(), NOW(), ?, ?, '北京', '北京', '海淀', ?, 1)",
                buyer, shopId, new byte[]{1}, new byte[]{1}, new byte[]{1});
        Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE user_id=? AND shop_id=?", Long.class, buyer, shopId);
        jdbc.update("INSERT INTO order_items (order_id,sku_id,product_name,sku_spec,quantity,unit_price) " +
                "VALUES (?,?,?,'标准',2,10)", orderId, skuId, "售后商品" + token);
        Long itemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, orderId);
        String outTradeNo = "SHIWUSHOP" + token;
        String tradeNo = "TRADE" + token;
        jdbc.update("INSERT INTO payment_attempts (order_id,channel,out_trade_no,alipay_trade_no,app_id,seller_id," +
                        "amount,status,paid_at) VALUES (?,'ALIPAY_SANDBOX',?,?,'app','seller',20,'SUCCESS',NOW())",
                orderId, outTradeNo, tradeNo);
        return new Fixture(buyer, merchant, shopId, orderId, itemId, skuId, outTradeNo, tradeNo);
    }

    private Long user(String name, String role) {
        jdbc.update("INSERT INTO users (nickname) VALUES (?)", name);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE nickname=?", Long.class, name);
        jdbc.update("INSERT INTO user_accounts (user_id,username,password_hash,role,status) VALUES (?,?,?,?,'ACTIVE')",
                id, name, "test-only", role);
        return id;
    }

    private record Fixture(Long buyer, Long merchant, Long shopId, Long orderId, Long itemId, Long skuId,
                           String outTradeNo, String tradeNo) {}
}
