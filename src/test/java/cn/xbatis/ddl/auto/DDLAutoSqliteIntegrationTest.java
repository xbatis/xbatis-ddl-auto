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

    private static final String UNIQUE_AUTO_INDEX_TABLE = "auto_sqlite_unique_autoindex_user";

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
    void sqliteShouldCreateDateDefaultValueColumns() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateDefaultValueFlow(
                    DbType.SQLITE,
                    connection,
                    "biz_date DATE DEFAULT CURRENT_DATE",
                    "today_date DATE DEFAULT CURRENT_DATE"
            );
        }
    }

    @Test
    void sqliteShouldSyncTypeLengthAndDefaultCombinations() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            // SQLite 不支持 MODIFY，类型/长度不变时同步不应生成任何 DDL
            DDLAutoExternalDatabaseIntegrationSupport.assertTypeLengthDefaultCombinationFlow(DbType.SQLITE, connection);
        }
    }

    @Test
    void sqliteShouldCreateColumnTypeMatrix() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertColumnTypeMatrixFlow(
                    DbType.SQLITE,
                    connection,
                    "short_text VARCHAR(64)",
                    "large_text VARCHAR(5000)",
                    "int_value INTEGER",
                    "long_value BIGINT",
                    "big_number BIGINT",
                    "short_value SMALLINT",
                    "byte_value TINYINT",
                    "enabled BOOLEAN",
                    "amount DECIMAL(12,4)",
                    "ratio REAL",
                    "score DOUBLE PRECISION",
                    "grade VARCHAR(1)",
                    "payload BLOB",
                    "biz_date DATE",
                    "biz_time TIME",
                    "sql_date DATE",
                    "sql_time TIME",
                    "created_at TIMESTAMP",
                    "sql_created_at TIMESTAMP",
                    "legacy_created_at TIMESTAMP",
                    "event_at TIMESTAMP WITH TIME ZONE",
                    "offset_at TIMESTAMP WITH TIME ZONE",
                    "zoned_at TIMESTAMP WITH TIME ZONE",
                    "request_id VARCHAR(36)"
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
    void sqliteShouldRejectPrimaryKeyAutoIncrementModifyInSyncMode() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncModifyAutoIncrementUnsupported(
                    DbType.SQLITE,
                    connection
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

    @Test
    void sqliteShouldSyncAndDeleteMissingColumnsAndIndexes() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncFlow(
                    DbType.SQLITE,
                    connection,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncUserV1.class,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncUserV2.class,
                    "auto_sync_user",
                    "DROP INDEX idx_sync_legacy_code;",
                    "ALTER TABLE auto_sync_user DROP COLUMN legacy_code;",
                    "ALTER TABLE auto_sync_user ADD COLUMN email VARCHAR(128);",
                    "CREATE INDEX idx_sync_email ON auto_sync_user (email);"
            );
        }
    }

    @Test
    void sqliteShouldIgnoreColumnDefaultModifyInSyncMode() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncModifyDefaultIgnored(
                    DbType.SQLITE,
                    connection
            );
        }
    }

    @Test
    void sqliteShouldIgnoreInternalAutoIndexesDuringSync() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DDLTestPrinter.ddl(DbType.SQLITE)
                    .builder(new DefaultDDLBuilder())
                    .add(SqliteUniqueAutoIndexUser.class)
                    .execute(connection);

            assertTrue(tableExists(connection, UNIQUE_AUTO_INDEX_TABLE));
            assertTrue(hasSqliteAutoIndex(connection, UNIQUE_AUTO_INDEX_TABLE));

            List<String> syncExecutedSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.SQLITE, syncExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .mode(Mode.SYNC)
                    .add(SqliteUniqueAutoIndexUser.class)
                    .execute(connection);

            assertTrue(syncExecutedSqlList.isEmpty(),
                    "Expected no SQLite DDL after sync already executed: " + syncExecutedSqlList);
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

    private static boolean hasSqliteAutoIndex(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA index_list(" + quoteIdentifier(tableName) + ")")) {
            while (resultSet.next()) {
                String indexName = resultSet.getString("name");
                if (indexName != null && indexName.toLowerCase(Locale.ROOT).startsWith("sqlite_autoindex_")) {
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

    @Table(UNIQUE_AUTO_INDEX_TABLE)
    static class SqliteUniqueAutoIndexUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, unique = true)
        private String username;
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
