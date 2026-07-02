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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class DDLAutoKingbaseIntegrationTest extends DDLAutoExternalDatabaseIntegrationSupport {

    private static final String KINGBASE_DRIVER = "com.kingbase8.Driver";

    private static final String KINGBASE_URL = System.getProperty(
            "kingbase.test.url",
            "jdbc:kingbase8://127.0.0.1:54321/ddl_test"
    );

    private static final String KINGBASE_ADMIN_URL = System.getProperty(
            "kingbase.test.admin.url",
            adminDatabaseUrl(KINGBASE_URL)
    );

    private static final String KINGBASE_USERNAME = System.getProperty("kingbase.test.username", "system");

    private static final String KINGBASE_PASSWORD = System.getProperty("kingbase.test.password", "123456");

    private static Connection openDatabaseConnectionOrSkip() throws SQLException {
        try {
            Class.forName(KINGBASE_DRIVER);
        } catch (ClassNotFoundException exception) {
            assumeTrue(false, "Skip Kingbase integration test, JDBC driver is not available: " + exception.getMessage());
        }
        try {
            return DriverManager.getConnection(KINGBASE_URL, KINGBASE_USERNAME, KINGBASE_PASSWORD);
        } catch (SQLException exception) {
            if (isDatabaseMissing(exception)) {
                createDatabaseOrSkip(exception);
                return DriverManager.getConnection(KINGBASE_URL, KINGBASE_USERNAME, KINGBASE_PASSWORD);
            }
            assumeTrue(false, "Skip Kingbase integration test, database is not available: " + exception.getMessage());
            throw exception;
        }
    }

    private static void createDatabaseOrSkip(SQLException missingDatabaseException) throws SQLException {
        String databaseName = databaseName(KINGBASE_URL);
        if (databaseName == null || databaseName.isEmpty()) {
            assumeTrue(false, "Skip Kingbase integration test, kingbase.test.url has no database name: " + KINGBASE_URL);
            throw missingDatabaseException;
        }
        try (Connection connection = DriverManager.getConnection(KINGBASE_ADMIN_URL, KINGBASE_USERNAME, KINGBASE_PASSWORD)) {
            if (!databaseExists(connection, databaseName)) {
                createDatabase(connection, databaseName);
            }
        } catch (SQLException exception) {
            exception.addSuppressed(missingDatabaseException);
            assumeTrue(false, "Skip Kingbase integration test, database " + databaseName
                    + " does not exist and cannot be created through " + KINGBASE_ADMIN_URL + ": " + exception.getMessage());
            throw exception;
        }
    }

    private static boolean isDatabaseMissing(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if ("3D000".equals(current.getSQLState())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(java.util.Locale.ROOT).contains("database")
                    && message.toLowerCase(java.util.Locale.ROOT).contains("does not exist")) {
                return true;
            }
        }
        return false;
    }

    private static boolean databaseExists(Connection connection, String databaseName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pg_database WHERE datname = ?")) {
            statement.setString(1, databaseName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private static void createDatabase(Connection connection, String databaseName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(databaseName));
        }
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String adminDatabaseUrl(String url) {
        return replaceDatabaseName(url, "test");
    }

    private static String databaseName(String url) {
        JdbcUrlParts parts = parseJdbcUrl(url);
        return parts.databaseName;
    }

    private static String replaceDatabaseName(String url, String databaseName) {
        JdbcUrlParts parts = parseJdbcUrl(url);
        return parts.prefix + databaseName + parts.query;
    }

    private static JdbcUrlParts parseJdbcUrl(String url) {
        String query = "";
        String urlWithoutQuery = url;
        int queryIndex = url.indexOf('?');
        if (queryIndex >= 0) {
            query = url.substring(queryIndex);
            urlWithoutQuery = url.substring(0, queryIndex);
        }
        String prefix = "jdbc:kingbase8://";
        if (urlWithoutQuery.startsWith(prefix)) {
            int databaseStart = urlWithoutQuery.indexOf('/', prefix.length());
            if (databaseStart < 0) {
                return new JdbcUrlParts(urlWithoutQuery + "/", "", query);
            }
            return new JdbcUrlParts(urlWithoutQuery.substring(0, databaseStart + 1),
                    urlWithoutQuery.substring(databaseStart + 1), query);
        }
        prefix = "jdbc:kingbase8:";
        if (urlWithoutQuery.startsWith(prefix)) {
            return new JdbcUrlParts(prefix, urlWithoutQuery.substring(prefix.length()), query);
        }
        return new JdbcUrlParts(urlWithoutQuery, "", query);
    }

    @Test
    void kingbaseShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertCreateUpdateFlow(
                    DbType.KING_BASE,
                    connection,
                    KingbaseIntegrationUserV1.class,
                    KingbaseIntegrationUserV2.class,
                    "auto_kingbase_itg_user",
                    "idx_kb_itg_user_name",
                    "idx_kb_itg_email",
                    "idx_kb_itg_name_ct",
                    "ALTER TABLE auto_kingbase_itg_user ADD COLUMN email VARCHAR(128);"
            );
        }
    }

    @Test
    void kingbaseShouldAddMultipleMissingColumnsInSingleAlter() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertMultiColumnAddColumnFlow(
                    DbType.KING_BASE,
                    connection,
                    "ALTER TABLE auto_multi_column_add_user ADD COLUMN age INTEGER, ADD COLUMN email VARCHAR(128);"
            );
        }
    }

    @Test
    void kingbaseShouldCreateBooleanDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertBooleanDefaultValueFlow(
                    DbType.KING_BASE,
                    connection,
                    "BOOLEAN",
                    "FALSE",
                    "TRUE"
            );
        }
    }

    @Test
    void kingbaseShouldCreateDateTimeDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertDateTimeDefaultValueFlow(
                    DbType.KING_BASE,
                    connection,
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                    "event_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP"
            );
        }
    }

    @Test
    void kingbaseShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertIntLongAutoAndManualIdFlow(
                    DbType.KING_BASE,
                    connection,
                    "id INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY",
                    "id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL PRIMARY KEY",
                    "id INTEGER NOT NULL PRIMARY KEY",
                    "id BIGINT NOT NULL PRIMARY KEY"
            );
        }
    }

    @Test
    void kingbaseShouldCreateTableDefinitionComment() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertTableDefinitionFlow(
                    DbType.KING_BASE,
                    connection,
                    KingbaseTableDefinitionIntegrationUser.class,
                    "auto_kingbase_table_definition_user",
                    null,
                    "COMMENT ON TABLE auto_kingbase_table_definition_user IS 'Kingbase表';"
            );
        }
    }

    @Test
    void kingbaseShouldCreateMultiplePhysicalTablesWithSequenceAndIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertMultiTableSequenceAndIndexFlow(DbType.KING_BASE, connection);
        }
    }

    static class JdbcUrlParts {

        private final String prefix;

        private final String databaseName;

        private final String query;

        JdbcUrlParts(String prefix, String databaseName, String query) {
            this.prefix = prefix;
            this.databaseName = databaseName;
            this.query = query;
        }
    }

    @Table("auto_kingbase_itg_user")
    @Index(name = "idx_kb_itg_user_name", fields = @IndexField(name = "username"))
    static class KingbaseIntegrationUserV1 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_kingbase_itg_user")
    @Indexs({
            @Index(name = "idx_kb_itg_user_name", fields = @IndexField(name = "username")),
            @Index(name = "idx_kb_itg_email", fields = @IndexField(name = "email")),
            @Index(name = "idx_kb_itg_name_ct", fields = {
                    @IndexField(name = "username", direction = IndexDirection.ASC),
                    @IndexField(name = "createdAt", direction = IndexDirection.DESC)
            })
    })
    static class KingbaseIntegrationUserV2 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;

        @ColumnDefinition(length = 128)
        private String email;
    }

    @Table("auto_kingbase_table_definition_user")
    @TableDefinition(comment = "Kingbase表")
    static class KingbaseTableDefinitionIntegrationUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
