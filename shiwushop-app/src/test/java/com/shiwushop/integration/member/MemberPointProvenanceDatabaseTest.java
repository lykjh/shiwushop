package com.shiwushop.integration.member;

import com.shiwushop.member.mapper.MemberMapper;
import com.shiwushop.member.mapper.MemberPointRedemptionMapper;
import com.shiwushop.member.service.MemberAccountService;
import com.shiwushop.member.service.impl.MemberPointLedger;
import com.shiwushop.member.service.impl.MemberPointRedemptionLedger;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberPointProvenanceDatabaseTest {
    private static final String DB = "shiwushop_member_test3_20261105b";
    private static final String URL = "jdbc:mysql://127.0.0.1:24306/" + DB
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"), ZoneOffset.ofHours(8));

    @Test
    void v38RollsBackAndSerializesDuplicateCancellationOnRealMysql() throws Exception {
        String user = System.getenv("MK06_DB_USER");
        String password = System.getenv("MK06_DB_PASSWORD");
        assertNotNull(user, "仅在开发三专库凭据已注入时运行");
        assertNotNull(password, "仅在开发三专库凭据已注入时运行");
        var dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", URL, user, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("38")).load().migrate();
        var configuration = new Configuration(new Environment("mk06", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MemberMapper.class);
        configuration.addMapper(MemberPointRedemptionMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        long buyer = 0, seller = 0, shop = 0, order = 0;
        String suffix = UUID.randomUUID().toString();
        try (Connection db = DriverManager.getConnection(URL, user, password)) {
            db.setAutoCommit(false);
            try {
                buyer = insert(db, "INSERT INTO users(nickname) VALUES('mk06-buyer')");
                seller = insert(db, "INSERT INTO users(nickname) VALUES('mk06-seller')");
                shop = insert(db, "INSERT INTO shops(owner_user_id,name) VALUES(" + seller + ",'mk06-" + suffix + "')");
                order = insert(db, "INSERT INTO orders(user_id,shop_id,total_amount,status,pay_expires_at,consignee_ciphertext,phone_ciphertext,province,city,district,detail_ciphertext,shipping_key_version) VALUES("
                        + buyer + "," + shop + ",1,'UNPAID','2026-11-07 10:00:00',X'00',X'00','p','c','d',X'00',1)");
                execute(db, "INSERT INTO member_accounts(user_id,level_code,available_point_units,point_debt_units,joined_at,reward_start_at) VALUES("
                        + buyer + ",'ORDINARY',0,600,'2026-11-01 00:00:00','2026-11-01 00:00:00')");
                long revoked = insert(db, "INSERT INTO member_point_lots(user_id,source_key,issued_units,reversed_units,earned_at,expires_at,status) VALUES("
                        + buyer + ",'MK06_A_" + suffix + "',1000,1000,'2026-11-01 00:00:00','2026-11-07 10:00:00','EXHAUSTED')");
                long later = insert(db, "INSERT INTO member_point_lots(user_id,source_key,issued_units,debt_offset_units,earned_at,expires_at,status) VALUES("
                        + buyer + ",'MK06_B_" + suffix + "',400,400,'2026-11-02 00:00:00','2026-11-08 10:00:00','EXHAUSTED')");
                long spend = insert(db, "INSERT INTO member_point_entries(user_id,event_kind,operation_key,order_id,available_delta,available_after,held_after,debt_after) VALUES("
                        + buyer + ",'SPEND','POINTS:SPEND:" + order + "'," + order + ",-1000,0,0,0)");
                long spendAllocation = insert(db, "INSERT INTO member_point_allocations(entry_id,lot_id,allocation_kind,units) VALUES("
                        + spend + "," + revoked + ",'SPEND',1000)");
                long refund = insert(db, "INSERT INTO member_point_entries(user_id,event_kind,operation_key,order_id,debt_delta,available_after,held_after,debt_after) VALUES("
                        + buyer + ",'REFUND_REVERSE','MK06_REFUND_" + suffix + "'," + order + ",1000,0,0,1000)");
                long debt = insert(db, "INSERT INTO member_point_allocations(entry_id,lot_id,original_allocation_id,allocation_kind,allocation_ordinal,units) VALUES("
                        + refund + "," + revoked + "," + spendAllocation + ",'DEBT_CREATE',1,1000)");
                long earn = insert(db, "INSERT INTO member_point_entries(user_id,event_kind,operation_key,debt_delta,available_after,held_after,debt_after) VALUES("
                        + buyer + ",'EARN','MK06_EARN_" + suffix + "',-400,0,0,600)");
                insert(db, "INSERT INTO member_point_allocations(entry_id,lot_id,original_allocation_id,allocation_kind,allocation_ordinal,units) VALUES("
                        + earn + "," + later + "," + debt + ",'DEBT_OFFSET',1,400)");
                db.commit();
            } catch (Exception e) {
                db.rollback();
                throw e;
            }
        }
        long userId = buyer, orderId = order;
        try {
            try (SqlSession session = sessions.openSession(false)) {
                var redemption = session.getMapper(MemberPointRedemptionMapper.class);
                var debt = redemption.accountDebts(userId).getFirst();
                assertEquals(400, debt.offsetUnits());
                var offset = redemption.debtOffsets(debt.id()).getFirst();
                long reversal = insert(session.getConnection(), "INSERT INTO member_point_entries(user_id,event_kind,operation_key,debt_delta,available_after,held_after,debt_after) VALUES("
                        + buyer + ",'REFUND_REVERSE','MK06_TEST_ROLLBACK_" + suffix + "',400,0,0,1000)");
                redemption.allocateLinked(reversal, offset.lotId(), offset.id(), "REVERSE", 1, 400);
                assertEquals(0, redemption.accountDebts(userId).getFirst().offsetUnits());
                session.rollback();
            }
            try (SqlSession session = sessions.openSession(false)) {
                var ledger = ledger(session, userId);
                ledger.returnSpent(userId, orderId, null, 500, "rollback", false);
                session.rollback();
            }
            assertEquals(600, number(user, password, "SELECT point_debt_units FROM member_accounts WHERE user_id=" + buyer));
            assertEquals(0, number(user, password, "SELECT COUNT(*) FROM member_point_entries WHERE operation_key='POINTS:RETURN:"
                    + order + ":rollback'"));

            var firstWritten = new CountDownLatch(1);
            var commitFirst = new CountDownLatch(1);
            var secondStarted = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> {
                    try (SqlSession session = sessions.openSession(false)) {
                        long id = ledger(session, userId).returnSpent(userId, orderId, null, 1000, "same", false);
                        firstWritten.countDown();
                        assertTrue(commitFirst.await(10, TimeUnit.SECONDS));
                        session.commit();
                        return id;
                    }
                });
                assertTrue(firstWritten.await(10, TimeUnit.SECONDS));
                var second = pool.submit(() -> {
                    secondStarted.countDown();
                    try (SqlSession session = sessions.openSession(false)) {
                        long id = ledger(session, userId).returnSpent(userId, orderId, null, 1000, "same", false);
                        session.commit();
                        return id;
                    }
                });
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                commitFirst.countDown();
                assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            }
            assertEquals(0, number(user, password, "SELECT point_debt_units FROM member_accounts WHERE user_id=" + buyer));
            assertEquals(400, number(user, password, "SELECT available_point_units FROM member_accounts WHERE user_id=" + buyer));
            assertEquals(1, number(user, password, "SELECT COUNT(*) FROM member_point_entries WHERE operation_key='POINTS:RETURN:"
                    + order + ":same'"));
            assertEquals(400, number(user, password, "SELECT available_units FROM member_point_lots WHERE source_key='MK06_B_"
                    + suffix + "'"));
            assertEquals(0, number(user, password, "SELECT debt_offset_units FROM member_point_lots WHERE source_key='MK06_B_"
                    + suffix + "'"));
        } finally {
            try (Connection db = DriverManager.getConnection(URL, user, password)) {
                for (String kind : new String[]{"RETURN", "DEBT_REPAY", "DEBT_OFFSET", "DEBT_CREATE", "SPEND"})
                    execute(db, "DELETE a FROM member_point_allocations a JOIN member_point_entries e ON e.id=a.entry_id WHERE e.user_id="
                            + buyer + " AND a.allocation_kind='" + kind + "'");
                execute(db, "DELETE FROM member_point_entries WHERE user_id=" + buyer);
                execute(db, "DELETE FROM member_point_lots WHERE user_id=" + buyer);
                execute(db, "DELETE FROM member_accounts WHERE user_id=" + buyer);
                execute(db, "DELETE FROM orders WHERE id=" + order);
                execute(db, "DELETE FROM shops WHERE id=" + shop);
                execute(db, "DELETE FROM users WHERE id IN(" + buyer + "," + seller + ")");
            }
        }
    }

    private static MemberPointRedemptionLedger ledger(SqlSession session, long userId) {
        var mapper = session.getMapper(MemberMapper.class);
        var redemption = session.getMapper(MemberPointRedemptionMapper.class);
        var accounts = mock(MemberAccountService.class);
        when(accounts.initializeAndLock(userId)).thenAnswer(ignored -> mapper.lockAccount(userId));
        return new MemberPointRedemptionLedger(mapper, redemption, accounts,
                new MemberPointLedger(mapper, redemption), CLOCK);
    }

    private static long insert(Connection db, String sql) throws Exception {
        try (Statement statement = db.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void execute(Connection db, String sql) throws Exception {
        try (Statement statement = db.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long number(String user, String password, String sql) throws Exception {
        try (Connection db = DriverManager.getConnection(URL, user, password);
             Statement statement = db.createStatement();
             var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
