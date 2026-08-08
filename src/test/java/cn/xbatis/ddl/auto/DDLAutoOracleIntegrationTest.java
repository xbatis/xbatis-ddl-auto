package cn.xbatis.ddl.auto;

import cn.xbatis.db.IndexDirection;
import cn.xbatis.db.annotations.*;
import db.sql.api.DbType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class DDLAutoOracleIntegrationTest {

    private static final String TEST_TABLE = "auto_oracle_integration_user";

    private static final String ORACLE_URL = System.getProperty(
            "oracle.test.url",
            "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"
    );

    private static final String ORACLE_USERNAME = System.getProperty("oracle.test.username", "system");

    private static final String ORACLE_PASSWORD = System.getProperty("oracle.test.password", "oracle");

    private static final String ORACLE_CONNECT_TIMEOUT = System.getProperty("oracle.test.connectTimeout", "2000");

    private static final String ORACLE_READ_TIMEOUT = System.getProperty("oracle.test.readTimeout", "5000");

    @Test
    void oracleShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            dropTestTable(connection);
            try {
                DDLTestPrinter.ddl(DbType.ORACLE)
                        .builder(new DefaultDDLBuilder())
                        .add(OracleIntegrationUserV1.class)
                        .execute(connection);

                assertTrue(tableExists(connection, TEST_TABLE));
                assertTrue(columnExists(connection, TEST_TABLE, "username"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_ora_itg_username"));
                assertFalse(columnExists(connection, TEST_TABLE, "email"));
                assertFalse(indexExists(connection, TEST_TABLE, "idx_ora_itg_email"));

                assertFalse(columnExists(connection, TEST_TABLE, "email"));

                List<String> updateExecutedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.ORACLE, updateExecutedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .mode(Mode.UPDATE)
                        .add(OracleIntegrationUserV2.class)
                        .execute(connection);

                assertTrue(updateExecutedSqlList.contains("ALTER TABLE " + TEST_TABLE + " ADD email VARCHAR2(128);"));
                assertTrue(updateExecutedSqlList.contains("CREATE INDEX idx_ora_itg_email ON " + TEST_TABLE + " (email);"));
                assertTrue(updateExecutedSqlList.contains("CREATE INDEX idx_ora_itg_name_ct ON "
                        + TEST_TABLE + " (username ASC, created_at DESC);"));
                assertTrue(columnExists(connection, TEST_TABLE, "email"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_ora_itg_email"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_ora_itg_name_ct"));
                List<String> verifyExecutedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.ORACLE, verifyExecutedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .mode(Mode.UPDATE)
                        .add(OracleIntegrationUserV2.class)
                        .execute(connection);
                assertTrue(verifyExecutedSqlList.isEmpty(), "Expected no Oracle DDL after update already executed: " + verifyExecutedSqlList);
            } finally {
                dropTestTable(connection);
            }
        }
    }

    @Test
    void oracleShouldAddMultipleMissingColumnsInSingleAlter() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiColumnAddColumnFlow(
                    DbType.ORACLE,
                    connection,
                    "ALTER TABLE auto_multi_column_add_user ADD (age NUMBER(10), email VARCHAR2(128));"
            );
        }
    }

    @Test
    void oracleShouldCreateBooleanDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertBooleanDefaultValueFlow(
                    DbType.ORACLE,
                    connection,
                    "NUMBER(1)",
                    "0",
                    "1"
            );
        }
    }

    @Test
    void oracleShouldCreateDateTimeDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateTimeDefaultValueFlow(
                    DbType.ORACLE,
                    connection,
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                    "event_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP"
            );
        }
    }

    @Test
    void oracleShouldCreateDateDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateDefaultValueFlow(
                    DbType.ORACLE,
                    connection,
                    "biz_date DATE DEFAULT TRUNC(SYSDATE)",
                    "today_date DATE DEFAULT TRUNC(SYSDATE)"
            );
        }
    }

    @Test
    void oracleShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertIntLongAutoAndManualIdFlow(
                    DbType.ORACLE,
                    connection,
                    "id NUMBER(10) GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY",
                    "id NUMBER(19) GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY",
                    "id NUMBER(10) NOT NULL PRIMARY KEY",
                    "id NUMBER(19) NOT NULL PRIMARY KEY"
            );
        }
    }

    @Test
    void oracleShouldCreateTableDefinitionComment() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertTableDefinitionFlow(
                    DbType.ORACLE,
                    connection,
                    OracleTableDefinitionIntegrationUser.class,
                    "auto_oracle_table_definition_user",
                    null,
                    "COMMENT ON TABLE auto_oracle_table_definition_user IS 'Oracle表';"
            );
        }
    }

    @Test
    void oracleShouldCreateMultiplePhysicalTablesWithSequenceAndIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiTableSequenceAndIndexFlow(DbType.ORACLE, connection);
        }
    }

    @Test
    void oracleShouldSyncAndDeleteMissingColumnsAndIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncFlow(
                    DbType.ORACLE,
                    connection,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncUserV1.class,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncUserV2.class,
                    "auto_sync_user",
                    "DROP INDEX idx_sync_legacy_code;",
                    "ALTER TABLE auto_sync_user DROP COLUMN legacy_code;",
                    "ALTER TABLE auto_sync_user ADD email VARCHAR2(128);",
                    "CREATE INDEX idx_sync_email ON auto_sync_user (email);"
            );
        }
    }

    @Test
    void oracleShouldModifyChangedColumnsInSyncMode() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncModifyFlow(
                    DbType.ORACLE,
                    connection,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncModifyUserV1.class,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncModifyUserV2.class,
                    "auto_sync_modify_user",
                    "ALTER TABLE auto_sync_modify_user MODIFY (username VARCHAR2(128));"
            );
        }
    }

    @Test
    void oracleShouldModifyColumnDefaultInSyncMode() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncModifyDefaultFlow(
                    DbType.ORACLE,
                    connection,
                    "ALTER TABLE auto_sync_modify_default_user MODIFY (username DEFAULT 'new');",
                    "ALTER TABLE auto_sync_modify_default_user MODIFY (create_time DEFAULT CURRENT_TIMESTAMP);"
            );
        }
    }

    @Test
    void oracleShouldSyncTypeLengthAndDefaultCombinations() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertTypeLengthDefaultCombinationFlow(
                    DbType.ORACLE,
                    connection,
                    "ALTER TABLE auto_type_length_default_user MODIFY (nickname DEFAULT 'new');",
                    "ALTER TABLE auto_type_length_default_user MODIFY (amount DEFAULT 2.5);",
                    "ALTER TABLE auto_type_length_default_user MODIFY (biz_date DEFAULT TRUNC(SYSDATE));",
                    "ALTER TABLE auto_type_length_default_user MODIFY (sign_date DEFAULT TRUNC(SYSDATE));",
                    "ALTER TABLE auto_type_length_default_user MODIFY (start_time DEFAULT CURRENT_TIMESTAMP);",
                    "ALTER TABLE auto_type_length_default_user MODIFY (created_at DEFAULT CURRENT_TIMESTAMP);",
                    "ALTER TABLE auto_type_length_default_user MODIFY (event_at DEFAULT CURRENT_TIMESTAMP);"
            );
        }
    }

    @Test
    void oracleShouldDropColumnDefaultInSyncMode() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncDropDefaultFlow(
                    DbType.ORACLE,
                    connection,
                    "ALTER TABLE auto_sync_drop_default_user MODIFY (username DEFAULT NULL);"
            );
        }
    }

    @Test
    void oracleShouldRejectAddingPrimaryKeyAutoIncrementInSyncMode() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncModifyAutoIncrementUnsupported(
                    DbType.ORACLE,
                    connection
            );
        }
    }

    @Test
    void oracleShouldRemovePrimaryKeyAutoIncrementInSyncMode() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncModifyAutoIncrementReverseFlow(
                    DbType.ORACLE,
                    connection,
                    "ALTER TABLE auto_sync_modify_auto_id_reverse_user MODIFY (id DROP IDENTITY);"
            );
        }
    }

    @Test
    void oracleShouldModifyMultipleChangedColumnsInSingleAlter() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncModifyBatchFlow(
                    DbType.ORACLE,
                    connection,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncModifyBatchUserV1.class,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncModifyBatchUserV2.class,
                    "auto_sync_modify_batch_user",
                    "ALTER TABLE auto_sync_modify_batch_user MODIFY (username VARCHAR2(128), balance NUMBER(18,4));",
                    64,
                    128,
                    12,
                    18,
                    4
            );
        }
    }

    @Test
    void oracleShouldModifyChangedColumnCommentInSyncMode() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertSyncModifyCommentFlow(
                    DbType.ORACLE,
                    connection,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncModifyCommentUserV1.class,
                    DDLAutoExternalDatabaseIntegrationSupport.SyncModifyCommentUserV2.class,
                    "auto_sync_modify_comment_user",
                    "COMMENT ON COLUMN auto_sync_modify_comment_user.username IS 'new comment';"
            );
        }
    }

    private static Connection openDatabaseConnectionOrSkip() throws SQLException {
        try {
            return DriverManager.getConnection(ORACLE_URL, connectionProperties());
        } catch (SQLException exception) {
            assumeTrue(false, "Skip Oracle integration test, local Oracle is not available: " + exception.getMessage());
            throw exception;
        }
    }

    private static Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", ORACLE_USERNAME);
        properties.setProperty("password", ORACLE_PASSWORD);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", ORACLE_CONNECT_TIMEOUT);
        properties.setProperty("oracle.jdbc.ReadTimeout", ORACLE_READ_TIMEOUT);
        // Oracle JDBC 默认不返回列注释，必须开启 remarksReporting 才能通过 DatabaseMetaData 读到 COMMENT ON COLUMN
        properties.setProperty("remarksReporting", "true");
        return properties;
    }

    private static void dropTestTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + TEST_TABLE + " PURGE");
        } catch (SQLException exception) {
            if (exception.getErrorCode() != 942) {
                throw exception;
            }
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_tables WHERE table_name = UPPER(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            return hasRows(statement);
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_tab_columns WHERE table_name = UPPER(?) AND column_name = UPPER(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            return hasRows(statement);
        }
    }

    private static boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_indexes WHERE table_name = UPPER(?) AND index_name = UPPER(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            return hasRows(statement);
        }
    }

    private static boolean hasRows(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }

    @Table("auto_oracle_integration_user")
    @Index(name = "idx_ora_itg_username", fields = @IndexField(name = "username"))
    static class OracleIntegrationUserV1 {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_oracle_integration_user")
    @Index(name = "idx_ora_itg_username", fields = @IndexField(name = "username"))
    @Index(name = "idx_ora_itg_email", fields = @IndexField(name = "email"))
    @Index(name = "idx_ora_itg_name_ct", fields = {
            @IndexField(name = "username", direction = IndexDirection.ASC),
            @IndexField(name = "createdAt", direction = IndexDirection.DESC)
    })
    static class OracleIntegrationUserV2 {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;

        @ColumnDefinition(length = 128)
        private String email;
    }

    @Table("auto_oracle_table_definition_user")
    @TableDefinition(comment = "Oracle表")
    static class OracleTableDefinitionIntegrationUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
