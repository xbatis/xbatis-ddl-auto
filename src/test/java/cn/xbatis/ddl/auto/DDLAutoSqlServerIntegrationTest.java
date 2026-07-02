package cn.xbatis.ddl.auto;

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
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class DDLAutoSqlServerIntegrationTest {

    private static final String TEST_TABLE = "auto_sqlserver_integration_user";

    private static final String TIME_ZONE = System.getProperty("sqlserver.test.timeZone", TimeZone.getDefault().getID());

    private static final String SQL_SERVER_URL = System.getProperty(
            "sqlserver.test.url",
            "jdbc:sqlserver://localhost:1433;DatabaseName=master;encrypt=false;useUnicode=true;"
                    + "characterEncoding=utf-8;genKeyNameCase=2;serverTimezone=" + TIME_ZONE
    );

    private static final String SQL_SERVER_USERNAME = System.getProperty("sqlserver.test.username", "SA");

    private static final String SQL_SERVER_PASSWORD = System.getProperty("sqlserver.test.password", "AbC@128723");

    private static final String SQL_SERVER_DRIVER_CLASS_NAME = System.getProperty(
            "sqlserver.test.driverClassName",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    );

    @Test
    void sqlServerShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            dropTestTable(connection);
            try {
                DDLTestPrinter.ddl(DbType.SQL_SERVER)
                        .builder(new DefaultDDLBuilder())
                        .add(SqlServerIntegrationUserV1.class)
                        .execute(connection);

                assertTrue(tableExists(connection, TEST_TABLE));
                assertTrue(columnExists(connection, TEST_TABLE, "username"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_sqlsrv_itg_username"));
                assertFalse(columnExists(connection, TEST_TABLE, "email"));
                assertFalse(indexExists(connection, TEST_TABLE, "idx_sqlsrv_itg_email"));

                assertFalse(columnExists(connection, TEST_TABLE, "email"));

                List<String> updateExecutedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.SQL_SERVER, updateExecutedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .mode(Mode.UPDATE)
                        .add(SqlServerIntegrationUserV2.class)
                        .execute(connection);

                assertTrue(updateExecutedSqlList.contains("ALTER TABLE " + TEST_TABLE + " ADD email NVARCHAR(128);"));
                assertTrue(updateExecutedSqlList.contains("CREATE INDEX idx_sqlsrv_itg_email ON " + TEST_TABLE + " (email);"));
                assertTrue(updateExecutedSqlList.contains("CREATE INDEX idx_sqlsrv_itg_name_ct ON "
                        + TEST_TABLE + " (username ASC, created_at DESC);"));
                assertTrue(columnExists(connection, TEST_TABLE, "email"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_sqlsrv_itg_email"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_sqlsrv_itg_name_ct"));
                List<String> verifyExecutedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.SQL_SERVER, verifyExecutedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .mode(Mode.UPDATE)
                        .add(SqlServerIntegrationUserV2.class)
                        .execute(connection);
                assertTrue(verifyExecutedSqlList.isEmpty(), "Expected no SQL Server DDL after update already executed: " + verifyExecutedSqlList);
            } finally {
                dropTestTable(connection);
            }
        }
    }

    @Test
    void sqlServerShouldAddMultipleMissingColumnsInSingleAlter() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiColumnAddColumnFlow(
                    DbType.SQL_SERVER,
                    connection,
                    "ALTER TABLE auto_multi_column_add_user ADD age INTEGER, email NVARCHAR(128);"
            );
        }
    }

    @Test
    void sqlServerShouldCreateBooleanDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertBooleanDefaultValueFlow(
                    DbType.SQL_SERVER,
                    connection,
                    "BIT",
                    "0",
                    "1"
            );
        }
    }

    @Test
    void sqlServerShouldCreateDateTimeDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateTimeDefaultValueFlow(
                    DbType.SQL_SERVER,
                    connection,
                    "created_at DATETIME2 DEFAULT SYSDATETIME()",
                    "event_at DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET()"
            );
        }
    }

    @Test
    void sqlServerShouldCreateDateDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateDefaultValueFlow(
                    DbType.SQL_SERVER,
                    connection,
                    "biz_date DATE DEFAULT (CAST(GETDATE() AS DATE))",
                    "today_date DATE DEFAULT (CAST(GETDATE() AS DATE))"
            );
        }
    }

    @Test
    void sqlServerShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertIntLongAutoAndManualIdFlow(
                    DbType.SQL_SERVER,
                    connection,
                    "id INTEGER IDENTITY(1,1) NOT NULL PRIMARY KEY",
                    "id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY",
                    "id INTEGER NOT NULL PRIMARY KEY",
                    "id BIGINT NOT NULL PRIMARY KEY"
            );
        }
    }

    @Test
    void sqlServerShouldCreateTableDefinitionComment() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertTableDefinitionFlow(
                    DbType.SQL_SERVER,
                    connection,
                    SqlServerTableDefinitionIntegrationUser.class,
                    "auto_sqlserver_table_definition_user",
                    null,
                    "DECLARE @schema sysname = SCHEMA_NAME(); EXEC sys.sp_addextendedproperty "
                            + "@name=N'MS_Description', @value=N'SQL Server表', @level0type=N'SCHEMA', "
                            + "@level0name=@schema, @level1type=N'TABLE', "
                            + "@level1name=N'auto_sqlserver_table_definition_user';"
            );
        }
    }

    @Test
    void sqlServerShouldCreateMultiplePhysicalTablesWithSequenceAndIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiTableSequenceAndIndexFlow(DbType.SQL_SERVER, connection);
        }
    }

    private static Connection openDatabaseConnectionOrSkip() throws SQLException {
        try {
            loadDriverOrSkip();
            return DriverManager.getConnection(SQL_SERVER_URL, SQL_SERVER_USERNAME, SQL_SERVER_PASSWORD);
        } catch (SQLException exception) {
            assumeTrue(false, "Skip SQL Server integration test, local SQL Server is not available: " + exception.getMessage());
            throw exception;
        }
    }

    private static void loadDriverOrSkip() {
        try {
            Class.forName(SQL_SERVER_DRIVER_CLASS_NAME);
        } catch (ClassNotFoundException exception) {
            assumeTrue(false, "Skip SQL Server integration test, driver is not available: "
                    + SQL_SERVER_DRIVER_CLASS_NAME);
        }
    }

    private static void dropTestTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("IF OBJECT_ID(N'" + TEST_TABLE + "', N'U') IS NOT NULL DROP TABLE " + TEST_TABLE);
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM sys.tables WHERE schema_id = SCHEMA_ID() AND name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            return hasRows(statement);
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM sys.columns c JOIN sys.tables t ON c.object_id = t.object_id "
                + "WHERE t.schema_id = SCHEMA_ID() AND t.name = ? AND c.name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            return hasRows(statement);
        }
    }

    private static boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM sys.indexes i JOIN sys.tables t ON i.object_id = t.object_id "
                + "WHERE t.schema_id = SCHEMA_ID() AND t.name = ? AND i.name = ?";
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

    @Table("auto_sqlserver_integration_user")
    @Index(name = "idx_sqlsrv_itg_username", fields = @IndexField(name = "username"))
    static class SqlServerIntegrationUserV1 {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_sqlserver_integration_user")
    @Indexs({
            @Index(name = "idx_sqlsrv_itg_username", fields = @IndexField(name = "username")),
            @Index(name = "idx_sqlsrv_itg_email", fields = @IndexField(name = "email")),
            @Index(name = "idx_sqlsrv_itg_name_ct", fields = {
                    @IndexField(name = "username", direction = IndexDirection.ASC),
                    @IndexField(name = "createdAt", direction = IndexDirection.DESC)
            })
    })
    static class SqlServerIntegrationUserV2 {

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

    @Table("auto_sqlserver_table_definition_user")
    @TableDefinition(comment = "SQL Server表")
    static class SqlServerTableDefinitionIntegrationUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
