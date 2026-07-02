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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class DDLAutoGaussIntegrationTest extends DDLAutoExternalDatabaseIntegrationSupport {

    private static final String GAUSS_DRIVER = "org.opengauss.Driver";

    private static final String GAUSS_URL = System.getProperty(
            "gauss.test.url",
            "jdbc:opengauss://127.0.0.1:5437/ddl_test?connectTimeout=2&socketTimeout=5"
    );

    private static final String GAUSS_ADMIN_URL = System.getProperty(
            "gauss.test.admin.url",
            adminDatabaseUrl(GAUSS_URL)
    );

    private static final String GAUSS_USERNAME = System.getProperty("gauss.test.username", "gaussdb");

    private static final String GAUSS_PASSWORD = System.getProperty("gauss.test.password", "Enmo@123");

    @Test
    void gaussShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertCreateUpdateFlow(
                    DbType.GAUSS,
                    connection,
                    GaussIntegrationUserV1.class,
                    GaussIntegrationUserV2.class,
                    "auto_gauss_itg_user",
                    "idx_gauss_itg_user_name",
                    "idx_gauss_itg_email",
                    "idx_gauss_itg_name_ct",
                    "ALTER TABLE auto_gauss_itg_user ADD COLUMN email VARCHAR(128);"
            );
        }
    }

    @Test
    void gaussShouldAddMultipleMissingColumnsInSingleAlter() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertMultiColumnAddColumnFlow(
                    DbType.GAUSS,
                    connection,
                    "ALTER TABLE auto_multi_column_add_user ADD COLUMN age INTEGER, ADD COLUMN email VARCHAR(128);"
            );
        }
    }

    @Test
    void gaussShouldCreateBooleanDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertBooleanDefaultValueFlow(
                    DbType.GAUSS,
                    connection,
                    "BOOLEAN",
                    "FALSE",
                    "TRUE"
            );
        }
    }

    @Test
    void gaussShouldCreateDateTimeDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertDateTimeDefaultValueFlow(
                    DbType.GAUSS,
                    connection,
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                    "event_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP"
            );
        }
    }

    @Test
    void gaussShouldCreateDateDefaultValueColumns() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertDateDefaultValueFlow(
                    DbType.GAUSS,
                    connection,
                    "biz_date DATE DEFAULT CURRENT_DATE",
                    "today_date DATE DEFAULT CURRENT_DATE"
            );
        }
    }

    @Test
    void gaussShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertIntLongAutoAndManualIdFlow(
                    DbType.GAUSS,
                    connection,
                    "id SERIAL NOT NULL PRIMARY KEY",
                    "id BIGSERIAL NOT NULL PRIMARY KEY",
                    "id INTEGER NOT NULL PRIMARY KEY",
                    "id BIGINT NOT NULL PRIMARY KEY"
            );
        }
    }

    @Test
    void gaussShouldCreateTableDefinitionComment() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertTableDefinitionFlow(
                    DbType.GAUSS,
                    connection,
                    GaussTableDefinitionIntegrationUser.class,
                    "auto_gauss_table_definition_user",
                    null,
                    "COMMENT ON TABLE auto_gauss_table_definition_user IS 'Gauss表';"
            );
        }
    }

    @Test
    void gaussShouldCreateMultiplePhysicalTablesWithSequenceAndIndexes() throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip()) {
            assertMultiTableSequenceAndIndexFlow(DbType.GAUSS, connection);
        }
    }

    private static Connection openDatabaseConnectionOrSkip() throws SQLException {
        try {
            Class.forName(GAUSS_DRIVER);
        } catch (ClassNotFoundException exception) {
            assumeTrue(false, "Skip Gauss integration test, JDBC driver is not available: " + exception.getMessage());
        }
        try {
            return DriverManager.getConnection(GAUSS_URL, GAUSS_USERNAME, GAUSS_PASSWORD);
        } catch (SQLException exception) {
            if (isDatabaseMissing(exception)) {
                createDatabaseOrSkip(exception);
                return DriverManager.getConnection(GAUSS_URL, GAUSS_USERNAME, GAUSS_PASSWORD);
            }
            assumeTrue(false, "Skip Gauss integration test, database is not available: " + exception.getMessage());
            throw exception;
        }
    }

    private static void createDatabaseOrSkip(SQLException missingDatabaseException) throws SQLException {
        String databaseName = databaseName(GAUSS_URL);
        if (databaseName == null || databaseName.isEmpty()) {
            assumeTrue(false, "Skip Gauss integration test, gauss.test.url has no database name: " + GAUSS_URL);
            throw missingDatabaseException;
        }
        try (Connection connection = DriverManager.getConnection(GAUSS_ADMIN_URL, GAUSS_USERNAME, GAUSS_PASSWORD)) {
            if (!databaseExists(connection, databaseName)) {
                createDatabase(connection, databaseName);
            }
        } catch (SQLException exception) {
            exception.addSuppressed(missingDatabaseException);
            assumeTrue(false, "Skip Gauss integration test, database " + databaseName
                    + " does not exist and cannot be created through " + GAUSS_ADMIN_URL + ": " + exception.getMessage());
            throw exception;
        }
    }

    private static boolean isDatabaseMissing(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if ("3D000".equals(current.getSQLState())) {
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
        return replaceDatabaseName(url, "postgres");
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

        int authorityStart = urlWithoutQuery.indexOf("://");
        if (authorityStart >= 0) {
            int databaseStart = urlWithoutQuery.indexOf('/', authorityStart + 3);
            if (databaseStart < 0) {
                return new JdbcUrlParts(urlWithoutQuery + "/", "", query);
            }
            return new JdbcUrlParts(urlWithoutQuery.substring(0, databaseStart + 1),
                    urlWithoutQuery.substring(databaseStart + 1), query);
        }

        String prefix = "jdbc:opengauss:";
        if (urlWithoutQuery.startsWith(prefix)) {
            return new JdbcUrlParts(prefix, urlWithoutQuery.substring(prefix.length()), query);
        }
        prefix = "jdbc:gaussdb:";
        if (urlWithoutQuery.startsWith(prefix)) {
            return new JdbcUrlParts(prefix, urlWithoutQuery.substring(prefix.length()), query);
        }
        return new JdbcUrlParts(urlWithoutQuery, "", query);
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

    @Table("auto_gauss_itg_user")
    @Index(name = "idx_gauss_itg_user_name", fields = @IndexField(name = "username"))
    static class GaussIntegrationUserV1 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_gauss_itg_user")
    @Indexs({
            @Index(name = "idx_gauss_itg_user_name", fields = @IndexField(name = "username")),
            @Index(name = "idx_gauss_itg_email", fields = @IndexField(name = "email")),
            @Index(name = "idx_gauss_itg_name_ct", fields = {
                    @IndexField(name = "username", direction = IndexDirection.ASC),
                    @IndexField(name = "createdAt", direction = IndexDirection.DESC)
            })
    })
    static class GaussIntegrationUserV2 {

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

    @Table("auto_gauss_table_definition_user")
    @TableDefinition(comment = "Gauss表")
    static class GaussTableDefinitionIntegrationUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

}
