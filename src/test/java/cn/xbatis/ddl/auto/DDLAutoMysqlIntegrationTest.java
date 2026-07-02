package cn.xbatis.ddl.auto;

import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.IndexDirection;
import cn.xbatis.db.annotations.*;
import db.sql.api.DbType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class DDLAutoMysqlIntegrationTest {

    private static final String DEFAULT_SCHEMA = "ddl_test";

    private static final String TEST_TABLE = "auto_mysql_integration_user";

    private static final String CREATE_DATABASE_PARAMETER = "createDatabaseIfNotExist";

    private static final String MYSQL_URL = System.getProperty(
            "mysql.test.url",
            "jdbc:mysql://127.0.0.1:3306/ddl_test?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=2000&socketTimeout=5000"
    );

    private static final String MYSQL_USERNAME = System.getProperty("mysql.test.username", "root");

    private static final String MYSQL_PASSWORD = System.getProperty("mysql.test.password", "123456");

    @Test
    void mysqlShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            dropTestTable(connection);
            try {
                DDLTestPrinter.ddl(DbType.MYSQL)
                        .builder(new DefaultDDLBuilder())
                        .add(MysqlIntegrationUserV1.class)
                        .execute(connection);

                assertTrue(tableExists(connection, TEST_TABLE));
                assertTrue(columnExists(connection, TEST_TABLE, "username"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_mysql_integration_username"));
                assertFalse(columnExists(connection, TEST_TABLE, "email"));
                assertFalse(indexExists(connection, TEST_TABLE, "idx_mysql_integration_email"));

                assertFalse(columnExists(connection, TEST_TABLE, "email"));

                List<String> updateExecutedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.MYSQL, updateExecutedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .mode(Mode.UPDATE)
                        .add(MysqlIntegrationUserV2.class)
                        .execute(connection);

                assertTrue(updateExecutedSqlList.contains("ALTER TABLE " + TEST_TABLE + " ADD COLUMN email VARCHAR(128);"));
                assertTrue(updateExecutedSqlList.contains("CREATE INDEX idx_mysql_integration_email ON " + TEST_TABLE + " (email);"));
                assertTrue(updateExecutedSqlList.contains("CREATE INDEX idx_mysql_integration_username_created_at ON "
                        + TEST_TABLE + " (username ASC, created_at DESC);"));
                assertTrue(columnExists(connection, TEST_TABLE, "email"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_mysql_integration_email"));
                assertTrue(indexExists(connection, TEST_TABLE, "idx_mysql_integration_username_created_at"));
                List<String> verifyExecutedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.MYSQL, verifyExecutedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .mode(Mode.UPDATE)
                        .add(MysqlIntegrationUserV2.class)
                        .execute(connection);
                assertTrue(verifyExecutedSqlList.isEmpty(), "Expected no MySQL DDL after update already executed: " + verifyExecutedSqlList);
            } finally {
                dropTestTable(connection);
            }
        }
    }

    @Test
    void mysqlCreateSqlShouldKeepNowDefaultValue() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            dropTestTable(connection, "auto_mysql_now_default_user");
            try {
                List<String> executedSqlList = new java.util.ArrayList<>();
                DDLTestPrinter.ddl(DbType.MYSQL, executedSqlList)
                        .builder(new DefaultDDLBuilder())
                        .add(MysqlNowDefaultUser.class)
                        .execute(connection);

                assertEquals(1, executedSqlList.size());
                assertTrue(executedSqlList.get(0).contains("created_at DATETIME DEFAULT NOW()"));
            } finally {
                dropTestTable(connection, "auto_mysql_now_default_user");
            }
        }
    }

    @Test
    void mysqlShouldAddMultipleMissingColumnsInSingleAlter() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiColumnAddColumnFlow(
                    DbType.MYSQL,
                    connection,
                    "ALTER TABLE auto_multi_column_add_user ADD COLUMN age INTEGER, ADD COLUMN email VARCHAR(128);"
            );
        }
    }

    @Test
    void mysqlShouldCreateBooleanDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertBooleanDefaultValueFlow(
                    DbType.MYSQL,
                    connection,
                    "TINYINT(1)",
                    "0",
                    "1"
            );
        }
    }

    @Test
    void mysqlShouldCreateDateTimeDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateTimeDefaultValueFlow(
                    DbType.MYSQL,
                    connection,
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP",
                    "event_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
            );
        }
    }

    @Test
    void mysqlShouldCreateDateDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateDefaultValueFlow(
                    DbType.MYSQL,
                    connection,
                    "biz_date DATE DEFAULT (CURRENT_DATE)",
                    "today_date DATE DEFAULT (CURRENT_DATE)"
            );
        }
    }

    @Test
    void mysqlShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertIntLongAutoAndManualIdFlow(
                    DbType.MYSQL,
                    connection,
                    "id INTEGER AUTO_INCREMENT NOT NULL PRIMARY KEY",
                    "id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY",
                    "id INTEGER NOT NULL PRIMARY KEY",
                    "id BIGINT NOT NULL PRIMARY KEY"
            );
        }
    }

    @Test
    void mysqlShouldCreateTableDefinition() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertTableDefinitionFlow(
                    DbType.MYSQL,
                    connection,
                    MysqlTableDefinitionIntegrationUser.class,
                    "auto_mysql_table_definition_user",
                    "ENGINE=InnoDB COMMENT='MySQL表'",
                    null
            );
        }
    }

    @Test
    void mysqlShouldCreateMultiplePhysicalTablesWithIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            DDLAutoExternalDatabaseIntegrationSupport.assertMultiTableIndexFlow(DbType.MYSQL, connection);
        }
    }

    private static Connection openDatabaseConnectionOrSkip() throws SQLException {
        try {
            return DriverManager.getConnection(databaseUrl(), MYSQL_USERNAME, MYSQL_PASSWORD);
        } catch (SQLException exception) {
            assumeTrue(false, "Skip MySQL integration test, local MySQL is not available: " + exception.getMessage());
            throw exception;
        }
    }

    private static void dropTestTable(Connection connection) throws SQLException {
        dropTestTable(connection, TEST_TABLE);
    }

    private static void dropTestTable(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + tableName);
        }
    }

    private static String databaseUrl() {
        ParsedJdbcUrl parsedUrl = parseJdbcUrl();
        String databasePath = parsedUrl.path;
        if (databasePath.isEmpty() || "/".equals(databasePath)) {
            databasePath = "/" + DEFAULT_SCHEMA;
        }
        String query = withCreateDatabaseParameter(parsedUrl.query);
        return parsedUrl.base + databasePath + (query.isEmpty() ? "" : "?" + query);
    }

    private static String withCreateDatabaseParameter(String query) {
        if (hasQueryParameter(query, CREATE_DATABASE_PARAMETER)) {
            return query;
        }
        String parameter = CREATE_DATABASE_PARAMETER + "=true";
        return query.isEmpty() ? parameter : query + "&" + parameter;
    }

    private static boolean hasQueryParameter(String query, String name) {
        if (query.isEmpty()) {
            return false;
        }
        String[] parameters = query.split("&");
        for (String parameter : parameters) {
            int equalsIndex = parameter.indexOf('=');
            String parameterName = equalsIndex < 0 ? parameter : parameter.substring(0, equalsIndex);
            if (parameterName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static ParsedJdbcUrl parseJdbcUrl() {
        int queryIndex = MYSQL_URL.indexOf('?');
        String urlWithoutQuery = queryIndex < 0 ? MYSQL_URL : MYSQL_URL.substring(0, queryIndex);
        String query = queryIndex < 0 ? "" : MYSQL_URL.substring(queryIndex + 1);
        int authorityStart = "jdbc:mysql://".length();
        int schemaStart = urlWithoutQuery.indexOf('/', authorityStart);
        String base = schemaStart < 0 ? urlWithoutQuery : urlWithoutQuery.substring(0, schemaStart);
        String path = schemaStart < 0 ? "" : urlWithoutQuery.substring(schemaStart);
        return new ParsedJdbcUrl(base, path, query);
    }

    static class ParsedJdbcUrl {

        private final String base;

        private final String path;

        private final String query;

        ParsedJdbcUrl(String base, String path, String query) {
            this.base = base;
            this.path = path;
            this.query = query;
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        return exists(connection, "information_schema.tables", "table_name", tableName);
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            return hasRows(statement);
        }
    }

    private static boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            return hasRows(statement);
        }
    }

    private static boolean exists(Connection connection, String metadataTable, String nameColumn, String name) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + metadataTable + " WHERE table_schema = DATABASE() AND " + nameColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            return hasRows(statement);
        }
    }

    private static boolean hasRows(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }

    @Table("auto_mysql_integration_user")
    @Index(name = "idx_mysql_integration_username", fields = @IndexField(name = "username"))
    static class MysqlIntegrationUserV1 {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_mysql_integration_user")
    @Indexs({
            @Index(name = "idx_mysql_integration_username", fields = @IndexField(name = "username")),
            @Index(name = "idx_mysql_integration_email", fields = @IndexField(name = "email")),
            @Index(name = "idx_mysql_integration_username_created_at", fields = {
                    @IndexField(name = "username", direction = IndexDirection.ASC),
                    @IndexField(name = "createdAt", direction = IndexDirection.DESC)
            })
    })
    static class MysqlIntegrationUserV2 {

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

    @Table("auto_mysql_now_default_user")
    static class MysqlNowDefaultUser {

        @TableId
        private Long id;

        @ColumnDefinition(defaultValue = "NOW()")
        private LocalDateTime createdAt;
    }

    @Table("auto_mysql_table_definition_user")
    @TableDefinition(definition = "ENGINE=InnoDB", comment = "MySQL表")
    static class MysqlTableDefinitionIntegrationUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
