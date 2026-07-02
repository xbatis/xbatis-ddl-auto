package cn.xbatis.ddl.auto;

import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.annotations.ColumnDefinition;
import cn.xbatis.db.annotations.Table;
import cn.xbatis.db.annotations.TableDefinition;
import cn.xbatis.db.annotations.TableId;
import db.sql.api.DbType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class DDLAutoDb2IntegrationTest {

    private static final String TEST_TABLE = "auto_db2_sequence_user";

    private static final String TEST_SEQUENCE = "auto_db2_sequence_user_seq";

    private static final String DB2_URL = System.getProperty(
            "db2.test.url",
            "jdbc:db2://localhost:50000/test3"
    );

    private static final String DB2_USERNAME = System.getProperty("db2.test.username", "db2inst1");

    private static final String DB2_PASSWORD = System.getProperty("db2.test.password", "123456");

    private static final String DB2_DRIVER_CLASS_NAME = System.getProperty(
            "db2.test.driverClassName",
            "com.ibm.db2.jcc.DB2Driver"
    );

    private static final int DB2_LOGIN_TIMEOUT_SECONDS = Integer.getInteger("db2.test.loginTimeout", 2);

    private static Connection openDatabaseConnectionOrSkip() throws SQLException {
        try {
            loadDriverOrSkip();
            int originalLoginTimeout = DriverManager.getLoginTimeout();
            DriverManager.setLoginTimeout(DB2_LOGIN_TIMEOUT_SECONDS);
            try {
                return DriverManager.getConnection(DB2_URL, connectionProperties());
            } finally {
                DriverManager.setLoginTimeout(originalLoginTimeout);
            }
        } catch (SQLException exception) {
            assumeTrue(false, "Skip DB2 integration test, local DB2 is not available: " + exception.getMessage());
            throw exception;
        }
    }

    private static void loadDriverOrSkip() {
        try {
            Class.forName(DB2_DRIVER_CLASS_NAME);
        } catch (ClassNotFoundException exception) {
            assumeTrue(false, "Skip DB2 integration test, driver is not available: " + DB2_DRIVER_CLASS_NAME);
        }
    }

    private static Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", DB2_USERNAME);
        properties.setProperty("password", DB2_PASSWORD);
        properties.setProperty("retrieveMessagesFromServerOnGetMessage", "true");
        return properties;
    }

    private static void dropTestTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + TEST_TABLE);
        } catch (SQLException exception) {
            if (!isObjectNotExists(exception)) {
                throw exception;
            }
        }
    }

    private static void dropTestSequence(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SEQUENCE " + TEST_SEQUENCE);
        } catch (SQLException exception) {
            if (!isObjectNotExists(exception)) {
                throw exception;
            }
        }
    }

    private static boolean isObjectNotExists(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if ("42704".equals(current.getSQLState()) || current.getErrorCode() == -204) {
                return true;
            }
        }
        return false;
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM SYSCAT.TABLES WHERE TABSCHEMA = CURRENT SCHEMA AND TABNAME = UPPER(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            return hasRows(statement);
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM SYSCAT.COLUMNS "
                + "WHERE TABSCHEMA = CURRENT SCHEMA AND TABNAME = UPPER(?) AND COLNAME = UPPER(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            return hasRows(statement);
        }
    }

    private static boolean sequenceExists(Connection connection, String sequenceName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM SYSCAT.SEQUENCES WHERE SEQSCHEMA = CURRENT SCHEMA AND SEQNAME = UPPER(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sequenceName);
            return hasRows(statement);
        }
    }

    private static boolean nextSequenceValueReadable(Connection connection, String sequenceName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT NEXT VALUE FOR " + sequenceName + " FROM SYSIBM.SYSDUMMY1")) {
            return resultSet.next();
        }
    }

    private static boolean hasRows(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }

    @Test
    void db2ShouldCreateTableAndSequenceForTableIdSql() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            dropTestTable(connection);
            dropTestSequence(connection);
            try {
                List<String> createExecutedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.DB2, createExecutedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .add(Db2SequenceUser.class)
                        .execute(connection);

                assertTrue(createExecutedSqlList.contains("CREATE SEQUENCE " + TEST_SEQUENCE + ";"));
                assertTrue(createExecutedSqlList.stream().anyMatch(sql -> sql.contains("CREATE TABLE " + TEST_TABLE)));
                assertTrue(createExecutedSqlList.stream().anyMatch(sql -> sql.contains("id BIGINT NOT NULL PRIMARY KEY")));
                assertTrue(sequenceExists(connection, TEST_SEQUENCE));
                assertTrue(tableExists(connection, TEST_TABLE));
                assertTrue(columnExists(connection, TEST_TABLE, "id"));
                assertTrue(columnExists(connection, TEST_TABLE, "username"));
                assertTrue(nextSequenceValueReadable(connection, TEST_SEQUENCE));

                List<String> updateExecutedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.DB2, updateExecutedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .mode(Mode.UPDATE)
                        .add(Db2SequenceUser.class)
                        .execute(connection);
                assertTrue(updateExecutedSqlList.isEmpty(),
                        "Expected no DB2 DDL after table and sequence exist: " + updateExecutedSqlList);
            } finally {
                dropTestTable(connection);
                dropTestSequence(connection);
            }
        }
    }

    @Test
    void db2ShouldAddMultipleMissingColumnsInSingleAlter() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiColumnAddColumnFlow(
                    DbType.DB2,
                    connection,
                    "ALTER TABLE auto_multi_column_add_user ADD COLUMN age INTEGER ADD COLUMN email VARCHAR(128);"
            );
        }
    }

    @Test
    void db2ShouldCreateBooleanDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertBooleanDefaultValueFlow(
                    DbType.DB2,
                    connection,
                    "BOOLEAN",
                    "FALSE",
                    "TRUE"
            );
        }
    }

    @Test
    void db2ShouldCreateDateTimeDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateTimeDefaultValueFlow(
                    DbType.DB2,
                    connection,
                    "created_at TIMESTAMP DEFAULT CURRENT TIMESTAMP",
                    "event_at TIMESTAMP DEFAULT CURRENT TIMESTAMP"
            );
        }
    }

    @Test
    void db2ShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertIntLongAutoAndManualIdFlow(
                    DbType.DB2,
                    connection,
                    "id INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY",
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY",
                    "id INTEGER NOT NULL PRIMARY KEY",
                    "id BIGINT NOT NULL PRIMARY KEY"
            );
        }
    }

    @Test
    void db2ShouldCreateTableDefinitionComment() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertTableDefinitionFlow(
                    DbType.DB2,
                    connection,
                    Db2TableDefinitionIntegrationUser.class,
                    "auto_db2_table_definition_user",
                    null,
                    "COMMENT ON TABLE auto_db2_table_definition_user IS 'DB2表';"
            );
        }
    }

    @Test
    void db2ShouldCreateMultiplePhysicalTablesWithSequenceAndIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiTableSequenceAndIndexFlow(DbType.DB2, connection);
        }
    }

    @Table("auto_db2_sequence_user")
    static class Db2SequenceUser {

        @TableId(dbType = DbType.Name.DB2, value = IdAutoType.SQL,
                sql = "select next value for auto_db2_sequence_user_seq from sysibm.sysdummy1")
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

    @Table("auto_db2_table_definition_user")
    @TableDefinition(comment = "DB2表")
    static class Db2TableDefinitionIntegrationUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
