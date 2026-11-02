package com.shiwushop.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import java.sql.DriverManager;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅在测试 MySQL 的固定专用 schema 演练建库与校验失败，不接触现有商城 schema。 */
class FlywayMigrationIT {
    static final String SCHEMA = "shiwushop_migration_acceptance";
    static final String URL = "jdbc:mysql://127.0.0.1:13306/";
    @BeforeEach void createEmptySchema() throws Exception {
        try(var connection = DriverManager.getConnection(URL, "root", "test-root"); var sql = connection.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            sql.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }
    @AfterEach void removeOwnSchema() throws Exception {
        try(var connection = DriverManager.getConnection(URL, "root", "test-root"); var sql = connection.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }
    Flyway migrations() {return Flyway.configure().dataSource(URL + SCHEMA, "root", "test-root").locations("classpath:db/migration").load();}
    @Test void emptySchemaMigratesCompletelyAndRestartIsIdempotent() {
        var first = migrations().migrate(); assertTrue(first.migrationsExecuted > 0);
        assertTrue(migrations().validateWithResult().validationSuccessful);
        assertEquals(0, migrations().migrate().migrationsExecuted);
        assertEquals(0, migrations().info().pending().length);
    }
    @Test void alteredRecordedChecksumStopsMigration() throws Exception {
        migrations().migrate();
        try(var connection = DriverManager.getConnection(URL + SCHEMA, "root", "test-root"); var sql = connection.createStatement()) {
            assertEquals(1, sql.executeUpdate("UPDATE flyway_schema_history SET checksum=123 WHERE installed_rank=1"));
        }
        assertThrows(FlywayException.class, () -> migrations().migrate());
    }
    @Test void invalidMigrationNeverReportsSuccessAndBlocksTheNextRun() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "broken-migration-");
        Path script = directory.resolve("V1__broken.sql");
        Files.writeString(script, "CREATE TABLE first_step(id BIGINT PRIMARY KEY);\nTHIS IS INVALID SQL;\n");
        var broken = Flyway.configure().dataSource(URL + SCHEMA, "root", "test-root").locations("filesystem:" + directory.toAbsolutePath()).load();
        try {
            assertThrows(FlywayException.class, broken::migrate);
            assertFalse(broken.validateWithResult().validationSuccessful);
            assertThrows(FlywayException.class, broken::migrate);
        } finally {Files.deleteIfExists(script); Files.deleteIfExists(directory);}
    }
}
