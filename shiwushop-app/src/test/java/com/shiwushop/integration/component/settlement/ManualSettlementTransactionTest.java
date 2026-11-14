package com.shiwushop.integration.component.settlement;

import com.shiwushop.common.BusinessException;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.TestAccounts;
import com.shiwushop.trade.settlement.ManualSettlementService;
import com.shiwushop.trade.settlement.dto.SettlementRequests;
import com.shiwushop.trade.settlement.mapper.ManualSettlementMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.autoconfigure.exclude=", "server.port=18127", "shop.member.enabled=false",
        "shop.marketing.enabled=false", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=28379", "spring.data.redis.database=12"})
class ManualSettlementTransactionTest {
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.username",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME")));
        properties.add("spring.datasource.password",
                () -> Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD")));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ManualSettlementService service;
    @Autowired ManualSettlementMapper settlementMapper;
    CommerceFixture fixture;
    TestAccounts admins;
    long adminId;

    @BeforeEach
    void prepare() throws Exception {
        try (var db = jdbc.getDataSource().getConnection()) {
            org.junit.jupiter.api.Assertions.assertEquals(true, db.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_trade_test4_20261105b"));
        }
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:18127");
        admins = new TestAccounts(jdbc);
        adminId = admins.create("ADMIN", "Shop123!").id();
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            for (long id : jdbc.queryForList("SELECT s.id FROM manual_settlements s "
                    + "JOIN manual_settlement_lines l ON l.settlement_id=s.id "
                    + "JOIN orders o ON o.id=l.order_id WHERE o.user_id=? GROUP BY s.id",
                    Long.class, fixture.buyer.id())) {
                jdbc.update("DELETE FROM manual_settlement_adjustments WHERE settlement_id=?", id);
                jdbc.update("DELETE FROM manual_settlement_lines WHERE settlement_id=?", id);
                jdbc.update("DELETE FROM audit_events WHERE target_type='MANUAL_SETTLEMENT' AND target_id=?", id);
                jdbc.update("DELETE FROM manual_settlements WHERE id=?", id);
            }
            jdbc.update("DELETE a FROM order_promotion_allocations a JOIN order_items i "
                    + "ON i.id=a.order_item_id JOIN orders o ON o.id=i.order_id WHERE o.user_id=?",
                    fixture.buyer.id());
            jdbc.update("DELETE FROM marketing_activities WHERE creator_user_id=?", adminId);
            fixture.close();
            admins.close();
        }
    }

    @Test
    void concurrentAdminsCannotOccupyTheSameOrderTwice() throws Exception {
        long order = paidOrder();
        long secondAdmin = admins.create("ADMIN", "Shop123!").id();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> createWhenReleased(start, adminId, order, "parallel-one"));
            var second = pool.submit(() -> createWhenReleased(start, secondAdmin, order, "parallel-two"));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM manual_settlement_lines WHERE active=1 AND order_id=?", order));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE target_type='MANUAL_SETTLEMENT' "
                + "AND action='MANUAL_SETTLEMENT_CREATED' AND target_id IN "
                + "(SELECT settlement_id FROM manual_settlement_lines WHERE order_id=?)", order));
    }

    @Test
    void formerOwnerCanBeAttestedWithoutAssigningOldOrderToNewOwner() throws Exception {
        long order = paidOrder();
        long oldOwner = fixture.catalog.merchant.id();
        long newOwner = admins.create("MERCHANT", "Shop123!").id();
        jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?", newOwner, fixture.catalog.shop);
        try {
            var created = service.create(adminId, new SettlementRequests.Create(fixture.catalog.shop,
                    oldOwner, "former-owner-claim", List.of(
                    new SettlementRequests.Selection(order, "signed transfer ledger"))));
            assertEquals(oldOwner, created.payeeUserId());
            assertEquals(oldOwner, created.lines().getFirst().ownerUserIdAtOrder());
            assertEquals("signed transfer ledger", created.lines().getFirst().ownershipEvidence());
        } finally {
            jdbc.update("UPDATE shops SET owner_user_id=? WHERE id=?", oldOwner, fixture.catalog.shop);
        }
    }

    private boolean createWhenReleased(java.util.concurrent.CountDownLatch start, long admin, long order,
                                       String key) throws Exception {
        start.await();
        try {
            service.create(admin, new SettlementRequests.Create(fixture.catalog.shop,
                    fixture.catalog.merchant.id(), key, List.of(
                    new SettlementRequests.Selection(order, "signed merchant evidence"))));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Test
    void locksEligibilityAndPreservesSingleActiveOrderAcrossVoidAndRebuild() throws Exception {
        long order = paidOrder();
        var selection = List.of(new SettlementRequests.Selection(order, "signed merchant evidence"));
        var request = new SettlementRequests.Create(fixture.catalog.shop,
                fixture.catalog.merchant.id(), "settlement-first", selection);
        assertEquals("PAYABLE", service.candidates(adminId, fixture.catalog.shop, null, 20)
                .items().stream().filter(c -> c.orderId() == order).findFirst().orElseThrow().state());
        var first = service.create(adminId, request);
        assertEquals(new BigDecimal("19.90"), first.amount());
        assertEquals(first.id(), service.create(adminId, request).id());
        jdbc.update("UPDATE user_accounts SET status='DISABLED' WHERE user_id=?",
                fixture.catalog.merchant.id());
        try {
            assertEquals(first.id(), service.create(adminId, request).id());
        } finally {
            jdbc.update("UPDATE user_accounts SET status='ACTIVE' WHERE user_id=?",
                    fixture.catalog.merchant.id());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM manual_settlement_lines WHERE active=1 AND order_id=?", order));
        assertThrows(RuntimeException.class, () -> service.create(adminId,
                new SettlementRequests.Create(fixture.catalog.shop, fixture.catalog.merchant.id(),
                        "settlement-second", selection)));
        assertEquals(1, count("SELECT COUNT(*) FROM manual_settlement_lines WHERE active=1 AND order_id=?", order));

        jdbc.update("UPDATE orders SET fully_received_at=NOW() WHERE id=?", order);
        assertThrows(BusinessException.class, () -> service.approve(adminId, first.id(),
                new SettlementRequests.Key("approve-first")));
        service.voidSettlement(adminId, first.id(), new SettlementRequests.Void(
                "void-first", " source changed ", " bank queue checked empty "));
        assertEquals("VOID", service.voidSettlement(adminId, first.id(), new SettlementRequests.Void(
                "void-first", "source changed", "bank queue checked empty")).status());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.voidSettlement(adminId,
                first.id(), new SettlementRequests.Void("void-first", "different reason",
                        "bank queue checked empty"))).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.voidSettlement(adminId,
                first.id(), new SettlementRequests.Void("void-first", "source changed",
                        "different bank evidence"))).getCode());
        assertEquals("source changed", jdbc.queryForObject("SELECT void_reason FROM manual_settlements WHERE id=?",
                String.class, first.id()));
        assertEquals("bank queue checked empty", jdbc.queryForObject(
                "SELECT no_payment_evidence FROM manual_settlements WHERE id=?", String.class, first.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE target_type='MANUAL_SETTLEMENT' "
                + "AND target_id=? AND action='MANUAL_SETTLEMENT_VOID'", first.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM manual_settlement_lines WHERE active=1 AND order_id=?", order));
        jdbc.update("UPDATE orders SET fully_received_at=DATE_SUB(NOW(), INTERVAL 8 DAY) WHERE id=?", order);
        var rebuilt = service.create(adminId, new SettlementRequests.Create(fixture.catalog.shop,
                fixture.catalog.merchant.id(), "settlement-third", selection));
        assertEquals("DRAFT", rebuilt.status());
        assertEquals(1, count("SELECT COUNT(*) FROM manual_settlement_lines WHERE active=1 AND order_id=?", order));
        assertEquals(2, count("SELECT COUNT(*) FROM manual_settlement_lines WHERE order_id=?", order));
    }

    @Test
    void recordsActualOfflinePaymentOnceAndKeepsAuditHistory() throws Exception {
        long order = paidOrder();
        var created = service.create(adminId, new SettlementRequests.Create(fixture.catalog.shop,
                fixture.catalog.merchant.id(), "settlement-paid", List.of(
                new SettlementRequests.Selection(order, "signed merchant evidence"))));
        service.approve(adminId, created.id(), new SettlementRequests.Key("approve-paid"));
        service.processing(adminId, created.id(), new SettlementRequests.Key("processing-paid"));
        var payment = new SettlementRequests.Payment("payment-paid", new BigDecimal("19.90"),
                LocalDateTime.now().minusMinutes(1), "BANK_TRANSFER", "bank-ref-1", null,
                fixture.catalog.merchant.id());
        assertEquals("PAID", service.payment(adminId, created.id(), payment).status());
        assertEquals("PAID", service.payment(adminId, created.id(), payment).status());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE target_type='MANUAL_SETTLEMENT' "
                + "AND target_id=? AND action='MANUAL_SETTLEMENT_PAID_RECORDED'", created.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM manual_settlement_adjustments WHERE settlement_id=?", created.id()));

        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        long shipment = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id=?", Long.class, order);
        long paymentAttempt = jdbc.queryForObject("SELECT id FROM payment_attempts WHERE order_id=?",
                Long.class, order);
        jdbc.update("INSERT INTO after_sale_cases(order_id,type,status,reason,refund_amount,requested_at,finished_at) "
                + "VALUES (?,'REFUND_ONLY','REFUNDED','partial refund',5,NOW(),NOW())", order);
        long caseId = jdbc.queryForObject("SELECT id FROM after_sale_cases WHERE order_id=?", Long.class, order);
        jdbc.update("INSERT INTO after_sale_items(case_id,order_item_id,quantity,refund_amount,source_kind,"
                + "source_shipment_id) VALUES (?,?,1,5,'SHIPMENT',?)", caseId, item, shipment);
        long saleItem = jdbc.queryForObject("SELECT id FROM after_sale_items WHERE case_id=?", Long.class, caseId);
        jdbc.update("INSERT INTO refund_attempts(case_id,payment_attempt_id,out_request_no,amount,status,requested_at) "
                + "VALUES (?,?,?,5,'SUCCESS',NOW())", caseId, paymentAttempt, "manual-refund-" + caseId);
        jdbc.update("INSERT INTO marketing_refund_allocations(after_sale_item_id,benefit_key,operation_key,"
                + "cash_refund_amount,status) VALUES (?,'BASE',?,5,'APPLIED')", saleItem,
                "manual-refund-base-" + caseId);
        assertEquals(1, service.adminDetail(adminId, created.id()).adjustments().size());
        assertEquals(1, service.adminDetail(adminId, created.id()).adjustments().size());
        assertEquals(new BigDecimal("-5.00"), jdbc.queryForObject("SELECT amount_delta FROM "
                + "manual_settlement_adjustments WHERE source_case_id=?", BigDecimal.class, caseId));
        long adjustment = jdbc.queryForObject("SELECT id FROM manual_settlement_adjustments WHERE source_case_id=?",
                Long.class, caseId);
        service.reviewAdjustment(adminId, created.id(), adjustment,
                new SettlementRequests.Review("review-refund", "manual reconciliation record"));
        assertEquals("REVIEWED", jdbc.queryForObject("SELECT status FROM manual_settlement_adjustments WHERE id=?",
                String.class, adjustment));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_events WHERE target_type='MANUAL_SETTLEMENT' "
                + "AND target_id=? AND action='MANUAL_SETTLEMENT_ADJUSTMENT_CREATED'", created.id()));
    }

    @Test
    void giftNullCashSnapshotUsesFrozenSupplyCostWithoutHidingMissingFacts() throws Exception {
        long order = fixture.order("gift-settlement-" + System.nanoTime());
        jdbc.update("UPDATE orders SET status='PAID',paid_at=NOW(),pricing_version=1,"
                + "original_amount=19.90,discount_amount=0,platform_subsidy_amount=0 WHERE id=?", order);
        jdbc.update("UPDATE order_items SET original_unit_price=19.90,discount_amount=0,"
                + "payable_amount=19.90 WHERE order_id=?", order);
        jdbc.update("INSERT INTO payment_attempts(order_id,channel,out_trade_no,app_id,seller_id,amount,status,paid_at) "
                + "VALUES (?,'ALIPAY_SANDBOX',?,'test-app','test-seller',19.90,'SUCCESS',NOW())",
                order, "gift-pay-" + order);
        long gift = fixture.catalog.insert("INSERT INTO order_items(order_id,sku_id,product_name,sku_spec,"
                + "quantity,unit_price,item_kind,original_unit_price,payable_amount) "
                + "VALUES (?,?,?, ?,1,6,'GIFT',6,NULL)", order, fixture.sku, "frozen gift", "gift");
        String key = "gift-activity-" + order;
        long activity = fixture.catalog.insert("INSERT INTO marketing_activities(activity_code,version_no,name,"
                + "activity_type,status,start_at,end_at,eligible_level_codes,creator_user_id,rules_text,"
                + "request_scope,request_key,request_hash) VALUES (?,1,'frozen gift','FULL_GIFT','RUNNING',"
                + "NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY,JSON_ARRAY('ORDINARY'),?,'gift source',"
                + "'R03_GIFT',?,UNHEX(REPEAT('a',64)))", key, adminId, key);
        jdbc.update("INSERT INTO order_promotion_allocations(order_item_id,activity_id,benefit_kind,amount,"
                + "platform_amount,merchant_amount,platform_share_bp,unit_cost_amount,rule_snapshot) "
                + "VALUES (?,?,'GIFT_COST',6,6,0,10000,6,JSON_OBJECT())", gift, activity);
        assertEquals(200, fixture.merchantClient.post("/merchant/shops/" + fixture.catalog.shop
                + "/orders/" + order + "/shipment", java.util.Map.of("carrierName", "SF",
                "trackingNo", "SF-GIFT-" + order)).statusCode());
        assertEquals(200, fixture.client.post("/orders/" + order + "/shipment/confirm",
                java.util.Map.of()).statusCode());
        jdbc.update("UPDATE orders SET fully_received_at=DATE_SUB(NOW(), INTERVAL 8 DAY) WHERE id=?", order);
        assertEquals(0, settlementMapper.itemFacts(order).missingPayableItems());
        assertEquals(new BigDecimal("19.90"), settlementMapper.itemFacts(order).payableTotal());
        assertEquals(new BigDecimal("25.90"), candidate(order).payableAmount());

        jdbc.update("UPDATE order_items SET payable_amount=NULL WHERE order_id=? AND item_kind='SALE'", order);
        assertEquals("REVIEW_REQUIRED", candidate(order).state());
        jdbc.update("UPDATE order_items SET payable_amount=19.90 WHERE order_id=? AND item_kind='SALE'", order);
        jdbc.update("UPDATE order_promotion_allocations SET unit_cost_amount=NULL WHERE order_item_id=?", gift);
        assertEquals("REVIEW_REQUIRED", candidate(order).state());
        jdbc.update("UPDATE order_promotion_allocations SET unit_cost_amount=6 WHERE order_item_id=?", gift);

        var created = service.create(adminId, new SettlementRequests.Create(fixture.catalog.shop,
                fixture.catalog.merchant.id(), "gift-claim", List.of(
                new SettlementRequests.Selection(order, "frozen gift cost attested"))));
        assertEquals(new BigDecimal("25.90"), created.amount());
        assertEquals(new BigDecimal("6.00"), created.lines().getFirst().costPlatform());
    }

    private com.shiwushop.trade.settlement.vo.SettlementView.Candidate candidate(long order) {
        return service.candidates(adminId, fixture.catalog.shop, null, 20).items().stream()
                .filter(row -> row.orderId() == order).findFirst().orElseThrow();
    }

    private long paidOrder() throws Exception {
        long order = fixture.order("manual-settlement-" + System.nanoTime());
        jdbc.update("UPDATE orders SET status='PAID',paid_at=NOW() WHERE id=?", order);
        jdbc.update("INSERT INTO payment_attempts(order_id,channel,out_trade_no,app_id,seller_id,amount,status,paid_at) "
                + "VALUES (?,'ALIPAY_SANDBOX',?,'test-app','test-seller',19.90,'SUCCESS',NOW())",
                order, "manual-pay-" + order);
        assertEquals(200, fixture.merchantClient.post("/merchant/shops/" + fixture.catalog.shop
                + "/orders/" + order + "/shipment", java.util.Map.of("carrierName", "SF",
                "trackingNo", "SF-SETTLEMENT-" + order)).statusCode());
        assertEquals(200, fixture.client.post("/orders/" + order + "/shipment/confirm",
                java.util.Map.of()).statusCode());
        jdbc.update("UPDATE orders SET fully_received_at=DATE_SUB(NOW(), INTERVAL 8 DAY) WHERE id=?", order);
        return order;
    }

    private int count(String sql, Object... values) {
        return jdbc.queryForObject(sql, Integer.class, values);
    }
}
