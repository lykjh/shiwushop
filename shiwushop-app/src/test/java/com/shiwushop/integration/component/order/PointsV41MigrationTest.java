package com.shiwushop.integration.component.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointsV41MigrationTest {
    private static final String EMPTY_URL = "jdbc:mysql://127.0.0.1:24306/shiwushop_trade_dev4_20261105b"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    private static final String URL = "jdbc:mysql://127.0.0.1:24306/shiwushop_trade_migration_test4_20261106"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";

    @Test
    void migratesAssignedEmptyDatabaseThroughV41() throws Exception {
        String user = Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME"));
        String password = Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(EMPTY_URL, user, password)
                .locations("classpath:db/migration").load();
        flyway.migrate();
        assertEquals("41", flyway.info().current().getVersion().getVersion());
        try (Connection db = DriverManager.getConnection(EMPTY_URL, user, password)) {
            assertTrue(count(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")
                    >= 80);
            assertEquals(1, count(db, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='checkout_requests' "
                    + "AND column_name='idempotency_key' AND collation_name='utf8mb4_0900_bin'"));
        }
    }

    @Test
    void upgradesV40FactsAndEnforcesPointsWithoutChangingCashHistory() throws Exception {
        String user = Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME"));
        String password = Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        Flyway flyway = Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").load();
        String current = flyway.info().current().getVersion().getVersion();
        assertTrue(current.equals("40") || current.equals("41"));
        flyway.migrate();
        assertEquals("41", flyway.info().current().getVersion().getVersion());

        try (Connection db = DriverManager.getConnection(URL, user, password)) {
            assertEquals(3, count(db, "SELECT COUNT(*) FROM orders WHERE id IN (9301,9302,9303)"));
            assertEquals(7, count(db, "SELECT SUM(quantity) FROM order_items WHERE id IN (9401,9402,9403)"));
            db.setAutoCommit(false);
            try {
                execute(db, "INSERT INTO orders(id,user_id,shop_id,total_amount,status,paid_at,"
                        + "consignee_ciphertext,phone_ciphertext,province,city,district,detail_ciphertext,"
                        + "shipping_key_version,business_type,settlement_kind,original_amount,discount_amount,"
                        + "pricing_version) VALUES(9801,9101,9202,0,'PAID','2026-01-06',X'01',X'01',"
                        + "'P','C','D',X'01',1,'POINTS','POINTS',0,0,1)");
                execute(db, "INSERT INTO order_items(id,order_id,sku_id,product_name,sku_spec,quantity,"
                        + "unit_price,item_kind,original_unit_price,discount_amount,payable_amount) "
                        + "VALUES(9802,9801,9204,'legacy product','base',1,0,'REDEEM',10,0,0)");
                execute(db, "INSERT INTO after_sale_cases(id,order_id,type,status,case_origin,reason,"
                        + "refund_amount,requested_at,finished_at) VALUES(9803,9801,'REFUND_ONLY','REFUNDED',"
                        + "'POINTS','points return',0,'2026-01-07','2026-01-07')");
                rejected(db, "ck_orders_1", "INSERT INTO orders(id,user_id,shop_id,total_amount,status,"
                        + "pay_expires_at,consignee_ciphertext,phone_ciphertext,province,city,district,"
                        + "detail_ciphertext,shipping_key_version,settlement_kind) VALUES(9804,9101,9202,0,"
                        + "'UNPAID','2026-01-09',X'01',X'01','P','C','D',X'01',1,'CASH')");
                rejected(db, "ck_orders_1", "INSERT INTO orders(id,user_id,shop_id,total_amount,status,"
                        + "paid_at,consignee_ciphertext,phone_ciphertext,province,city,district,"
                        + "detail_ciphertext,shipping_key_version,settlement_kind) VALUES(9804,9101,9202,1,"
                        + "'PAID','2026-01-06',X'01',X'01','P','C','D',X'01',1,'POINTS')");
                rejected(db, "ck_order_items_2", "INSERT INTO order_items(id,order_id,sku_id,product_name,"
                        + "sku_spec,quantity,unit_price,item_kind,original_unit_price,discount_amount,"
                        + "payable_amount) VALUES(9805,9801,9204,'bad','base',1,1,'REDEEM',10,0,0)");
                rejected(db, "ck_order_items_cash_snapshot", "INSERT INTO order_items(id,order_id,sku_id,"
                        + "product_name,sku_spec,quantity,unit_price,item_kind,original_unit_price,"
                        + "discount_amount,payable_amount) VALUES(9805,9801,9204,'bad','base',1,0,"
                        + "'REDEEM',10,0,1)");
                rejected(db, "ck_after_sale_cases_3", "INSERT INTO after_sale_cases(id,order_id,type,status,"
                        + "case_origin,reason,refund_amount,requested_at) VALUES(9806,9801,'REFUND_ONLY',"
                        + "'REQUESTED','CUSTOMER','invalid',0,'2026-01-07')");
                execute(db, "INSERT INTO checkout_requests(user_id,idempotency_key,request_hash,status) "
                        + "VALUES(9101,'V41-Aa',UNHEX(REPEAT('a',64)),'PROCESSING'),"
                        + "(9101,'V41-aa',UNHEX(REPEAT('b',64)),'PROCESSING')");
                assertEquals(2, count(db, "SELECT COUNT(*) FROM checkout_requests WHERE user_id=9101 "
                        + "AND idempotency_key IN ('V41-Aa','V41-aa')"));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM checkout_requests WHERE user_id=9101 "
                        + "AND idempotency_key='V41-Aa'"));
                rejected(db, "uk_checkout_requests_user_id_idempotency_key",
                        "INSERT INTO checkout_requests(user_id,idempotency_key,request_hash,status) "
                                + "VALUES(9101,'V41-Aa',UNHEX(REPEAT('c',64)),'PROCESSING')");
            } finally {
                db.rollback();
            }
        }
    }

    private void rejected(Connection db, String constraint, String sql) throws Exception {
        Savepoint before = db.setSavepoint();
        SQLException error = assertThrows(SQLException.class, () -> execute(db, sql));
        assertTrue(error.getMessage().contains(constraint), error.getMessage());
        db.rollback(before);
    }

    private void execute(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private int count(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
