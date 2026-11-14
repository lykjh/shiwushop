package com.shiwushop.integration.component.order;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShipmentV40UpgradeTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:24306/shiwushop_trade_migration_test4_20261106"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";

    @Test
    void upgradesLegacyShipmentAndRefundFactsFromV39() throws Exception {
        String user = Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME"));
        String password = Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        try (Connection db = DriverManager.getConnection(URL, user, password)) {
            if (scalar(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()") > 0) {
                Flyway existing = Flyway.configure().dataSource(URL, user, password)
                        .locations("classpath:db/migration").load();
                assertEquals("40", existing.info().current().getVersion().getVersion());
                verifyBackfill(db);
                return;
            }
        }
        Flyway before = Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("39")).load();
        before.migrate();
        assertEquals("39", before.info().current().getVersion().getVersion());

        try (Connection db = DriverManager.getConnection(URL, user, password);
             Statement sql = db.createStatement()) {
            sql.execute("INSERT INTO users(id,nickname) VALUES (9101,'legacy buyer'),(9102,'legacy shop owner')");
            sql.execute("INSERT INTO categories(id,level,name) VALUES (9201,1,'legacy category')");
            sql.execute("INSERT INTO shops(id,owner_user_id,name) VALUES (9202,9102,'legacy shop')");
            sql.execute("INSERT INTO products(id,shop_id,category_id,name,status) VALUES "
                    + "(9203,9202,9201,'legacy product','OFF_SALE')");
            sql.execute("INSERT INTO skus(id,product_id,option_signature,price,stock) VALUES "
                    + "(9204,9203,REPEAT('a',64),10.00,0)");
            sql.execute("INSERT INTO orders(id,user_id,shop_id,total_amount,status,pay_expires_at,paid_at,"
                    + "consignee_ciphertext,phone_ciphertext,province,city,district,detail_ciphertext,shipping_key_version) VALUES "
                    + "(9301,9101,9202,30.00,'PAID','2026-01-02','2026-01-01',X'01',X'01','P','C','D',X'01',1),"
                    + "(9302,9101,9202,20.00,'PAID','2026-01-02','2026-01-01',X'01',X'01','P','C','D',X'01',1),"
                    + "(9303,9101,9202,20.00,'PAID','2026-01-02','2026-01-01',X'01',X'01','P','C','D',X'01',1)");
            sql.execute("INSERT INTO order_items(id,order_id,sku_id,product_name,sku_spec,quantity,unit_price) VALUES "
                    + "(9401,9301,9204,'legacy product','base',3,10.00),"
                    + "(9402,9302,9204,'legacy product','base',2,10.00),"
                    + "(9403,9303,9204,'legacy product','base',2,10.00)");
            sql.execute("INSERT INTO shipments(id,order_id,carrier_name,tracking_no,status,shipped_by_user_id,"
                    + "shipped_at,auto_confirm_at,confirmed_at,confirm_source) VALUES "
                    + "(9501,9301,'legacy','one','CONFIRMED',9102,'2026-01-03','2026-01-13','2026-01-04','BUYER'),"
                    + "(9502,9302,'legacy','two','SHIPPED',9102,'2026-01-03','2026-01-13',NULL,NULL),"
                    + "(9503,9303,'legacy','three','CONFIRMED',9102,'2026-01-03','2026-01-13','2026-01-04','BUYER')");
            sql.execute("INSERT INTO after_sale_cases(id,order_id,type,status,reason,refund_amount,requested_at,finished_at) VALUES "
                    + "(9601,9301,'REFUND_ONLY','REFUNDED','before ship',10.00,'2026-01-01','2026-01-02'),"
                    + "(9602,9302,'REFUND_ONLY','REFUNDED','after ship',10.00,'2026-01-04','2026-01-05'),"
                    + "(9603,9303,'REFUND_ONLY','REFUNDED','same second',10.00,'2026-01-02','2026-01-03')");
            sql.execute("INSERT INTO after_sale_items(id,case_id,order_item_id,quantity,refund_amount) VALUES "
                    + "(9701,9601,9401,1,10.00),(9702,9602,9402,1,10.00),(9703,9603,9403,1,10.00)");
        }

        Flyway after = Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("40")).load();
        after.migrate();
        assertEquals("40", after.info().current().getVersion().getVersion());
        try (Connection db = DriverManager.getConnection(URL, user, password)) {
            verifyBackfill(db);
        }
    }

    private void verifyBackfill(Connection db) throws Exception {
            assertEquals(2, scalar(db, "SELECT quantity FROM shipment_items WHERE shipment_id=9501 AND order_item_id=9401"));
            assertEquals(2, scalar(db, "SELECT quantity FROM shipment_items WHERE shipment_id=9502 AND order_item_id=9402"));
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM shipment_items WHERE shipment_id=9503"));
            assertEquals("COMPLETE", value(db, "SELECT allocation_state FROM shipments WHERE id=9501"));
            assertEquals("NEEDS_REVIEW", value(db, "SELECT allocation_state FROM shipments WHERE id=9503"));
            assertEquals("UNSHIPPED", value(db, "SELECT source_kind FROM after_sale_items WHERE id=9701"));
            assertEquals("SHIPMENT", value(db, "SELECT source_kind FROM after_sale_items WHERE id=9702"));
            assertEquals(9502, scalar(db, "SELECT source_shipment_id FROM after_sale_items WHERE id=9702"));
            assertEquals("LEGACY_UNKNOWN", value(db, "SELECT source_kind FROM after_sale_items WHERE id=9703"));
            assertEquals("2026-01-04 00:00:00", value(db, "SELECT CAST(fully_received_at AS CHAR) FROM orders WHERE id=9301"));
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM orders WHERE id IN (9302,9303) AND fully_received_at IS NOT NULL"));
    }

    private int scalar(Connection db, String query) throws Exception {
        try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private String value(Connection db, String query) throws Exception {
        try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
