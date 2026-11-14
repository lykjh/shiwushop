package com.shiwushop.integration.marketing;

import com.shiwushop.common.BusinessException;
import com.shiwushop.marketing.mapper.MarketingOrderSourceMapper;
import com.shiwushop.marketing.mapper.MarketingGroupMapper;
import com.shiwushop.marketing.service.MarketingOrderSettlement;
import com.shiwushop.marketing.service.MarketingGroupExpiryService;
import com.shiwushop.marketing.service.impl.MarketingStockLedger;
import com.shiwushop.support.CatalogFixture;
import com.shiwushop.support.TestAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL事务中的首次参与、根锁顺序和请求身份；测试事务回滚不留活动空壳。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.marketing.enabled=true",
        "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class MarketingOrderSourceDatabaseTest {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MarketingOrderSettlement settlement;
    @Autowired
    MarketingOrderSourceMapper sources;
    @Autowired
    MarketingGroupMapper groups;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    MarketingStockLedger stock;
    @Autowired
    MarketingGroupExpiryService groupExpiry;

    @BeforeEach
    void isolatedDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_marketing_"));
        }
    }

    private long activity(CatalogFixture catalog, String type) {
        String key = "source-" + UUID.randomUUID();
        return catalog.insert("""
                INSERT INTO marketing_activities(activity_code,version_no,name,activity_type,status,start_at,end_at,
                  eligible_level_codes,creator_user_id,rules_text,request_scope,request_key,request_hash)
                VALUES(?,1,'活动来源',?,'RUNNING','2020-01-01','2035-12-01',JSON_ARRAY('ORDINARY'),?,
                  '隔离测试','CREATE_PLATFORM',?,UNHEX(SHA2(?,256)))
                """, key, type, catalog.merchant.id(), key, key);
    }

    private long scope(CatalogFixture catalog, long activity, long sku, String role,
                       String price, Long points, Integer teamSize) {
        return catalog.insert("""
                INSERT INTO marketing_activity_skus(activity_id,sku_id,scope_role,activity_price,point_cost_units,
                  team_size,participation_seconds)
                VALUES(?,?,?,?,?,?,?)
                """, activity, sku, role, price, points, teamSize, teamSize == null ? null : 3600);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void paidSeatWaitingForRootLockCannotCompleteAtDeadline() throws Exception {
        var catalog = new CatalogFixture(jdbc);
        long activity = activity(catalog, "GROUP");
        long product = catalog.product("拼团截止竞争", "ON_SALE", "20.00", 5);
        long groupScope = scope(catalog, activity, catalog.sku(product), "SALE", "9.00", null, 2);
        long group = catalog.insert("INSERT INTO marketing_groups(activity_sku_id,leader_user_id,"
                + "required_members,status,expires_at) VALUES(?,?,2,'FORMING','2035-12-01')",
                groupScope, catalog.merchant.id());
        var openedAt = java.time.LocalDateTime.now();
        var release = new CountDownLatch(1);
        try {
            assertEquals(1, sources.reserveGroupSeat(group, openedAt));
            assertEquals(1, sources.finishGroupSeat(group, 1, openedAt, openedAt.plusSeconds(60)));
            var deadline = sources.group(group).expiresAt();
            assertEquals(1, sources.reserveGroupSeat(group, deadline.minusSeconds(5)));
            var businessNow = new AtomicReference<>(deadline.minusSeconds(5));
            var locked = new CountDownLatch(1);
            var attempted = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                    sources.lockGroup(group);
                    locked.countDown();
                    assertTrue(assertDoesNotThrow(() -> release.await(10, TimeUnit.SECONDS)));
                }));
                try {
                    assertTrue(locked.await(10, TimeUnit.SECONDS));
                    var payer = executor.submit(() -> transactions.executeWithoutResult(status -> {
                        attempted.countDown();
                        sources.lockGroup(group);
                        var afterLock = businessNow.get();
                        assertEquals(0, sources.finishGroupSeat(group, 1, afterLock, afterLock.plusSeconds(60)));
                        assertEquals(1, groups.fail(group, afterLock));
                    }));
                    assertTrue(attempted.await(10, TimeUnit.SECONDS));
                    businessNow.set(deadline);
                    release.countDown();
                    holder.get(10, TimeUnit.SECONDS);
                    payer.get(10, TimeUnit.SECONDS);
                } finally {
                    release.countDown();
                }
            }
            assertEquals("FAILED", sources.group(group).status());
            assertEquals(deadline, sources.group(group).expiresAt());
        } finally {
            release.countDown();
            jdbc.update("DELETE FROM marketing_groups WHERE id=?", group);
            jdbc.update("DELETE FROM marketing_activity_skus WHERE id=?", groupScope);
            jdbc.update("DELETE FROM marketing_activities WHERE id=?", activity);
            catalog.close();
        }
    }

    @Test
    @Transactional
    void flashAndPointsCreateOneRootPerKeyWithoutReservingPoolEarly() {
        var catalog = new CatalogFixture(jdbc);
        var buyer = new TestAccounts(jdbc).create("USER", "Shop123!");
        long product = catalog.product("专单商品", "ON_SALE", "20.00", 5);
        long sku = catalog.sku(product);
        long flash = scope(catalog, activity(catalog, "FLASH"), sku, "SALE", "10.00", null, null);
        var first = settlement.prepareSource(buyer.id(), "FLASH", flash, 2, null, null, "flash-1");
        assertEquals(new BigDecimal("20.00"), first.cashTotal());
        assertEquals(first, settlement.prepareSource(buyer.id(), "FLASH", flash, 2, null, null, "flash-1"));
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_participations WHERE id=?", first.participationId()));
        assertEquals(0, count("SELECT COUNT(*) FROM marketing_stock_pools WHERE activity_sku_id=?", flash));
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(BusinessException.class,
                () -> settlement.prepareSource(buyer.id(), "FLASH", flash, 1, null, null, "flash-1"))
                .getData() instanceof Map<?, ?> data ? data.get("reason") : null);
        long points = scope(catalog, activity(catalog, "POINTS"), sku, "REDEEM", null, 500L, null);
        var redemption = settlement.prepareSource(buyer.id(), "POINTS", points, 2, null, null, "points-1");
        assertEquals(1000, redemption.pointUnits());
        assertEquals(0, redemption.cashTotal().signum());
        assertEquals(0, count("SELECT COUNT(*) FROM point_redemptions WHERE participation_id=?",
                redemption.participationId()));
        assertEquals("ACTIVITY_SOURCE_INVALID", assertThrows(BusinessException.class,
                () -> settlement.prepareSource(buyer.id(), "FLASH", points, 1, null, null, "wrong-type"))
                .getData() instanceof Map<?, ?> data ? data.get("reason") : null);
    }

    @Test
    @Transactional
    void groupRootIsStableAndCannotBeJoinedBeforeLeaderPayment() {
        var catalog = new CatalogFixture(jdbc);
        var firstUser = new TestAccounts(jdbc).create("USER", "Shop123!");
        var secondUser = new TestAccounts(jdbc).create("USER", "Shop123!");
        long product = catalog.product("拼团商品", "ON_SALE", "20.00", 5);
        long groupScope = scope(catalog, activity(catalog, "GROUP"), catalog.sku(product),
                "SALE", "9.00", null, 2);
        var leader = settlement.prepareSource(firstUser.id(), "GROUP", groupScope, 1, null, null, "open-1");
        assertNotNull(leader.groupId());
        assertEquals(leader, settlement.prepareSource(firstUser.id(), "GROUP", groupScope, 1,
                null, null, "open-1"));
        assertEquals(0, count("SELECT reserved_members FROM marketing_groups WHERE id=?", leader.groupId()));
        assertEquals("GROUP_NOT_READY", assertThrows(BusinessException.class,
                () -> settlement.prepareSource(secondUser.id(), "GROUP", groupScope, 1,
                        leader.groupId(), null, "join-1")).getData() instanceof Map<?, ?> data
                        ? data.get("reason") : null);
        assertEquals(java.time.LocalDateTime.of(2035, 12, 1, 0, 0),
                sources.lockGroup(leader.groupId()).expiresAt());
        var openedAt = java.time.LocalDateTime.now();
        assertEquals(1, sources.reserveGroupSeat(leader.groupId(), openedAt));
        assertEquals(1, sources.finishGroupSeat(leader.groupId(), 1, openedAt,
                openedAt.plusSeconds(3600)));
        var group = sources.lockGroup(leader.groupId());
        assertEquals("FORMING", group.status(), group.toString());
        assertNotNull(group.openedAt(), group.toString());
        assertTrue(group.expiresAt().isAfter(openedAt.plusSeconds(3599)), group.toString());
        assertTrue(group.expiresAt().isBefore(openedAt.plusSeconds(3601)), group.toString());
        var joined = settlement.prepareSource(secondUser.id(), "GROUP", groupScope, 1,
                leader.groupId(), null, "join-1");
        assertEquals(leader.groupId(), joined.groupId());
        assertNotEquals(leader.participationId(), joined.participationId());
        assertEquals(2, count("SELECT COUNT(*) FROM marketing_participations WHERE group_id=?", leader.groupId()));
        assertEquals(1, sources.reserveGroupSeat(leader.groupId(), openedAt.plusSeconds(1)));
        assertEquals(1, sources.finishGroupSeat(leader.groupId(), 1, openedAt.plusSeconds(1),
                openedAt.plusSeconds(3601)));
        assertEquals("SUCCEEDED", sources.lockGroup(leader.groupId()).status());
        assertNotNull(jdbc.queryForObject("SELECT succeeded_at FROM marketing_groups WHERE id=?",
                java.time.LocalDateTime.class, leader.groupId()));
    }

    @Test
    @Transactional
    void bargainTransferReusesQualifiedParticipation() {
        var catalog = new CatalogFixture(jdbc);
        var buyer = new TestAccounts(jdbc).create("USER", "Shop123!");
        long product = catalog.product("砍价商品", "ON_SALE", "100.00", 5);
        long activity = activity(catalog, "BARGAIN");
        long scope = catalog.insert("""
                INSERT INTO marketing_activity_skus(activity_id,sku_id,scope_role,bargain_start_price,
                  bargain_target_price,required_helpers,participation_seconds)
                VALUES(?,?,'SALE',100,80,2,3600)
                """, activity, catalog.sku(product));
        long participation = catalog.insert("""
                INSERT INTO marketing_participations(activity_sku_id,user_id,request_key,request_hash,
                  quantity,status,expires_at)
                VALUES(?,?,'bargain-start',UNHEX(SHA2('start',256)),1,'QUALIFIED','2035-12-01')
                """, scope, buyer.id());
        long bargain = catalog.insert("""
                INSERT INTO marketing_bargains(participation_id,activity_sku_id,user_id,start_amount,target_amount,
                  current_amount,required_helpers,help_count,status,help_expires_at,purchase_expires_at)
                VALUES(?,?,?,100,80,80,2,2,'QUALIFIED','2035-12-01','2035-12-01')
                """, participation, scope, buyer.id());
        var source = settlement.prepareSource(buyer.id(), "BARGAIN", scope, 1,
                null, bargain, "bargain-checkout");
        assertEquals(participation, source.participationId());
        assertEquals(new BigDecimal("80.00"), source.cashTotal());
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_participations WHERE activity_sku_id=?", scope));
    }

    @Test
    @Transactional
    void fullyRefundedLateGroupParticipationClosesItsResponsibility() {
        var catalog = new CatalogFixture(jdbc);
        var buyer = new TestAccounts(jdbc).create("USER", "Shop123!");
        long product = catalog.product("失败团退款商品", "ON_SALE", "20.00", 5);
        long groupScope = scope(catalog, activity(catalog, "GROUP"), catalog.sku(product),
                "SALE", "9.00", null, 2);
        var source = settlement.prepareSource(buyer.id(), "GROUP", groupScope, 1,
                null, null, "late-group-refund");
        long participation = source.participationId();
        jdbc.update("UPDATE marketing_groups SET status='FAILED' WHERE id=?", source.groupId());
        jdbc.update("UPDATE marketing_participations SET status='REFUND_PENDING' WHERE id=?", participation);
        jdbc.update("INSERT INTO marketing_stock_pools(activity_sku_id,allocated_qty,consumed_qty) VALUES(?,1,1)",
                groupScope);
        stock.refund(groupScope, participation, 1, "ACTIVITY:REFUND:late-group:" + participation);
        stock.refund(groupScope, participation, 1, "ACTIVITY:REFUND:late-group:" + participation);
        assertEquals(1, count("SELECT available_qty FROM marketing_stock_pools WHERE activity_sku_id=?", groupScope));
        assertEquals(0, count("SELECT consumed_qty FROM marketing_stock_pools WHERE activity_sku_id=?", groupScope));
        assertEquals(1, count("SELECT COUNT(*) FROM marketing_stock_entries WHERE participation_id=?", participation));
        assertEquals(1, sources.refundedParticipation(participation));
        assertEquals("REFUNDED", jdbc.queryForObject(
                "SELECT status FROM marketing_participations WHERE id=?", String.class, participation));
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT status FROM marketing_groups WHERE id=?", String.class, source.groupId()));
    }

    @Test
    @Transactional
    void expiredGroupFailsOnceAndUnpaidSeatCanStillBeReleased() {
        var catalog = new CatalogFixture(jdbc);
        var buyer = new TestAccounts(jdbc).create("USER", "Shop123!");
        long product = catalog.product("截止拼团商品", "ON_SALE", "20.00", 5);
        long groupScope = scope(catalog, activity(catalog, "GROUP"), catalog.sku(product),
                "SALE", "9.00", null, 2);
        var source = settlement.prepareSource(buyer.id(), "GROUP", groupScope, 1,
                null, null, "expired-group");
        jdbc.update("UPDATE marketing_groups SET reserved_members=1,opened_at=NOW(3)-INTERVAL 2 HOUR,"
                + "expires_at=NOW(3)-INTERVAL 1 HOUR WHERE id=?", source.groupId());
        assertTrue(groupExpiry.due().contains(source.groupId()));
        groupExpiry.expire(source.groupId());
        groupExpiry.expire(source.groupId());
        assertEquals("FAILED", sources.lockGroup(source.groupId()).status());
        assertEquals(1, count("SELECT version FROM marketing_groups WHERE id=?", source.groupId()));
        var now = java.time.LocalDateTime.now();
        assertEquals(0, sources.finishGroupSeat(source.groupId(), 1, now, now));
        assertEquals(1, sources.finishGroupSeat(source.groupId(), 0, now, now));
        assertEquals("FAILED", sources.lockGroup(source.groupId()).status());
        assertEquals(0, count("SELECT reserved_members FROM marketing_groups WHERE id=?", source.groupId()));
    }
}
