package cn.xbatis.ddl.auto;

import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.annotations.*;
import db.sql.api.DbType;
import db.sql.api.IDbType;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

abstract class DDLAutoExternalDatabaseIntegrationSupport {

    private static final String INTEGER_AUTO_ID_TABLE = "auto_id_int_auto_user";

    private static final String LONG_AUTO_ID_TABLE = "auto_id_long_auto_user";

    private static final String INTEGER_MANUAL_ID_TABLE = "auto_id_int_manual_user";

    private static final String LONG_MANUAL_ID_TABLE = "auto_id_long_manual_user";

    private static final String MULTI_TABLE_INDEX_TABLE = "auto_multi_table_index_user";

    private static final String MULTI_TABLE_SEQUENCE_TABLE = "auto_multi_table_seq_user";

    private static final String MULTI_TABLE_SEQUENCE = "auto_multi_table_seq";

    private static final String MULTI_COLUMN_ADD_TABLE = "auto_multi_column_add_user";

    private static final String BOOLEAN_DEFAULT_TABLE = "auto_boolean_default_user";

    private static final String DATE_TIME_DEFAULT_TABLE = "auto_datetime_default_user";

    static void assertCreateUpdateFlow(DatabaseCase databaseCase,
                                       Class<?> v1Entity,
                                       Class<?> v2Entity,
                                       String tableName,
                                       String usernameIndexName,
                                       String emailIndexName,
                                       String compositeIndexName,
                                       String expectedAddColumnSql) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertCreateUpdateFlow(databaseCase.dbType, connection, v1Entity, v2Entity, tableName,
                    usernameIndexName, emailIndexName, compositeIndexName, expectedAddColumnSql);
        }
    }

    static void assertCreateUpdateFlow(IDbType dbType,
                                       Connection connection,
                                       Class<?> v1Entity,
                                       Class<?> v2Entity,
                                       String tableName,
                                       String usernameIndexName,
                                       String emailIndexName,
                                       String compositeIndexName,
                                       String expectedAddColumnSql) throws Exception {
        dropTestTable(connection, tableName);
        try {
            DDLTestPrinter.ddl(dbType)
                    .builder(new DefaultDDLBuilder())
                    .add(v1Entity)
                    .execute(connection);

            assertTrue(tableExists(connection, tableName));
            assertTrue(columnExists(connection, tableName, "username"));
            assertTrue(indexExists(connection, tableName, usernameIndexName));
            assertFalse(columnExists(connection, tableName, "email"));
            assertFalse(indexExists(connection, tableName, emailIndexName));

            assertFalse(columnExists(connection, tableName, "email"));

            List<String> updateExecutedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, updateExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .mode(Mode.UPDATE)
                    .add(v2Entity)
                    .execute(connection);

            assertTrue(updateExecutedSqlList.contains(expectedAddColumnSql));
            assertTrue(updateExecutedSqlList.contains("CREATE INDEX " + emailIndexName + " ON " + tableName + " (email);"));
            assertTrue(updateExecutedSqlList.contains("CREATE INDEX " + compositeIndexName + " ON "
                    + tableName + " (username ASC, created_at DESC);"));
            assertTrue(columnExists(connection, tableName, "email"));
            assertTrue(indexExists(connection, tableName, emailIndexName));
            assertTrue(indexExists(connection, tableName, compositeIndexName));

            List<String> verifyExecutedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, verifyExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .mode(Mode.UPDATE)
                    .add(v2Entity)
                    .execute(connection);
            assertTrue(verifyExecutedSqlList.isEmpty(),
                    "Expected no DDL after update flow already executed: " + verifyExecutedSqlList);
        } finally {
            dropTestTable(connection, tableName);
        }
    }

    static void assertMultiTableIndexFlow(DatabaseCase databaseCase) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertMultiTableIndexFlow(databaseCase.dbType, connection);
        }
    }

    static void assertMultiTableIndexFlow(IDbType dbType, Connection connection) throws Exception {
        assertMultiTableFlow(dbType, connection, MultiTableIndexUserV1.class, MultiTableIndexUserV2.class,
                MULTI_TABLE_INDEX_TABLE, null);
    }

    static void assertMultiTableSequenceAndIndexFlow(DatabaseCase databaseCase) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertMultiTableSequenceAndIndexFlow(databaseCase.dbType, connection);
        }
    }

    static void assertMultiTableSequenceAndIndexFlow(IDbType dbType, Connection connection) throws Exception {
        assertMultiTableFlow(dbType, connection, MultiTableSequenceUserV1.class, MultiTableSequenceUserV2.class,
                MULTI_TABLE_SEQUENCE_TABLE, MULTI_TABLE_SEQUENCE);
    }

    static void assertMultiColumnAddColumnFlow(DatabaseCase databaseCase, String... expectedAddColumnSqlList) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertMultiColumnAddColumnFlow(databaseCase.dbType, connection, expectedAddColumnSqlList);
        }
    }

    static void assertMultiColumnAddColumnFlow(IDbType dbType,
                                               Connection connection,
                                               String... expectedAddColumnSqlList) throws Exception {
        dropTestTable(connection, MULTI_COLUMN_ADD_TABLE);
        try {
            DDLTestPrinter.ddl(dbType)
                    .builder(new DefaultDDLBuilder())
                    .add(MultiColumnAddUserV1.class)
                    .execute(connection);

            assertTrue(tableExists(connection, MULTI_COLUMN_ADD_TABLE));
            assertTrue(columnExists(connection, MULTI_COLUMN_ADD_TABLE, "id"));
            assertTrue(columnExists(connection, MULTI_COLUMN_ADD_TABLE, "username"));
            assertFalse(columnExists(connection, MULTI_COLUMN_ADD_TABLE, "age"));
            assertFalse(columnExists(connection, MULTI_COLUMN_ADD_TABLE, "email"));

            List<String> updateExecutedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, updateExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .mode(Mode.UPDATE)
                    .add(MultiColumnAddUserV2.class)
                    .execute(connection);

            assertEquals(Arrays.asList(expectedAddColumnSqlList), updateExecutedSqlList,
                    "Expected ADD COLUMN SQL list: " + updateExecutedSqlList);
            assertTrue(columnExists(connection, MULTI_COLUMN_ADD_TABLE, "age"));
            assertTrue(columnExists(connection, MULTI_COLUMN_ADD_TABLE, "email"));

            List<String> verifyExecutedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, verifyExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .mode(Mode.UPDATE)
                    .add(MultiColumnAddUserV2.class)
                    .execute(connection);
            assertTrue(verifyExecutedSqlList.isEmpty(),
                    "Expected no DDL after multi-column update flow already executed: " + verifyExecutedSqlList);
        } finally {
            dropTestTable(connection, MULTI_COLUMN_ADD_TABLE);
        }
    }

    static void assertBooleanDefaultValueFlow(DatabaseCase databaseCase,
                                              Class<?> entityClass,
                                              String expectedDefaultFalseSql,
                                              String expectedDefaultTrueSql) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertBooleanDefaultValueFlow(databaseCase.dbType, connection, entityClass,
                    expectedDefaultFalseSql, expectedDefaultTrueSql);
        }
    }

    static void assertBooleanDefaultValueFlow(IDbType dbType,
                                              Connection connection,
                                              Class<?> entityClass,
                                              String expectedDefaultFalseSql,
                                              String expectedDefaultTrueSql) throws Exception {
        dropTestTable(connection, BOOLEAN_DEFAULT_TABLE);
        try {
            List<String> executedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, executedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .add(entityClass)
                    .execute(connection);

            assertTrue(executedSqlList.stream().anyMatch(sql -> sql.contains(expectedDefaultFalseSql)),
                    "Expected boolean false default SQL [" + expectedDefaultFalseSql + "]: " + executedSqlList);
            assertTrue(executedSqlList.stream().anyMatch(sql -> sql.contains(expectedDefaultTrueSql)),
                    "Expected boolean true default SQL [" + expectedDefaultTrueSql + "]: " + executedSqlList);
            assertTrue(tableExists(connection, BOOLEAN_DEFAULT_TABLE));
            assertTrue(columnExists(connection, BOOLEAN_DEFAULT_TABLE, "default_false"));
            assertTrue(columnExists(connection, BOOLEAN_DEFAULT_TABLE, "default_true"));
        } finally {
            dropTestTable(connection, BOOLEAN_DEFAULT_TABLE);
        }
    }

    static void assertBooleanDefaultValueFlow(DatabaseCase databaseCase,
                                              String expectedColumnType,
                                              String expectedFalseDefaultValue,
                                              String expectedTrueDefaultValue) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertBooleanDefaultValueFlow(databaseCase.dbType, connection, expectedColumnType,
                    expectedFalseDefaultValue, expectedTrueDefaultValue);
        }
    }

    static void assertBooleanDefaultValueFlow(IDbType dbType,
                                              Connection connection,
                                              String expectedColumnType,
                                              String expectedFalseDefaultValue,
                                              String expectedTrueDefaultValue) throws Exception {
        dropTestTable(connection, BOOLEAN_DEFAULT_TABLE);
        try {
            List<String> executedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, executedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .add(BooleanDefaultVariantUser.class)
                    .execute(connection);

            assertBooleanDefaultSql(executedSqlList, "numeric_false", expectedColumnType, expectedFalseDefaultValue);
            assertBooleanDefaultSql(executedSqlList, "numeric_true", expectedColumnType, expectedTrueDefaultValue);
            assertBooleanDefaultSql(executedSqlList, "literal_false", expectedColumnType, expectedFalseDefaultValue);
            assertBooleanDefaultSql(executedSqlList, "literal_true", expectedColumnType, expectedTrueDefaultValue);
            assertTrue(tableExists(connection, BOOLEAN_DEFAULT_TABLE));
            assertTrue(columnExists(connection, BOOLEAN_DEFAULT_TABLE, "numeric_false"));
            assertTrue(columnExists(connection, BOOLEAN_DEFAULT_TABLE, "numeric_true"));
            assertTrue(columnExists(connection, BOOLEAN_DEFAULT_TABLE, "literal_false"));
            assertTrue(columnExists(connection, BOOLEAN_DEFAULT_TABLE, "literal_true"));
            assertBooleanDefaultsGeneratedByDatabase(connection);
        } finally {
            dropTestTable(connection, BOOLEAN_DEFAULT_TABLE);
        }
    }

    private static void assertBooleanDefaultSql(List<String> executedSqlList,
                                                String columnName,
                                                String expectedColumnType,
                                                String expectedDefaultValue) {
        String expectedSql = columnName + " " + expectedColumnType + " DEFAULT " + expectedDefaultValue;
        assertTrue(executedSqlList.stream().anyMatch(sql -> sql.contains(expectedSql)),
                "Expected boolean default SQL [" + expectedSql + "]: " + executedSqlList);
    }

    static void assertDateTimeDefaultValueFlow(DatabaseCase databaseCase,
                                               String expectedDateTimeSql,
                                               String expectedInstantSql) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertDateTimeDefaultValueFlow(databaseCase.dbType, connection, expectedDateTimeSql, expectedInstantSql);
        }
    }

    static void assertDateTimeDefaultValueFlow(IDbType dbType,
                                               Connection connection,
                                               String expectedDateTimeSql,
                                               String expectedInstantSql) throws Exception {
        dropTestTable(connection, DATE_TIME_DEFAULT_TABLE);
        try {
            List<String> executedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, executedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .add(DateTimeDefaultUser.class)
                    .execute(connection);

            assertTrue(executedSqlList.stream().anyMatch(sql -> sql.contains(expectedDateTimeSql)),
                    "Expected LocalDateTime default SQL [" + expectedDateTimeSql + "]: " + executedSqlList);
            assertTrue(executedSqlList.stream().anyMatch(sql -> sql.contains(expectedInstantSql)),
                    "Expected Instant default SQL [" + expectedInstantSql + "]: " + executedSqlList);
            assertTrue(tableExists(connection, DATE_TIME_DEFAULT_TABLE));
            assertTrue(columnExists(connection, DATE_TIME_DEFAULT_TABLE, "created_at"));
            assertTrue(columnExists(connection, DATE_TIME_DEFAULT_TABLE, "event_at"));
            assertDateTimeDefaultsGeneratedByDatabase(connection);
        } finally {
            dropTestTable(connection, DATE_TIME_DEFAULT_TABLE);
        }
    }

    private static void assertBooleanDefaultsGeneratedByDatabase(Connection connection) throws SQLException {
        insertDefaultOnlyRow(connection, BOOLEAN_DEFAULT_TABLE, 1L);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT numeric_false, numeric_true, literal_false, literal_true FROM " + BOOLEAN_DEFAULT_TABLE + " WHERE id = ?"
        )) {
            statement.setLong(1, 1L);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Expected inserted boolean default row");
                assertBooleanValue(false, resultSet.getObject("numeric_false"), "numeric_false");
                assertBooleanValue(true, resultSet.getObject("numeric_true"), "numeric_true");
                assertBooleanValue(false, resultSet.getObject("literal_false"), "literal_false");
                assertBooleanValue(true, resultSet.getObject("literal_true"), "literal_true");
            }
        }
    }

    private static void assertDateTimeDefaultsGeneratedByDatabase(Connection connection) throws SQLException {
        insertDefaultOnlyRow(connection, DATE_TIME_DEFAULT_TABLE, 1L);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT created_at, event_at FROM " + DATE_TIME_DEFAULT_TABLE + " WHERE id = ?"
        )) {
            statement.setLong(1, 1L);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Expected inserted date/time default row");
                assertNotNull(resultSet.getObject("created_at"), "Expected created_at database default value");
                assertNotNull(resultSet.getObject("event_at"), "Expected event_at database default value");
            }
        }
    }

    private static void insertDefaultOnlyRow(Connection connection, String tableName, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + tableName + " (id) VALUES (?)")) {
            statement.setLong(1, id);
            assertEquals(1, statement.executeUpdate(), "Expected one inserted default row for " + tableName);
        }
    }

    private static void assertBooleanValue(boolean expected, Object actual, String columnName) {
        assertNotNull(actual, "Expected " + columnName + " database default value");
        Boolean actualBoolean = toBoolean(actual);
        assertNotNull(actualBoolean, "Expected " + columnName + " to be readable as boolean, actual: "
                + actual + " (" + actual.getClass().getName() + ")");
        assertEquals(expected, actualBoolean, "Unexpected database default value for " + columnName);
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String normalizedValue = ((String) value).trim().toUpperCase(Locale.ROOT);
            if ("0".equals(normalizedValue) || "FALSE".equals(normalizedValue)) {
                return Boolean.FALSE;
            }
            if ("1".equals(normalizedValue) || "TRUE".equals(normalizedValue)) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    private static void assertMultiTableFlow(IDbType dbType,
                                             Connection connection,
                                             Class<?> v1EntityClass,
                                             Class<?> v2EntityClass,
                                             String logicalTableName,
                                             String sequenceName) throws Exception {
        List<String> tableNames = physicalTableNames(logicalTableName);
        dropMultiTableObjects(connection, tableNames, sequenceName);
        DDLTableNameResolverUtil.set(v1EntityClass, tableName -> physicalTableNames(tableName));
        DDLTableNameResolverUtil.set(v2EntityClass, tableName -> physicalTableNames(tableName));
        try {
            List<String> createExecutedSqlList = executeMultiTableFlow(dbType, v1EntityClass, Mode.CREATE, connection);
            assertMultiTableCreateResult(connection, tableNames, sequenceName, createExecutedSqlList, Mode.CREATE, false);

            List<String> updateExistingExecutedSqlList = executeMultiTableFlow(dbType, v2EntityClass, Mode.UPDATE, connection);
            assertMultiTableAddColumnResult(connection, tableNames, sequenceName, updateExistingExecutedSqlList);

            List<String> verifyExecutedSqlList = executeMultiTableFlow(dbType, v2EntityClass, Mode.UPDATE, connection);
            assertTrue(verifyExecutedSqlList.isEmpty(),
                    "Expected no DDL after multi-table update-add-column flow already executed: " + verifyExecutedSqlList);


            dropMultiTableObjects(connection, tableNames, sequenceName);

            List<String> updateExecutedSqlList = executeMultiTableFlow(dbType, v2EntityClass, Mode.UPDATE, connection);
            assertMultiTableCreateResult(connection, tableNames, sequenceName, updateExecutedSqlList, Mode.UPDATE, true);

            List<String> updateVerifyExecutedSqlList = executeMultiTableFlow(dbType, v2EntityClass, Mode.UPDATE, connection);
            assertTrue(updateVerifyExecutedSqlList.isEmpty(),
                    "Expected no DDL after multi-table update flow already executed: " + updateVerifyExecutedSqlList);
        } finally {
            DDLTableNameResolverUtil.remove(v1EntityClass);
            DDLTableNameResolverUtil.remove(v2EntityClass);
            dropMultiTableObjects(connection, tableNames, sequenceName);
        }
    }

    private static List<String> executeMultiTableFlow(IDbType dbType, Class<?> entityClass, Mode mode, Connection connection) {
        List<String> executedSqlList = new ArrayList<>();
        DDLTestPrinter.ddl(dbType, executedSqlList)
                .builder(new DefaultDDLBuilder())
                .mode(mode)
                .add(entityClass)
                .execute(connection);
        return executedSqlList;
    }

    private static void assertMultiTableCreateResult(Connection connection,
                                                     List<String> tableNames,
                                                     String sequenceName,
                                                     List<String> executedSqlList,
                                                     Mode mode,
                                                     boolean expectEmail) throws SQLException {
        if (sequenceName != null) {
            assertSequenceCreateSqlCount(executedSqlList, sequenceName, 1L,
                    "Expected sequence DDL to be executed only once in " + mode + " mode: " + executedSqlList);
        }

        for (String tableName : tableNames) {
            assertTrue(executedSqlList.stream().anyMatch(sql -> sql.contains("CREATE TABLE") && sql.contains(tableName)),
                    "Expected CREATE TABLE SQL for " + tableName + " in " + mode + " mode: " + executedSqlList);
            assertTrue(executedSqlList.contains("CREATE INDEX " + usernameIndexName(tableName)
                            + " ON " + tableName + " (username);"),
                    "Expected CREATE INDEX SQL for " + tableName + " in " + mode + " mode: " + executedSqlList);
            assertTrue(tableExists(connection, tableName));
            assertTrue(columnExists(connection, tableName, "id"));
            assertTrue(columnExists(connection, tableName, "username"));
            assertTrue(indexExists(connection, tableName, usernameIndexName(tableName)));
            if (expectEmail) {
                assertTrue(executedSqlList.contains("CREATE INDEX " + emailIndexName(tableName)
                                + " ON " + tableName + " (email);"),
                        "Expected email CREATE INDEX SQL for " + tableName + " in " + mode + " mode: " + executedSqlList);
                assertTrue(columnExists(connection, tableName, "email"));
                assertTrue(indexExists(connection, tableName, emailIndexName(tableName)));
            } else {
                assertFalse(columnExists(connection, tableName, "email"));
                assertFalse(indexExists(connection, tableName, emailIndexName(tableName)));
            }
        }
    }

    private static void assertMultiTableAddColumnResult(Connection connection,
                                                        List<String> tableNames,
                                                        String sequenceName,
                                                        List<String> executedSqlList) throws SQLException {
        if (sequenceName != null) {
            assertSequenceCreateSqlCount(executedSqlList, sequenceName, 0L,
                    "Expected sequence DDL not to be recreated while adding columns: " + executedSqlList);
        }

        for (String tableName : tableNames) {
            assertTrue(executedSqlList.stream().noneMatch(sql -> sql.contains("CREATE TABLE") && sql.contains(tableName)),
                    "Expected UPDATE existing table flow not to recreate " + tableName + ": " + executedSqlList);
            assertTrue(containsAddColumnSql(executedSqlList, tableName, "email"),
                    "Expected ADD COLUMN SQL for email on " + tableName + ": " + executedSqlList);
            assertTrue(executedSqlList.contains("CREATE INDEX " + emailIndexName(tableName)
                            + " ON " + tableName + " (email);"),
                    "Expected email CREATE INDEX SQL for " + tableName + ": " + executedSqlList);
            assertTrue(tableExists(connection, tableName));
            assertTrue(columnExists(connection, tableName, "id"));
            assertTrue(columnExists(connection, tableName, "username"));
            assertTrue(columnExists(connection, tableName, "email"));
            assertTrue(indexExists(connection, tableName, usernameIndexName(tableName)));
            assertTrue(indexExists(connection, tableName, emailIndexName(tableName)));
        }
    }

    private static void assertSequenceCreateSqlCount(List<String> executedSqlList,
                                                     String sequenceName,
                                                     long expectedCount,
                                                     String message) {
        long sequenceSqlCount = executedSqlList.stream()
                .filter(sql -> sql.toUpperCase(Locale.ROOT).startsWith("CREATE SEQUENCE") && sql.contains(sequenceName))
                .count();
        assertEquals(expectedCount, sequenceSqlCount, message);
    }

    private static boolean containsAddColumnSql(List<String> executedSqlList, String tableName, String columnName) {
        String tableNameUpper = tableName.toUpperCase(Locale.ROOT);
        String columnNameUpper = columnName.toUpperCase(Locale.ROOT);
        return executedSqlList.stream().anyMatch(sql -> {
            String upperSql = sql.toUpperCase(Locale.ROOT);
            return upperSql.contains("ALTER TABLE")
                    && upperSql.contains(tableNameUpper)
                    && upperSql.contains("ADD")
                    && upperSql.contains(columnNameUpper);
        });
    }

    private static void dropMultiTableObjects(Connection connection, List<String> tableNames, String sequenceName) throws SQLException {
        System.out.println("=====================");
        for (int i = tableNames.size() - 1; i >= 0; i--) {
            dropTestTable(connection, tableNames.get(i));
        }
        if (sequenceName != null) {
            dropTestSequence(connection, sequenceName);
        }
    }

    private static List<String> physicalTableNames(String logicalTableName) {
        return Arrays.asList(logicalTableName + "_00", logicalTableName + "_01");
    }

    private static String usernameIndexName(String tableName) {
        return "idx_" + tableName + "_username";
    }

    private static String emailIndexName(String tableName) {
        return "idx_" + tableName + "_email";
    }

    static void assertIntLongAutoAndManualIdFlow(DatabaseCase databaseCase,
                                                 String expectedIntegerAutoIdSql,
                                                 String expectedLongAutoIdSql,
                                                 String expectedIntegerManualIdSql,
                                                 String expectedLongManualIdSql) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertIntLongAutoAndManualIdFlow(databaseCase.dbType, connection,
                    expectedIntegerAutoIdSql, expectedLongAutoIdSql,
                    expectedIntegerManualIdSql, expectedLongManualIdSql);
        }
    }

    static void assertIntLongAutoAndManualIdFlow(IDbType dbType,
                                                 Connection connection,
                                                 String expectedIntegerAutoIdSql,
                                                 String expectedLongAutoIdSql,
                                                 String expectedIntegerManualIdSql,
                                                 String expectedLongManualIdSql) throws Exception {
        List<IdStrategyCase> idStrategyCases = Arrays.asList(
                new IdStrategyCase(IntegerAutoIdUser.class, INTEGER_AUTO_ID_TABLE, expectedIntegerAutoIdSql),
                new IdStrategyCase(LongAutoIdUser.class, LONG_AUTO_ID_TABLE, expectedLongAutoIdSql),
                new IdStrategyCase(IntegerManualIdUser.class, INTEGER_MANUAL_ID_TABLE, expectedIntegerManualIdSql),
                new IdStrategyCase(LongManualIdUser.class, LONG_MANUAL_ID_TABLE, expectedLongManualIdSql)
        );
        for (IdStrategyCase idStrategyCase : idStrategyCases) {
            dropTestTable(connection, idStrategyCase.tableName);
        }
        try {
            List<Class<?>> entityClasses = new ArrayList<>(idStrategyCases.size());
            for (IdStrategyCase idStrategyCase : idStrategyCases) {
                entityClasses.add(idStrategyCase.entityClass);
            }
            List<String> createExecutedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, createExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .add(entityClasses)
                    .execute(connection);

            for (IdStrategyCase idStrategyCase : idStrategyCases) {
                assertTrue(createExecutedSqlList.stream().anyMatch(sql -> sql.contains(idStrategyCase.tableName)),
                        "Expected executed CREATE TABLE SQL for " + idStrategyCase.tableName + ": " + createExecutedSqlList);
                assertTrue(createExecutedSqlList.stream().anyMatch(sql -> sql.contains(idStrategyCase.expectedIdSql)),
                        "Expected id SQL [" + idStrategyCase.expectedIdSql + "] for "
                                + idStrategyCase.tableName + ": " + createExecutedSqlList);
            }

            for (IdStrategyCase idStrategyCase : idStrategyCases) {
                assertTrue(tableExists(connection, idStrategyCase.tableName));
                assertTrue(columnExists(connection, idStrategyCase.tableName, "id"));
                assertTrue(columnExists(connection, idStrategyCase.tableName, "username"));
            }
            List<String> verifyExecutedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, verifyExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .mode(Mode.UPDATE)
                    .add(entityClasses)
                    .execute(connection);
            assertTrue(verifyExecutedSqlList.isEmpty(),
                    "Expected no id strategy DDL after tables exist: " + verifyExecutedSqlList);
        } finally {
            for (int i = idStrategyCases.size() - 1; i >= 0; i--) {
                dropTestTable(connection, idStrategyCases.get(i).tableName);
            }
        }
    }

    static void assertTableDefinitionFlow(DatabaseCase databaseCase,
                                          Class<?> entityClass,
                                          String tableName,
                                          String expectedCreateSqlFragment,
                                          String expectedTableCommentSql) throws Exception {
        try (Connection connection = openDatabaseConnectionOrSkip(databaseCase)) {
            assertTableDefinitionFlow(databaseCase.dbType, connection, entityClass, tableName,
                    expectedCreateSqlFragment, expectedTableCommentSql);
        }
    }

    static void assertTableDefinitionFlow(IDbType dbType,
                                          Connection connection,
                                          Class<?> entityClass,
                                          String tableName,
                                          String expectedCreateSqlFragment,
                                          String expectedTableCommentSql) throws Exception {
        dropTestTable(connection, tableName);
        try {
            List<String> createExecutedSqlList = new ArrayList<>();
            DDLTestPrinter.ddl(dbType, createExecutedSqlList)
                    .builder(new DefaultDDLBuilder())
                    .add(entityClass)
                    .execute(connection);

            assertTrue(tableExists(connection, tableName));
            assertTrue(columnExists(connection, tableName, "id"));
            assertTrue(columnExists(connection, tableName, "username"));
            if (expectedCreateSqlFragment != null) {
                assertTrue(createExecutedSqlList.stream().anyMatch(sql -> sql.contains(expectedCreateSqlFragment)),
                        "Expected table definition SQL fragment [" + expectedCreateSqlFragment + "]: " + createExecutedSqlList);
            }
            if (expectedTableCommentSql != null) {
                assertTrue(createExecutedSqlList.contains(expectedTableCommentSql),
                        "Expected table comment SQL [" + expectedTableCommentSql + "]: " + createExecutedSqlList);
            }
        } finally {
            dropTestTable(connection, tableName);
        }
    }

    static void dropTestSequence(Connection connection, String sequenceName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SEQUENCE " + sequenceName);
        } catch (SQLException ignored) {
            // 不同数据库缺失序列的错误码不一致，测试清理阶段忽略不存在即可。
        }
    }

    private static Connection openDatabaseConnectionOrSkip(DatabaseCase databaseCase) throws SQLException {
        try {
            Class.forName(databaseCase.driverClassName);
        } catch (ClassNotFoundException exception) {
            assumeTrue(false, "Skip " + databaseCase.name + " integration test, JDBC driver is not available: "
                    + exception.getMessage());
        }
        try {
            return DriverManager.getConnection(databaseCase.url, databaseCase.username, databaseCase.password);
        } catch (SQLException exception) {
            assumeTrue(false, "Skip " + databaseCase.name + " integration test, database is not available: "
                    + exception.getMessage());
            throw exception;
        }
    }

    static void dropTestTable(Connection connection, String tableName) throws SQLException {
        if (!tableExists(connection, tableName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + tableName);
        }
    }

    static boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (MetadataScope scope : metadataScopes(connection)) {
            for (String tableCandidate : nameCandidates(tableName)) {
                try (ResultSet resultSet = metadata.getTables(scope.catalog, scope.schema, tableCandidate, new String[]{"TABLE"})) {
                    if (resultSet.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (MetadataScope scope : metadataScopes(connection)) {
            for (String tableCandidate : nameCandidates(tableName)) {
                for (String columnCandidate : nameCandidates(columnName)) {
                    try (ResultSet resultSet = metadata.getColumns(scope.catalog, scope.schema, tableCandidate, columnCandidate)) {
                        if (resultSet.next()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (MetadataScope scope : metadataScopes(connection)) {
            for (String tableCandidate : nameCandidates(tableName)) {
                try (ResultSet resultSet = metadata.getIndexInfo(scope.catalog, scope.schema, tableCandidate, false, false)) {
                    while (resultSet.next()) {
                        String actualIndexName = resultSet.getString("INDEX_NAME");
                        if (matchesName(actualIndexName, indexName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static List<MetadataScope> metadataScopes(Connection connection) throws SQLException {
        String catalog = connection.getCatalog();
        String schema = getSchema(connection);
        List<MetadataScope> scopes = new ArrayList<>();
        addScope(scopes, catalog, schema);
        addScope(scopes, null, schema);
        addScope(scopes, catalog, null);
        addScope(scopes, null, null);
        return scopes;
    }

    private static void addScope(List<MetadataScope> scopes, String catalog, String schema) {
        MetadataScope scope = new MetadataScope(catalog, schema);
        if (!scopes.contains(scope)) {
            scopes.add(scope);
        }
    }

    private static String getSchema(Connection connection) throws SQLException {
        try {
            return connection.getSchema();
        } catch (SQLFeatureNotSupportedException exception) {
            return null;
        } catch (AbstractMethodError exception) {
            return null;
        }
    }

    private static List<String> nameCandidates(String name) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(name);
        candidates.add(name.toUpperCase(Locale.ROOT));
        candidates.add(name.toLowerCase(Locale.ROOT));
        return new ArrayList<>(candidates);
    }

    private static boolean matchesName(String actualName, String expectedName) {
        if (actualName == null) {
            return false;
        }
        for (String candidate : nameCandidates(expectedName)) {
            if (actualName.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static class IdStrategyCase {

        private final Class<?> entityClass;

        private final String tableName;

        private final String expectedIdSql;

        private IdStrategyCase(Class<?> entityClass, String tableName, String expectedIdSql) {
            this.entityClass = entityClass;
            this.tableName = tableName;
            this.expectedIdSql = expectedIdSql;
        }
    }

    static class DatabaseCase {

        private final IDbType dbType;

        private final String name;

        private final String driverClassName;

        private final String url;

        private final String username;

        private final String password;

        DatabaseCase(IDbType dbType, String name, String driverClassName, String url, String username, String password) {
            this.dbType = dbType;
            this.name = name;
            this.driverClassName = driverClassName;
            this.url = url;
            this.username = username;
            this.password = password;
        }
    }

    @Table(INTEGER_AUTO_ID_TABLE)
    static class IntegerAutoIdUser {

        @TableId
        private Integer id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

    @Table(LONG_AUTO_ID_TABLE)
    static class LongAutoIdUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

    @Table(INTEGER_MANUAL_ID_TABLE)
    static class IntegerManualIdUser {

        @TableId(value = IdAutoType.NONE)
        private Integer id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

    @Table(LONG_MANUAL_ID_TABLE)
    static class LongManualIdUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

    @Table(MULTI_TABLE_INDEX_TABLE)
    @Index(fields = @IndexField(name = "username"))
    static class MultiTableIndexUserV1 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

    @Table(MULTI_TABLE_INDEX_TABLE)
    @Indexs({
            @Index(fields = @IndexField(name = "username")),
            @Index(fields = @IndexField(name = "email"))
    })
    static class MultiTableIndexUserV2 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;

        @ColumnDefinition(length = 128)
        private String email;
    }

    @Table(MULTI_COLUMN_ADD_TABLE)
    static class MultiColumnAddUserV1 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

    @Table(MULTI_COLUMN_ADD_TABLE)
    static class MultiColumnAddUserV2 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;

        private Integer age;

        @ColumnDefinition(length = 128)
        private String email;
    }

    @Table(BOOLEAN_DEFAULT_TABLE)
    static class BooleanNumericDefaultUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(defaultValue = "0")
        private Boolean defaultFalse;

        @ColumnDefinition(defaultValue = "1")
        private Boolean defaultTrue;
    }

    @Table(BOOLEAN_DEFAULT_TABLE)
    static class BooleanLiteralDefaultUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(defaultValue = "FALSE")
        private Boolean defaultFalse;

        @ColumnDefinition(defaultValue = "TRUE")
        private Boolean defaultTrue;
    }

    @Table(BOOLEAN_DEFAULT_TABLE)
    static class BooleanDefaultVariantUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @TableField(defaultValue = "0")
        private Boolean numericFalse;

        @TableField(defaultValue = "1")
        private Boolean numericTrue;

        @TableField(defaultValue = "false")
        private Boolean literalFalse;

        @TableField(defaultValue = "true")
        private Boolean literalTrue;
    }

    @Table(DATE_TIME_DEFAULT_TABLE)
    static class DateTimeDefaultUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @TableField(defaultValue = "{NOW}")
        private java.time.LocalDateTime createdAt;

        @TableField(defaultValue = "{NOW}")
        private Instant eventAt;
    }

    @Table(MULTI_TABLE_SEQUENCE_TABLE)
    @Index(fields = @IndexField(name = "username"))
    static class MultiTableSequenceUserV1 {

        @TableId(dbType = DbType.Name.PGSQL, value = IdAutoType.SQL,
                sql = "select nextval('auto_multi_table_seq')")
        @TableId(dbType = DbType.Name.GAUSS, value = IdAutoType.SQL,
                sql = "select nextval('auto_multi_table_seq')")
        @TableId(dbType = DbType.Name.KING_BASE, value = IdAutoType.SQL,
                sql = "select nextval('auto_multi_table_seq')")
        @TableId(dbType = DbType.Name.ORACLE, value = IdAutoType.SQL,
                sql = "select auto_multi_table_seq.NEXTVAL FROM dual")
        @TableId(dbType = DbType.Name.DM, value = IdAutoType.SQL,
                sql = "select auto_multi_table_seq.NEXTVAL FROM dual")
        @TableId(dbType = DbType.Name.SQL_SERVER, value = IdAutoType.SQL,
                sql = "select next value for auto_multi_table_seq")
        @TableId(dbType = DbType.Name.DB2, value = IdAutoType.SQL,
                sql = "select next value for auto_multi_table_seq from sysibm.sysdummy1")
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }

    @Table(MULTI_TABLE_SEQUENCE_TABLE)
    @Indexs({
            @Index(fields = @IndexField(name = "username")),
            @Index(fields = @IndexField(name = "email"))
    })
    static class MultiTableSequenceUserV2 {

        @TableId(dbType = DbType.Name.PGSQL, value = IdAutoType.SQL,
                sql = "select nextval('auto_multi_table_seq')")
        @TableId(dbType = DbType.Name.GAUSS, value = IdAutoType.SQL,
                sql = "select nextval('auto_multi_table_seq')")
        @TableId(dbType = DbType.Name.KING_BASE, value = IdAutoType.SQL,
                sql = "select nextval('auto_multi_table_seq')")
        @TableId(dbType = DbType.Name.ORACLE, value = IdAutoType.SQL,
                sql = "select auto_multi_table_seq.NEXTVAL FROM dual")
        @TableId(dbType = DbType.Name.DM, value = IdAutoType.SQL,
                sql = "select auto_multi_table_seq.NEXTVAL FROM dual")
        @TableId(dbType = DbType.Name.SQL_SERVER, value = IdAutoType.SQL,
                sql = "select next value for auto_multi_table_seq")
        @TableId(dbType = DbType.Name.DB2, value = IdAutoType.SQL,
                sql = "select next value for auto_multi_table_seq from sysibm.sysdummy1")
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;

        @ColumnDefinition(length = 128)
        private String email;
    }

    private static class MetadataScope {

        private final String catalog;

        private final String schema;

        MetadataScope(String catalog, String schema) {
            this.catalog = catalog;
            this.schema = schema;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MetadataScope)) {
                return false;
            }
            MetadataScope that = (MetadataScope) o;
            return Objects.equals(catalog, that.catalog) && Objects.equals(schema, that.schema);
        }

        @Override
        public int hashCode() {
            return Objects.hash(catalog, schema);
        }
    }
}
