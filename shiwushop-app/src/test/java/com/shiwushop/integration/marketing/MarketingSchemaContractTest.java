package com.shiwushop.integration.marketing;

import com.shiwushop.trade.payment.LocalSimulationGateway;
import com.shiwushop.product.service.ProductArchiveConstraint;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL行内约束验证；直接SQL夹具不代表营销业务接口已实现。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.member.enabled=false",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MarketingSchemaContractTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    ProductArchiveConstraint activities;
    @MockitoBean(name = "localSimulationGateway")
    LocalSimulationGateway gateway;
    CommerceFixture fixture;
    long activity, scope, budget;
    String requestKey;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(
                    connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_"));
        }
        PaymentHttpFixture.configure(gateway);
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        requestKey = "schema-" + UUID.randomUUID();
        activity = activity("COUPON", requestKey, requestKey, 1);
        scope = fixture.catalog.insert("INSERT INTO marketing_activity_skus(activity_id,sku_id) VALUES(?,?)", activity,
                fixture.sku);
        budget = fixture.catalog.insert(
                "INSERT INTO marketing_budgets(activity_id,party,budget_amount) VALUES(?,'PLATFORM',100)", activity);
    }

    @AfterEach
    void closeSessions() {
        if (fixture != null) {
            fixture.client.close();
            fixture.merchantClient.close();
        }
    }

    long activity(String type, String key, String code, int version) {
        return fixture.catalog.insert("""
                INSERT INTO marketing_activities(activity_code,version_no,name,activity_type,status,start_at,end_at,
                  eligible_level_codes,creator_user_id,rules_text,request_scope,request_key,request_hash)
                VALUES(?,?,'秋日优惠',?,'DRAFT','2026-11-01','2026-12-01',JSON_ARRAY('ORDINARY'),?,
                  '指定商品优惠','CREATE_PLATFORM',?,UNHEX(SHA2(?,256)))
                """, code, version, type, fixture.catalog.merchant.id(), key, key);
    }

    void rule() {
        jdbc.update("""
                INSERT INTO marketing_coupon_rules(activity_id,face_amount,threshold_amount,issue_limit,per_user_limit,
                  claim_end_at,validity_days,grant_mode) VALUES(?,5,20,100,1,'2026-12-01',30,'COMPENSATION')
                """, activity);
    }

    long grant(long user, long actor, String source) {
        return fixture.catalog.insert("""
                INSERT INTO marketing_coupon_grants(user_id,activity_id,source_kind,source_key,entitled_count,
                  face_amount,threshold_amount,expires_at,status,next_attempt_at,actor_user_id,request_hash,reason,evidence_reference)
                VALUES(?,?,'COMPENSATION',?,1,5,20,'2026-12-01','PENDING','2026-11-04',?,UNHEX(SHA2('request',256)),'补发','CASE-1')
                """, user, activity, source, actor);
    }

    long coupon(long grant) {
        return fixture.catalog.insert("""
                INSERT INTO user_coupons(grant_id,ordinal_no,user_id,activity_id,face_amount,threshold_amount,valid_from,expires_at,status)
                VALUES(?,1,?,?,5,20,'2026-11-04','2026-12-01','AVAILABLE')
                """, grant, fixture.buyer.id(), activity);
    }

    void rejected(int code, String sql, Object... args) {
        var failure = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(sql, args));
        var cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(code, cause.getErrorCode(), cause.getMessage());
    }

    @Test
    void activityRequestIdentityAndReviewBoundariesArePersistent() {
        rejected(3819, "UPDATE marketing_activities SET end_at=start_at WHERE id=?", activity);
        rejected(3819, "UPDATE marketing_activities SET reviewer_user_id=creator_user_id WHERE id=?", activity);
        rejected(3819, "UPDATE marketing_activities SET request_key='with space' WHERE id=?", activity);
        rejected(3819, "UPDATE marketing_activities SET request_key=CONCAT('a',CHAR(127)) WHERE id=?", activity);
        var failure = assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> activity("COUPON", requestKey, "different-code", 1));
        assertEquals(1062, assertInstanceOf(SQLException.class, failure.getMostSpecificCause()).getErrorCode());
        long distinct = activity("COUPON", requestKey.toUpperCase(Locale.ROOT), requestKey, 2);
        assertNotEquals(activity, distinct);
    }

    @Test
    void scopeRequiresRealSkuAndValidQuantitiesAndPointUnits() {
        rejected(1452, "UPDATE marketing_activity_skus SET sku_id=? WHERE id=?", Long.MAX_VALUE, scope);
        rejected(3819, "UPDATE marketing_activity_skus SET reserved_qty=-1 WHERE id=?", scope);
        rejected(3819, "UPDATE marketing_activity_skus SET point_cost_units=101 WHERE id=?", scope);
        rejected(1062, "INSERT INTO marketing_activity_skus(activity_id,sku_id) VALUES(?,?)", activity, fixture.sku);
    }

    @Test
    void memberPriceExcludesOrdinaryTierAndNonpositivePrice() {
        rejected(3819, "INSERT INTO marketing_member_prices(activity_sku_id,level_code,price) VALUES(?,'ORDINARY',10)",
                scope);
        rejected(3819, "INSERT INTO marketing_member_prices(activity_sku_id,level_code,price) VALUES(?,'SILVER',0)",
                scope);
    }

    @Test
    void tierCannotMixReductionAndGiftOrRepeatThreshold() {
        jdbc.update(
                "INSERT INTO marketing_threshold_tiers(activity_id,threshold_amount,reduction_amount) VALUES(?,20,5)",
                activity);
        rejected(3819,
                "UPDATE marketing_threshold_tiers SET gift_activity_sku_id=?,gift_quantity=1 WHERE activity_id=?",
                scope, activity);
        rejected(3819, "UPDATE marketing_threshold_tiers SET reduction_amount=21 WHERE activity_id=?", activity);
        rejected(1062,
                "INSERT INTO marketing_threshold_tiers(activity_id,threshold_amount,reduction_amount) VALUES(?,20,3)",
                activity);
    }

    @Test
    void budgetMustCoverPromisesAndKeepOneResponsibilityRow() {
        rejected(3819, "UPDATE marketing_budgets SET held_amount=70,spent_amount=31 WHERE id=?", budget);
        rejected(3819, "UPDATE marketing_budgets SET shop_id=? WHERE id=?", fixture.catalog.shop, budget);
        rejected(1062, "INSERT INTO marketing_budgets(activity_id,party) VALUES(?,'PLATFORM')", activity);
        assertEquals(100,
                jdbc.queryForObject("SELECT budget_amount FROM marketing_budgets WHERE id=?", Integer.class, budget));
    }

    @Test
    void holdFlowsAreNotMistakenForCurrentBalances() {
        long hold = fixture.catalog.insert("""
                INSERT INTO marketing_budget_holds(budget_id,operation_key,source_kind,source_id,authorized_amount,
                  held_amount,spent_amount,released_amount,refunded_amount,status)
                VALUES(?,'hold','COUPON',1,5,2,3,5,5,'PARTIAL')
                """, budget);
        rejected(3819, "UPDATE marketing_budget_holds SET held_amount=3 WHERE id=?", hold);
        assertEquals(5,
                jdbc.queryForObject("SELECT released_amount FROM marketing_budget_holds WHERE id=?", Integer.class,
                        hold));
    }

    @Test
    void manualBudgetEntryRequiresDeltaActorAndFullEvidence() {
        long entry = fixture.catalog.insert("""
                INSERT INTO marketing_budget_entries(budget_id,operation_key,event_kind,held_after,spent_after,
                  actor_user_id,reason,budget_delta_amount,budget_after,evidence_reference)
                VALUES(?,'adjust','ADJUST',0,0,?,'增加预算',50,150,?)
                """, budget, fixture.catalog.merchant.id(), "凭".repeat(500));
        rejected(3819, "UPDATE marketing_budget_entries SET evidence_reference=NULL WHERE id=?", entry);
        rejected(3819, "UPDATE marketing_budget_entries SET budget_delta_amount=0 WHERE id=?", entry);
        rejected(3819, "UPDATE marketing_budget_entries SET event_kind='RESERVE' WHERE id=?", entry);
        assertEquals(500,
                jdbc.queryForObject("SELECT CHAR_LENGTH(evidence_reference) FROM marketing_budget_entries WHERE id=?",
                        Integer.class, entry));
    }

    @Test
    void quotaUniqueKeySpansActivityVersions() {
        long next = activity("COUPON", requestKey + "-v2", requestKey, 2);
        jdbc.update(
                "INSERT INTO marketing_user_quotas(activity_id,activity_code,user_id,quota_kind,period_key) VALUES(?,?,?,'BUY','ALL')",
                activity, requestKey, fixture.buyer.id());
        rejected(1062,
                "INSERT INTO marketing_user_quotas(activity_id,activity_code,user_id,quota_kind,period_key) VALUES(?,?,?,'BUY','ALL')",
                next, requestKey, fixture.buyer.id());
    }

    @Test
    void couponRuleCannotExceedIssueLimitOrCreateWrongMonthlyShape() {
        rule();
        rejected(3819, "UPDATE marketing_coupon_rules SET issued_count=101 WHERE activity_id=?", activity);
        rejected(3819, "UPDATE marketing_coupon_rules SET grant_mode='MEMBERSHIP' WHERE activity_id=?", activity);
        rejected(3819, "UPDATE marketing_coupon_rules SET threshold_amount=4 WHERE activity_id=?", activity);
    }

    @Test
    void codeBatchRetainsOrdinalIdentityAndSingleUseLimit() {
        rule();
        long code = fixture.catalog.insert("""
                INSERT INTO marketing_coupon_codes(activity_id,code_hash,code_kind,max_redemptions,redeemed_count,
                  expires_at,status,creator_user_id,creation_request_key,creation_request_hash,creation_ordinal)
                VALUES(?,UNHEX(SHA2(?,256)),'SINGLE',1,0,'2026-12-01','ACTIVE',?,'batch',UNHEX(SHA2('batch',256)),1)
                """, activity, requestKey, fixture.catalog.merchant.id());
        rejected(3819, "UPDATE marketing_coupon_codes SET max_redemptions=2 WHERE id=?", code);
        rejected(3819, "UPDATE marketing_coupon_codes SET creation_ordinal=0 WHERE id=?", code);
        rejected(3819, "UPDATE marketing_coupon_codes SET creation_request_key='bad key' WHERE id=?", code);
        rejected(1062, """
                INSERT INTO marketing_coupon_codes(activity_id,code_hash,code_kind,max_redemptions,redeemed_count,
                  expires_at,status,creator_user_id,creation_request_key,creation_request_hash,creation_ordinal)
                SELECT activity_id,UNHEX(SHA2(CONCAT(creation_request_key,id),256)),code_kind,max_redemptions,redeemed_count,
                  expires_at,status,creator_user_id,creation_request_key,creation_request_hash,creation_ordinal
                FROM marketing_coupon_codes WHERE id=?
                """, code);
    }

    @Test
    void compensationIdentityCannotBeBypassedByChangingRecipient() {
        rule();
        long actor = fixture.catalog.merchant.id();
        long grant = grant(fixture.buyer.id(), actor, "COMPENSATION:request");
        long other = new TestAccounts(jdbc).create("USER", "Shop123!").id();
        var failure = assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> grant(other, actor, "COMPENSATION:request"));
        assertEquals(1062, assertInstanceOf(SQLException.class, failure.getMostSpecificCause()).getErrorCode());
        rejected(3819, "UPDATE marketing_coupon_grants SET actor_user_id=NULL WHERE id=?", grant);
        rejected(3819, "UPDATE marketing_coupon_grants SET request_hash=NULL WHERE id=?", grant);
        rejected(3819, "UPDATE marketing_coupon_grants SET claim_token='incomplete-lease' WHERE id=?", grant);
    }

    @Test
    void couponOrdinalAndValidityCannotBeLost() {
        rule();
        long grant = grant(fixture.buyer.id(), fixture.catalog.merchant.id(), "COMPENSATION:request");
        long coupon = coupon(grant);
        rejected(3819, "UPDATE user_coupons SET expires_at=valid_from WHERE id=?", coupon);
        rejected(3819, "UPDATE user_coupons SET reservation_version=-1 WHERE id=?", coupon);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> coupon(grant));
    }

    @Test
    void promotionPartsMustExactlySumToTotal() throws Exception {
        long order = fixture.order("allocation-schema");
        long item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, order);
        long allocation = fixture.catalog.insert("""
                INSERT INTO order_promotion_allocations(order_item_id,activity_id,benefit_kind,amount,platform_amount,
                  merchant_amount,platform_share_bp,rule_snapshot) VALUES(?,?,'COUPON',5,2.50,2.50,5000,JSON_OBJECT())
                """, item, activity);
        rejected(3819, "UPDATE order_promotion_allocations SET merchant_amount=2.51 WHERE id=?", allocation);
        rejected(3819, "UPDATE order_promotion_allocations SET platform_share_bp=10001 WHERE id=?", allocation);
    }

    @Test
    void refundRequiresRealAfterSaleItemAndSeparatesBaseCash() {
        rejected(1452, """
                INSERT INTO marketing_refund_allocations(after_sale_item_id,benefit_key,operation_key,status)
                VALUES(?,'BASE',?,'PENDING')
                """, Long.MAX_VALUE, requestKey);
        rejected(3819, """
                INSERT INTO marketing_refund_allocations(after_sale_item_id,benefit_key,operation_key,cash_refund_amount,status)
                VALUES(?,'COUPON',?,1,'PENDING')
                """, Long.MAX_VALUE, requestKey);
    }

    @Test
    void archiveCommitmentReadsLiveActivityAndPoolButIgnoresConsumedHistory() {
        assertEquals(Boolean.FALSE, transactions.execute(tx ->
                activities.hasUnfinishedProductCommitments(fixture.catalog.shop, fixture.product)));
        jdbc.update("UPDATE marketing_activities SET status='APPROVED' WHERE id=?", activity);
        assertEquals(Boolean.TRUE, transactions.execute(tx ->
                activities.hasUnfinishedProductCommitments(fixture.catalog.shop, fixture.product)));
        jdbc.update("UPDATE marketing_activities SET status='TERMINATED' WHERE id=?", activity);
        assertEquals(Boolean.FALSE, transactions.execute(tx ->
                activities.hasUnfinishedProductCommitments(fixture.catalog.shop, fixture.product)));
        jdbc.update("INSERT INTO marketing_stock_pools(activity_sku_id,allocated_qty,available_qty) VALUES(?,1,1)", scope);
        assertEquals(Boolean.TRUE, transactions.execute(tx ->
                activities.hasUnfinishedProductCommitments(fixture.catalog.shop, fixture.product)));
        jdbc.update("UPDATE marketing_stock_pools SET available_qty=0,consumed_qty=1 WHERE activity_sku_id=?", scope);
        assertEquals(Boolean.FALSE, transactions.execute(tx ->
                activities.hasUnfinishedProductCommitments(fixture.catalog.shop, fixture.product)));
    }
}
