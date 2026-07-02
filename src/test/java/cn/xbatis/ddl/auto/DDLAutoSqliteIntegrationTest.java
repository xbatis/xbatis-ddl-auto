package cn.xbatis.ddl.auto;

import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.IndexDirection;
import cn.xbatis.db.annotations.ColumnDefinition;
import cn.xbatis.db.annotations.Index;
import cn.xbatis.db.annotations.IndexField;
import cn.xbatis.db.annotations.Indexs;
import cn.xbatis.db.annotations.Table;
import cn.xbatis.db.annotations.TableDefinition;
import cn.xbatis.db.annotations.TableId;
import db.sql.api.DbType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class DDLAutoSqliteIntegrationTest {

    private static final String TEST_TABLE = "auto_sqlite_integration_user";

    @Test
    void sqliteShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLTestPrinter.ddl(DbType.SQLITE)
                    .builder(new DefaultDDLBuilder())
                    .add(SqliteIntegrationUserV1.class)
                    .execute(connection);

            assertTrue(tableExists(connection, TEST_TABLE));
            assertTrue(columnExists(connection, TEST_TABLE, "id"));
            assertTrue(columnExists(connection, TEST_TABLE, "username"));
            assertTrue(columnExists(connection, TEST_TABLE, "created_at"));
            assertTrue(indexExists(connection, TEST_TABLE, "idx_sqlite_integration_username"));
            assertFalse(columnExists(connection, TEST_TABLE, "email"));
            assertFalse(indexExists(connection, TEST_TABLE, "uk_auto_sqlite_integration_user_email"));
            assertDefaultValueContains(connection, TEST_TABLE, "created_at", "CURRENT_TIMESTAMP");

            assertFalse(columnExists(connection, TEST_TABLE, "email"));

            List<String> updateExecutedSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.SQLITE, updateExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .mode(Mode.UPDATE)
                    .add(SqliteIntegrationUserV2.class)
                    .execute(connection);

            assertTrue(updateExecutedSqlList.contains("ALTER TABLE " + TEST_TABLE + " ADD COLUMN email VARCHAR(128);"));
            assertTrue(updateExecutedSqlList.contains("CREATE UNIQUE INDEX uk_auto_sqlite_integration_user_email ON "
                    + TEST_TABLE + " (email);"));
            assertTrue(updateExecutedSqlList.contains("CREATE INDEX idx_sqlite_integration_username_created_at ON "
                    + TEST_TABLE + " (username ASC, created_at DESC);"));
            assertTrue(columnExists(connection, TEST_TABLE, "email"));
            assertTrue(indexExists(connection, TEST_TABLE, "uk_auto_sqlite_integration_user_email"));
            assertTrue(indexExists(connection, TEST_TABLE, "idx_sqlite_integration_username_created_at"));
            List<String> verifyExecutedSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.SQLITE, verifyExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .mode(Mode.UPDATE)
                    .add(SqliteIntegrationUserV2.class)
                    .execute(connection);
            assertTrue(verifyExecutedSqlList.isEmpty(), "Expected no SQLite DDL after update already executed: " + verifyExecutedSqlList);
        }
    }

    @Test
    void sqliteShouldAddMultipleMissingColumnsAsSeparateAlter() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiColumnAddColumnFlow(
                    DbType.SQLITE,
                    connection,
                    "ALTER TABLE auto_multi_column_add_user ADD COLUMN age INTEGER;",
                    "ALTER TABLE auto_multi_column_add_user ADD COLUMN email VARCHAR(128);"
            );
        }
    }

    @Test
    void sqliteShouldCreateBooleanDefaultValueColumns() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertBooleanDefaultValueFlow(
                    DbType.SQLITE,
                    connection,
                    "BOOLEAN",
                    "FALSE",
                    "TRUE"
            );
        }
    }

    @Test
    void sqliteShouldCreateDateTimeDefaultValueColumns() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateTimeDefaultValueFlow(
                    DbType.SQLITE,
                    connection,
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                    "event_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP"
            );
        }
    }

    @Test
    void sqliteShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertIntLongAutoAndManualIdFlow(
                    DbType.SQLITE,
                    connection,
                    "id INTEGER PRIMARY KEY AUTOINCREMENT",
                    "id INTEGER PRIMARY KEY AUTOINCREMENT",
                    "id INTEGER NOT NULL PRIMARY KEY",
                    "id BIGINT NOT NULL PRIMARY KEY"
            );
        }
    }

    @Test
    void sqliteShouldCreateTableDefinition() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertTableDefinitionFlow(
                    DbType.SQLITE,
                    connection,
                    SqliteTableDefinitionIntegrationUser.class,
                    "auto_sqlite_table_definition_user",
                    "WITHOUT ROWID",
                    null
            );
        }
    }

    @Test
    void sqliteShouldCreateMultiplePhysicalTablesWithIndexes() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiTableIndexFlow(DbType.SQLITE, connection);
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            return hasRows(statement);
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void assertDefaultValueContains(Connection connection, String tableName, String columnName, String expectedDefault) throws SQLException {
        String defaultValue = columnDefaultValue(connection, tableName, columnName);
        assertNotNull(defaultValue);
        assertTrue(defaultValue.toUpperCase(Locale.ROOT).contains(expectedDefault));
    }

    private static String columnDefaultValue(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return resultSet.getString("dflt_value");
                }
            }
            return null;
        }
    }

    private static boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA index_list(" + quoteIdentifier(tableName) + ")")) {
            while (resultSet.next()) {
                if (indexName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean hasRows(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Table(TEST_TABLE)
    @Index(name = "idx_sqlite_integration_username", fields = @IndexField(name = "username"))
    static class SqliteIntegrationUserV1 {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        @ColumnDefinition(defaultValue = "CURRENT_TIMESTAMP")
        private LocalDateTime createdAt;
    }

    @Table(TEST_TABLE)
    @Indexs({
            @Index(name = "idx_sqlite_integration_username", fields = @IndexField(name = "username")),
            @Index(name = "idx_sqlite_integration_username_created_at", fields = {
                    @IndexField(name = "username", direction = IndexDirection.ASC),
                    @IndexField(name = "createdAt", direction = IndexDirection.DESC)
            })
    })
    static class SqliteIntegrationUserV2 {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        @ColumnDefinition(defaultValue = "CURRENT_TIMESTAMP")
        private LocalDateTime createdAt;

        @ColumnDefinition(length = 128, unique = true)
        private String email;
    }

    @Table("auto_sqlite_table_definition_user")
    @TableDefinition(definition = "WITHOUT ROWID")
    static class SqliteTableDefinitionIntegrationUser {

        @TableId(value = IdAutoType.NONE)
        private Integer id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
