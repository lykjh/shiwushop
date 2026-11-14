package com.shiwushop.integration.member;

import com.shiwushop.trade.payment.LocalSimulationGateway;
import com.shiwushop.support.CommerceFixture;
import com.shiwushop.support.PaymentHttpFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.SQLException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=", "shop.member.enabled=true", "shop.member.reward-start-at=2026-11-01T00:00:00",
        "shop.member.scan-delay-ms=86400000", "shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Import(MemberRewardHttpTest.TimeConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MemberSchemaContractTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemberRewardHttpTest.TestClock clock;
    @MockitoBean(name = "localSimulationGateway")
    LocalSimulationGateway gateway;
    CommerceFixture fixture;
    long order;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:24306/shiwushop_member_"));
        }
        clock.at(LocalDateTime.of(2026, 11, 4, 15, 0));
        PaymentHttpFixture.configure(gateway);
        fixture = new CommerceFixture(jdbc, "http://127.0.0.1:" + port);
        order = PaymentHttpFixture.paidOrder(fixture, jdbc);
    }

    @AfterEach
    void closeSessions() {
        if (fixture != null) {
            fixture.client.close();
            fixture.merchantClient.close();
        }
    }

    private void rejected(int errorCode, String sql, Object... args) {
        var failure = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(sql, args));
        var sqlError = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(errorCode, sqlError.getErrorCode(), sqlError.getMessage());
    }

    @Test
    void levelThresholdCannotBecomeNegative() {
        rejected(3819, "UPDATE membership_levels SET min_growth_amount=-1 WHERE level_code='ORDINARY'");
    }

    @Test
    void accountCannotGoNegativeOrLoseItsUserReference() {
        rejected(3819, "UPDATE member_accounts SET available_point_units=-1 WHERE user_id=?", fixture.buyer.id());
        rejected(1452, "UPDATE member_accounts SET user_id=? WHERE user_id=?", Long.MAX_VALUE, fixture.buyer.id());
        assertEquals(1990,
                jdbc.queryForObject("SELECT available_point_units FROM member_accounts WHERE user_id=?", Long.class,
                        fixture.buyer.id()));
    }

    @Test
    void negativeGrowthCannotLackSourceOrManualEvidence() {
        rejected(3819, """
                INSERT INTO member_growth_entries(user_id,event_kind,operation_key,delta_amount,balance_after,old_level_code,new_level_code)
                VALUES(?,'REFUND',?,-1,0,'ORDINARY','ORDINARY')
                """, fixture.buyer.id(), "SCHEMA_REFUND:" + order);
        rejected(3819, """
                INSERT INTO member_growth_entries(user_id,event_kind,operation_key,delta_amount,balance_after,old_level_code,new_level_code)
                VALUES(?,'ADJUST',?,1,1,'ORDINARY','ORDINARY')
                """, fixture.buyer.id(), "SCHEMA_ADJUST:" + order);
    }

    @Test
    void levelHistoryCannotReferToAnUnknownTier() {
        rejected(1452, "UPDATE member_level_changes SET to_level_code='UNKNOWN' WHERE user_id=?", fixture.buyer.id());
    }

    @Test
    void rewardKindsCannotShareCreditAndExclusionNeedsReason() {
        rejected(3819,
                "UPDATE member_reward_tasks SET credited_growth_amount=1 WHERE user_id=? AND reward_kind='POINTS'",
                fixture.buyer.id());
        rejected(3819,
                "UPDATE member_reward_tasks SET status='EXCLUDED',exclusion_reason=NULL WHERE user_id=? AND reward_kind='GROWTH'",
                fixture.buyer.id());
        rejected(1062, """
                INSERT INTO member_reward_tasks(user_id,order_item_id,reward_kind,status,next_attempt_at,rule_version)
                SELECT user_id,order_item_id,reward_kind,'WAIT_PAYMENT',next_attempt_at,rule_version FROM member_reward_tasks
                WHERE user_id=? AND reward_kind='POINTS'
                """, fixture.buyer.id());
    }

    @Test
    void pointLotConservationCannotBeBroken() {
        rejected(3819, "UPDATE member_point_lots SET available_units=available_units+1 WHERE user_id=?",
                fixture.buyer.id());
        assertEquals(1990,
                jdbc.queryForObject("SELECT available_units FROM member_point_lots WHERE user_id=?", Long.class,
                        fixture.buyer.id()));
    }

    @Test
    void pointEntryRequiresNonnegativeBalanceAndManualAuditFields() {
        rejected(3819, "UPDATE member_point_entries SET debt_after=-1 WHERE user_id=?", fixture.buyer.id());
        rejected(3819, "UPDATE member_point_entries SET event_kind='ADJUST' WHERE user_id=?", fixture.buyer.id());
    }

    @Test
    void allocationsMustBePositiveAndReferenceRealLots() {
        rejected(3819,
                "UPDATE member_point_allocations SET units=0 WHERE entry_id IN (SELECT id FROM member_point_entries WHERE user_id=?)",
                fixture.buyer.id());
        rejected(1452,
                "UPDATE member_point_allocations SET lot_id=? WHERE entry_id IN (SELECT id FROM member_point_entries WHERE user_id=?)",
                Long.MAX_VALUE, fixture.buyer.id());
    }
}
