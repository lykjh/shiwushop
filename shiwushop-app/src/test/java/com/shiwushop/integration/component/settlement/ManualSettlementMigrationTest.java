package com.shiwushop.integration.component.settlement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualSettlementMigrationTest {
    private static final String PREFIX = "jdbc:mysql://127.0.0.1:24306/";
    private static final String EMPTY = "shiwushop_trade_empty_test4_20261107";
    private static final String EXISTING = "shiwushop_trade_migration_test4_20261106";
    private static final String OPTIONS = "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";

    @Test
    void migratesAssignedEmptyDatabaseFromV1ToV42() throws Exception {
        var flyway = flyway(EMPTY);
        if (flyway.info().current() == null) {
            try (var db = DriverManager.getConnection(PREFIX + EMPTY + OPTIONS, user(), password())) {
                assertEquals(0, count(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
            }
            flyway.migrate();
        }
        assertEquals("42", flyway.info().current().getVersion().getVersion());
        try (var db = DriverManager.getConnection(PREFIX + EMPTY + OPTIONS, user(), password())) {
            assertEquals(3, count(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('manual_settlements','manual_settlement_lines','manual_settlement_adjustments')"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='manual_settlement_lines' AND index_name='uk_manual_settlement_active_order'"));
        }
    }

    @Test
    void upgradesV41WithExistingTradeFactsUnchanged() throws Exception {
        var flyway = flyway(EXISTING);
        assertTrue(List.of("41", "42").contains(flyway.info().current().getVersion().getVersion()));
        try (var db = DriverManager.getConnection(PREFIX + EXISTING + OPTIONS, user(), password())) {
            assertEquals(3, count(db, "SELECT COUNT(*) FROM orders"));
            assertEquals(3, count(db, "SELECT COUNT(*) FROM after_sale_cases"));
            assertEquals(2, count(db, "SELECT COUNT(*) FROM users"));
        }
        if (flyway.info().current().getVersion().getVersion().equals("41"))
            flyway.migrate();
        assertEquals("42", flyway.info().current().getVersion().getVersion());
        try (var db = DriverManager.getConnection(PREFIX + EXISTING + OPTIONS, user(), password())) {
            assertEquals(3, count(db, "SELECT COUNT(*) FROM orders"));
            assertEquals(3, count(db, "SELECT COUNT(*) FROM after_sale_cases"));
            assertEquals(2, count(db, "SELECT COUNT(*) FROM users"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM manual_settlements"));
        }
    }

    private static Flyway flyway(String database) {
        return Flyway.configure().dataSource(PREFIX + database + OPTIONS, user(), password())
                .locations("classpath:db/migration").load();
    }

    private static String user() {
        return Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME"));
    }

    private static String password() {
        return Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD"));
    }

    private static int count(java.sql.Connection db, String sql) throws Exception {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
