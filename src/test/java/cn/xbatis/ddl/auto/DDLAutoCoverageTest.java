package cn.xbatis.ddl.auto;

import cn.xbatis.core.db.reflect.TableInfo;
import cn.xbatis.core.db.reflect.Tables;
import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.IndexDirection;
import cn.xbatis.db.annotations.*;
import db.sql.api.DbType;
import db.sql.api.IDbType;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DDLAutoCoverageTest {

    @Test
    void autoTableFacadeShouldCoverCustomizationAndDataSourceCreate() throws Exception {
        TestDataSource dataSource = dataSource("facade");
        TestDataSource previewDataSource = dataSource("facade_preview");
        List<String> executedSqlLog = new ArrayList<>();
        DDLAuto ddlAuto = DDLTestPrinter.ddl(DbType.H2)
                .builder(new DefaultDDLBuilder())
                .executionListener(new DDLExecutionListener() {
                    @Override
                    public void afterExecute(String sql, List<String> executedSqlList) {
                        executedSqlLog.add(sql);
                    }
                })
                .mode(Mode.CREATE)
                .add(Collections.singletonList(FacadeUser.class));

        assertEquals(1, ddlAuto.getEntityClasses().size());
        List<Class<?>> entityClasses = ddlAuto.getEntityClasses();
        assertThrows(UnsupportedOperationException.class, () -> entityClasses.add(FacadeUser.class));
        DDLAuto snapshotAuto = DDLTestPrinter.ddl(DbType.H2).add(FacadeUser.class);
        List<Class<?>> snapshotEntityClasses = snapshotAuto.getEntityClasses();
        snapshotAuto.add(BatchSnapshotUser.class);
        assertEquals(1, entityClasses.size());
        assertEquals(1, snapshotEntityClasses.size());
        assertEquals(2, snapshotAuto.getEntityClasses().size());
        assertThrows(NullPointerException.class, () -> DDLTestPrinter.ddl(DbType.H2).add(Collections.singletonList(null)));
        assertThrows(NullPointerException.class, () -> DDLTestPrinter.ddl(DbType.H2).add((Class<?>[]) null));
        assertThrows(NullPointerException.class, () -> DDLTestPrinter.ddl(DbType.H2).add(FacadeUser.class, null));

        List<String> previewSqlList = DDLTestPrinter.ddl(DbType.H2)
                .builder(new DefaultDDLBuilder())
                .add(FacadeUser.class)
                .sqlList(previewDataSource);
        assertEquals(1, previewSqlList.size());
        assertTrue(previewSqlList.get(0).contains("CREATE TABLE IF NOT EXISTS auto_facade_user"));

        ddlAuto.execute(dataSource);
        assertEquals(1, executedSqlLog.size());

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(tableExists(connection, "AUTO_FACADE_USER"));
        }
        try (Connection connection = previewDataSource.getConnection()) {
            assertFalse(tableExists(connection, "AUTO_FACADE_USER"));
        }
    }

    private static boolean sequenceExists(Connection connection, String sequenceName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = ?"
        )) {
            statement.setString(1, sequenceName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    @Test
    void creatorUtilityBranchesShouldBeCovered() throws Exception {
        DefaultDDLAutoExecutor creator = new DefaultDDLAutoExecutor();
        assertTrue(creator.candidates(" ").isEmpty());
        assertTrue(creator.candidates("\"Name\"").contains("Name"));
        assertFalse(creator.candidates("\"Name\"").contains("name"));
        assertEquals("name", creator.normalize("Name"));
        assertEquals("Name", creator.normalize("\"Name\""));
        assertEquals(null, creator.normalize(null));
        DDLExecutionListener.NONE.beforeExecute("SELECT 1", Collections.emptyList());
        DDLExecutionListener.NONE.afterExecute("SELECT 1", Collections.emptyList());
        DDLExecutionListener.NONE.onExecuteError("SELECT 1", new SQLException("test"), Collections.emptyList());

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_execute_sql;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            creator.executeSql(statement, Arrays.asList(null, " ", "CREATE TABLE auto_execute_sql_user (id BIGINT);"));
            assertTrue(tableExists(connection, "AUTO_EXECUTE_SQL_USER"));
        }
    }

    @Test
    void executionListenerShouldRecordExecutedSqlAndFailureContext() throws Exception {
        List<String> beforeSqlList = new ArrayList<>();
        List<String> afterSqlList = new ArrayList<>();
        List<String> errorSqlList = new ArrayList<>();
        DefaultDDLAutoExecutor creator = new DefaultDDLAutoExecutor(new DefaultDDLBuilder(), new DDLExecutionListener() {
            @Override
            public void beforeExecute(String sql, List<String> executedSqlList) {
                beforeSqlList.add(sql + ":" + executedSqlList.size());
            }

            @Override
            public void afterExecute(String sql, List<String> executedSqlList) {
                afterSqlList.add(sql + ":" + executedSqlList.size());
            }

            @Override
            public void onExecuteError(String sql, SQLException exception, List<String> executedSqlList) {
                errorSqlList.add(sql + ":" + executedSqlList.size());
            }
        });

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_listener;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            SQLException exception = assertThrows(SQLException.class, () -> creator.executeSql(statement, Arrays.asList(
                    "CREATE TABLE auto_listener_user (id BIGINT);",
                    "CREATE TABLE auto_listener_user (id BIGINT);"
            )));

            assertEquals(2, beforeSqlList.size());
            assertEquals(1, afterSqlList.size());
            assertEquals(1, errorSqlList.size());
            List<String> executedSqlSnapshot = creator.getExecutedSqlList();
            assertEquals(Collections.singletonList("CREATE TABLE auto_listener_user (id BIGINT);"), executedSqlSnapshot);
            assertThrows(UnsupportedOperationException.class, () -> executedSqlSnapshot.add("SELECT 1"));
            creator.executeSql(statement, Collections.singletonList("CREATE TABLE auto_listener_other (id BIGINT);"));
            assertEquals(1, executedSqlSnapshot.size());
            assertEquals(2, creator.getExecutedSqlList().size());
            assertTrue(exception.getMessage().contains("Failed to execute DDL SQL"));
            assertTrue(exception.getMessage().contains("CREATE TABLE auto_listener_user"));
        }
    }

    @Test
    void executionListenerFailureShouldNotHideSqlException() throws Exception {
        DefaultDDLAutoExecutor creator = new DefaultDDLAutoExecutor(new DefaultDDLBuilder(), new DDLExecutionListener() {
            @Override
            public void onExecuteError(String sql, SQLException exception, List<String> executedSqlList) {
                throw new IllegalStateException("listener failed");
            }
        });

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_listener_error;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            SQLException exception = assertThrows(SQLException.class, () -> creator.executeSql(statement, Collections.singletonList("INVALID SQL")));

            assertTrue(exception.getMessage().contains("INVALID SQL"));
            assertEquals(1, exception.getCause().getSuppressed().length);
        }
    }

    @Test
    void metadataSchemaFallbackBranchesShouldBeCovered() throws Exception {
        MetadataBranchCreator creator = new MetadataBranchCreator();
        Connection connection = metadataConnection("CATALOG");

        assertTrue(creator.exposeTableExists(connection, MetadataSchemaUser.class));

        Set<String> columnNames = creator.getExistsColumnNames(connection, MetadataSchemaUser.class);
        assertTrue(columnNames.contains("fallback_col"));
        assertTrue(creator.fallbackColumnRead);
    }

    @Test
    void metadataSchemaFallbackShouldBeDisabledForSqlServerStyleCatalogs() throws Exception {
        MetadataBranchCreator creator = new NoSchemaCatalogFallbackCreator();
        Connection connection = metadataConnection("master");

        assertFalse(creator.exposeTableExists(connection, MetadataSchemaUser.class));
        assertTrue(creator.getExistsColumnNames(connection, MetadataSchemaUser.class).isEmpty());
        assertFalse(creator.fallbackColumnRead);
    }

    @Test
    void metadataLookupShouldNotMatchSameTableNameFromDifferentSchema() throws Exception {
        ExposedMetadataExecutor creator = new ExposedMetadataExecutor();

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_metadata_schema;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA beta");
            statement.execute("CREATE TABLE beta.same_name_user (id BIGINT)");

            assertFalse(creator.exposeTableExists(connection, MissingSchemaUser.class));
        }
    }

    @Test
    void metadataLookupShouldSupportQuotedIdentifiers() throws Exception {
        ExposedMetadataExecutor creator = new ExposedMetadataExecutor();

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_metadata_quoted;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"CaseSchema\"");
            statement.execute("CREATE TABLE \"CaseSchema\".\"CaseUser\" (\"Id\" BIGINT)");

            assertTrue(creator.exposeTableExists(connection, QuotedCaseUser.class));
            Set<String> columnNames = creator.getExistsColumnNames(connection, QuotedCaseUser.class);
            assertTrue(columnNames.contains("Id"));
            assertTrue(creator.exposeContainsMetadataName(columnNames, "\"Id\""));
            assertFalse(creator.exposeContainsMetadataName(columnNames, "\"id\""));
            assertTrue(creator.exposeIndexedContainsMetadataName(Arrays.asList("ID"), "id"));
            assertTrue(creator.exposeIndexedContainsMetadataName(Arrays.asList("Id"), "\"Id\""));
            assertFalse(creator.exposeIndexedContainsMetadataName(Arrays.asList("Id"), "\"id\""));
            assertTrue(creator.exposeIndexedContainsMetadataName(Collections.singletonList(null), null));
            assertTrue(creator.exposeMatchesMetadataName(null, null));
            assertFalse(creator.exposeMatchesMetadataName(null, "Id"));
            assertTrue(creator.exposeMatchesMetadataName("id", "ID"));
        assertFalse(creator.exposeIsQuotedIdentifier("I"));
        assertEquals("Id", creator.exposeUnquoteIdentifier("\"Id\""));
        assertTrue(creator.exposeMatchesOptionalMetadataName(null, "Id"));
        assertEquals(null, creator.exposeGetString(metadataResultSetWithoutColumns(), "TABLE_NAME"));
    }
    }

    @Test
    void metadataNullSchemaBranchesShouldBeCovered() throws Exception {
        NullSchemaMetadataExecutor creator = new NullSchemaMetadataExecutor();
        Connection nullSchemaConnection = metadataConnection("catalog");
        Connection unsupportedSchemaConnection = proxy(Connection.class, (proxy, method, args) -> {
            if ("getSchema".equals(method.getName())) {
                throw new SQLFeatureNotSupportedException();
            }
            return defaultValue(method.getReturnType());
        });
        Connection abstractMethodSchemaConnection = proxy(Connection.class, (proxy, method, args) -> {
            if ("getSchema".equals(method.getName())) {
                throw new AbstractMethodError();
            }
            return defaultValue(method.getReturnType());
        });

        assertEquals(null, creator.exposeGetSchema(unsupportedSchemaConnection));
        assertEquals(null, creator.exposeGetSchema(abstractMethodSchemaConnection));
        creator.exposeReadTablesWithBlankSchema();
        creator.exposeReadColumnsWithBlankSchema(Tables.get(FacadeUser.class));
        creator.exposeReadIndexesWithBlankSchema(Tables.get(FacadeUser.class));
        assertFalse(creator.exposeTableExists(nullSchemaConnection, FacadeUser.class));
        assertTrue(creator.getExistsColumnNames(nullSchemaConnection, FacadeUser.class).contains("null_schema_col"));
        assertEquals(null, creator.exposeMetadataLookupKey(null));
        assertTrue(creator.exposeEmptyColumnNames(Tables.get(FacadeUser.class)).isEmpty());
        assertTrue(creator.exposeDatabaseMetadataBlankSchemaTable(Tables.get(FacadeUser.class)));
        assertEquals(0, creator.exposeDatabaseMetadataView(Tables.get(FacadeUser.class)));
    }

    @Test
    void metadataUpdateSqlShouldCoverTableInfoAndEntityBridges() throws Exception {
        ExposedMetadataExecutor creator = new ExposedMetadataExecutor();

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_metadata_update;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auto_facade_user (id BIGINT)");

            List<String> expectedSqlList = Collections.singletonList("ALTER TABLE auto_facade_user ADD COLUMN username VARCHAR(255);");
            assertTrue(creator.exposeTableExists(connection, FacadeUser.class));
            assertEquals(expectedSqlList, creator.exposeCreateSqlList(DbType.H2, connection, Mode.UPDATE, FacadeUser.class));
            assertEquals(expectedSqlList, creator.exposeCreateAddColumnSqlList(DbType.H2, connection, FacadeUser.class));
            assertEquals(expectedSqlList, creator.exposeCreateSqlList(DbType.H2, connection, Mode.UPDATE, Tables.get(FacadeUser.class)));
            assertTrue(creator.exposeDatabaseMetadataSchemaCatalogFallback(Tables.get(MetadataSchemaUser.class)));
        }
    }

    @Test
    void metadataSnapshotShouldLoadTablesAndColumnsByEntitySchemaGroup() throws Exception {
        SchemaCountingExecutor creator = new SchemaCountingExecutor();
        List<TableInfo> tableInfos = creator.tableInfos(Arrays.asList(SchemaGroupOne.class, SchemaGroupTwo.class, FacadeUser.class));

        assertEquals(new LinkedHashSet<>(Arrays.asList("schema_group", null)), creator.exposeSchemas(tableInfos));
        assertEquals(new LinkedHashSet<>(Arrays.asList("schema_group", "PUBLIC")), creator.exposeSchemas(tableInfos, "PUBLIC"));

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_schema_group;DB_CLOSE_DELAY=-1")) {
            creator.exposeLoadDatabaseMetadata(connection, tableInfos, true);
            assertFalse(creator.hasTableRead(connection.getCatalog(), null));
            assertFalse(creator.hasColumnRead(connection.getCatalog(), null, "auto_facade_user"));
        }

        assertEquals(new LinkedHashSet<>(creator.tableMetadataReads).size(), creator.tableMetadataReads.size());
        assertEquals(new LinkedHashSet<>(creator.columnMetadataReads).size(), creator.columnMetadataReads.size());
        assertTrue(creator.hasTableReadForSchema("schema_group"));
        assertTrue(creator.hasTableReadForSchema("SCHEMA_GROUP"));
        assertTrue(creator.hasColumnReadForTable("schema_group_one"));
        assertTrue(creator.hasColumnReadForTable("schema_group_two"));
        assertTrue(creator.hasColumnReadForTable("auto_facade_user"));
    }

    @Test
    void metadataSnapshotShouldBatchReadSchemaMetadataWhenEntityCountIsLarge() throws Exception {
        SchemaCountingExecutor creator = new SchemaCountingExecutor();
        List<TableInfo> tableInfos = repeatedTableInfos(FacadeUser.class, 17);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_schema_batch;DB_CLOSE_DELAY=-1")) {
            creator.exposeLoadDatabaseMetadata(connection, tableInfos, true);

            assertTrue(creator.hasTableRead(connection.getCatalog(), "PUBLIC", null));
            assertTrue(creator.hasColumnRead(connection.getCatalog(), "PUBLIC", null));
            assertTrue(creator.hasIndexRead(connection.getCatalog(), "PUBLIC", null));
            assertFalse(creator.hasColumnReadForTable("auto_facade_user"));
        }
    }

    @Test
    void metadataSnapshotShouldFallbackToEntityColumnReadWhenSchemaColumnReadFails() throws Exception {
        SchemaColumnFallbackCountingExecutor creator = new SchemaColumnFallbackCountingExecutor();
        List<TableInfo> tableInfos = repeatedTableInfos(FacadeUser.class, 17);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_schema_column_fallback;DB_CLOSE_DELAY=-1")) {
            creator.exposeLoadDatabaseMetadata(connection, tableInfos, true);

            assertTrue(creator.schemaColumnReadFailed);
            assertTrue(creator.hasColumnReadForTable("auto_facade_user"));
        }
    }

    @Test
    void metadataSnapshotShouldFallbackToEntityIndexReadWhenSchemaIndexReadFails() throws Exception {
        SchemaIndexFallbackCountingExecutor creator = new SchemaIndexFallbackCountingExecutor();
        List<TableInfo> tableInfos = repeatedTableInfos(FacadeUser.class, 17);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_schema_index_fallback;DB_CLOSE_DELAY=-1")) {
            creator.exposeLoadDatabaseMetadata(connection, tableInfos, true);

            assertTrue(creator.schemaIndexReadFailed);
            assertTrue(creator.hasIndexReadForTable("auto_facade_user"));
        }
    }

    @Test
    void metadataSnapshotShouldNotReadSchemaAsCatalogWhenFallbackIsDisabled() throws Exception {
        SchemaCountingExecutor creator = new NoSchemaCatalogFallbackCountingExecutor();
        List<TableInfo> tableInfos = creator.tableInfos(Arrays.asList(SchemaGroupOne.class, SchemaGroupTwo.class));

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_schema_no_catalog_fallback;DB_CLOSE_DELAY=-1")) {
            creator.exposeLoadDatabaseMetadata(connection, tableInfos, true);

            assertTrue(creator.hasTableRead(connection.getCatalog(), "schema_group"));
            assertTrue(creator.hasColumnRead(connection.getCatalog(), "schema_group", "schema_group_one"));
            assertFalse(creator.hasTableRead("schema_group", null));
            assertFalse(creator.hasColumnRead("schema_group", null, "schema_group_one"));
        }
    }

    @Test
    void dbColumnAccessorsShouldBeCovered() {
        ColumnInfo id = column(DbType.H2, FacadeUser.class, "id");
        ColumnInfo username = column(DbType.H2, FacadeUser.class, "username");

        assertNotNull(id.getTableFieldInfo());
        assertNotNull(id.getField());
        assertNotNull(id.getTableId());
        assertEquals(ColumnDefinition.class, id.getDefinition().annotationType());
        assertEquals(IdAutoType.AUTO, id.getIdAutoType());
        assertEquals(IdAutoType.NONE, username.getIdAutoType());
    }

    @Test
    @SuppressWarnings("deprecation")
    void autoTableBuilderAndCreatorOverloadsShouldBeCovered() throws Exception {
        DDLBuilder builder = new DefaultDDLBuilder();
        assertTrue(builder.createTableSql(DbType.H2, FacadeUser.class).contains("CREATE TABLE"));
        assertEquals("ALTER TABLE auto_facade_user ADD COLUMN username VARCHAR(255);",
                builder.addColumnSql(DbType.H2, FacadeUser.class, "username"));
        assertTrue(builder.addColumnSqlList(DbType.H2, FacadeUser.class, Collections.emptyList()).isEmpty());
        assertEquals(Collections.singletonList("ALTER TABLE auto_facade_user ADD COLUMN username VARCHAR(255);"),
                builder.addColumnSqlList(DbType.H2, FacadeUser.class, Collections.singletonList(column(DbType.H2, FacadeUser.class, "username"))));

        DDLBuilder fallbackBuilder = new DDLBuilder() {
            @Override
            public String createTableSql(IDbType dbType, Class<?> entityClass) {
                return "";
            }

            @Override
            public String addColumnSql(IDbType dbType, Class<?> entityClass, String columnName) {
                return columnName;
            }

            @Override
            public List<String> addColumnSqlList(IDbType dbType, Class<?> entityClass, String columnName) {
                return Collections.singletonList(columnName);
            }

            @Override
            public List<ColumnInfo> getColumns(IDbType dbType, Class<?> entityClass) {
                return Collections.emptyList();
            }

            @Override
            public List<String> createTableSqlList(IDbType dbType, Class<?> entityClass) {
                return Collections.emptyList();
            }
        };
        assertEquals(Arrays.asList("id", "username"), fallbackBuilder.addColumnSqlList(DbType.H2, FacadeUser.class, Arrays.asList(
                column(DbType.H2, FacadeUser.class, "id"),
                column(DbType.H2, FacadeUser.class, "username")
        )));
        TableInfo facadeUserTable = Tables.get(FacadeUser.class);
        assertEquals(Arrays.asList("id", "username"), fallbackBuilder.addColumnSqlList(DbType.H2, facadeUserTable, Arrays.asList(
                column(DbType.H2, FacadeUser.class, "id"),
                column(DbType.H2, FacadeUser.class, "username")
        )));
        assertTrue(fallbackBuilder.getColumns(DbType.H2, facadeUserTable).isEmpty());
        assertTrue(fallbackBuilder.getIndexes(DbType.H2, FacadeUser.class).isEmpty());
        assertTrue(fallbackBuilder.getIndexes(DbType.H2, facadeUserTable).isEmpty());
        assertTrue(fallbackBuilder.createIndexSqlList(DbType.H2, facadeUserTable, Collections.<IndexInfo>emptyList()).isEmpty());
        assertTrue(fallbackBuilder.getSequences(DbType.H2, FacadeUser.class).isEmpty());
        assertTrue(fallbackBuilder.getSequences(DbType.H2, facadeUserTable).isEmpty());
        assertTrue(fallbackBuilder.createSequenceSqlList(DbType.H2, Collections.<SequenceInfo>emptyList()).isEmpty());
        assertTrue(fallbackBuilder.createTableSqlList(DbType.H2, facadeUserTable).isEmpty());
        EntityDDLMetadata fallbackMetadata = fallbackBuilder.getEntityDDLMetadata(DbType.H2, facadeUserTable);
        assertSame(facadeUserTable, fallbackMetadata.getTableInfo());
        assertTrue(fallbackMetadata.getColumns().isEmpty());
        assertTrue(fallbackMetadata.getIndexes().isEmpty());
        assertTrue(fallbackMetadata.getSequences().isEmpty());
        assertTrue(fallbackBuilder.createTableSqlList(DbType.H2, fallbackMetadata).isEmpty());

        EntityDDLMetadata defaultMetadata = builder.getEntityDDLMetadata(DbType.H2, facadeUserTable);
        assertSame(facadeUserTable, defaultMetadata.getTableInfo());
        assertEquals(2, defaultMetadata.getColumns().size());
        assertTrue(defaultMetadata.getIndexes().isEmpty());
        assertTrue(defaultMetadata.getSequences().isEmpty());
        assertTrue(builder.createTableSqlList(DbType.H2, defaultMetadata).get(0).contains("CREATE TABLE IF NOT EXISTS auto_facade_user"));

        DefaultDDLAutoExecutor creator = new DefaultDDLAutoExecutor();
        assertTrue(creator.createTableSql(DbType.H2, FacadeUser.class).contains("auto_facade_user"));

        TestDataSource dataSource = dataSource("creator_core_methods");
        assertTrue(creator.sqlList(DbType.H2, dataSource, Mode.CREATE, Collections.emptyList()).isEmpty());
        creator.execute(DbType.H2, dataSource, Collections.emptyList());
        creator.execute(DbType.H2, dataSource);
        creator.execute(DbType.H2, dataSource, Mode.UPDATE);
        creator.execute(DbType.H2, dataSource, Mode.UPDATE, Collections.emptyList());

        try (Connection connection = dataSource.getConnection()) {
            assertThrows(NullPointerException.class, () -> creator.sqlList(DbType.H2, connection, Mode.CREATE, Collections.singletonList(null)));
            assertThrows(NullPointerException.class, () -> creator.execute(DbType.H2, connection, Mode.CREATE, Collections.singletonList(null)));
            creator.execute(DbType.H2, connection, Collections.emptyList());
            creator.execute(DbType.H2, connection);
            creator.execute(DbType.H2, connection, Mode.UPDATE);
            creator.execute(DbType.H2, connection, Mode.UPDATE, Collections.emptyList());
        }
    }

    @Test
    void sequenceSqlShouldCoverBuilderParsingAndDefaults() {
        DefaultDDLBuilder builder = new DefaultDDLBuilder();
        SequenceExposedBuilder exposedBuilder = new SequenceExposedBuilder();

        List<SequenceInfo> postgresqlSequences = builder.getSequences(DbType.PGSQL, DDLAutoTest.SqlSequenceUser.class);
        assertEquals(1, postgresqlSequences.size());
        assertEquals("id_test_id_seq", postgresqlSequences.get(0).getName());
        assertEquals(null, postgresqlSequences.get(0).getSchema());
        assertEquals("id_test_id_seq", postgresqlSequences.get(0).getQualifiedName());
        assertEquals(Collections.singletonList("CREATE SEQUENCE IF NOT EXISTS id_test_id_seq;"),
                builder.createSequenceSqlList(DbType.PGSQL, postgresqlSequences));

        List<SequenceInfo> oracleSequences = builder.getSequences(DbType.ORACLE, DDLAutoTest.SqlSequenceUser.class);
        assertEquals(1, oracleSequences.size());
        assertEquals("id_test_seq", oracleSequences.get(0).getName());
        assertEquals(Collections.singletonList("CREATE SEQUENCE id_test_seq;"),
                builder.createSequenceSqlList(DbType.ORACLE, oracleSequences));

        List<SequenceInfo> sqlServerSequences = builder.getSequences(DbType.SQL_SERVER, DDLAutoTest.SqlSequenceUser.class);
        assertEquals(1, sqlServerSequences.size());
        assertEquals("id_test_sqlserver_seq", sqlServerSequences.get(0).getName());
        assertEquals(Collections.singletonList("CREATE SEQUENCE id_test_sqlserver_seq;"),
                builder.createSequenceSqlList(DbType.SQL_SERVER, sqlServerSequences));

        List<SequenceInfo> db2Sequences = builder.getSequences(DbType.DB2, DDLAutoTest.SqlSequenceUser.class);
        assertEquals(1, db2Sequences.size());
        assertEquals("id_test_db2_seq", db2Sequences.get(0).getName());
        assertEquals(Collections.singletonList("CREATE SEQUENCE id_test_db2_seq;"),
                builder.createSequenceSqlList(DbType.DB2, db2Sequences));

        List<SequenceInfo> genericSequences = builder.getSequences(DbType.H2, GenericSqlSequenceUser.class);
        assertEquals(1, genericSequences.size());
        assertEquals("generic_id_seq", genericSequences.get(0).getName());
        assertEquals(Collections.singletonList("CREATE SEQUENCE generic_id_seq\n    START WITH 1\n    INCREMENT BY 1;"),
                builder.createSequenceSqlList(DbType.H2, genericSequences));
        assertEquals(Collections.singletonList("CREATE SEQUENCE auto_multi_table_seq;"),
                builder.createSequenceSqlList(DbType.GAUSS, Collections.singletonList(new SequenceInfo("auto_multi_table_seq"))));
        assertEquals(Collections.singletonList("CREATE SEQUENCE auto_multi_table_seq;"),
                builder.createSequenceSqlList(DbType.DM, Collections.singletonList(new SequenceInfo("auto_multi_table_seq"))));
        assertEquals(Collections.singletonList("CREATE SEQUENCE auto_multi_table_seq;"),
                builder.createSequenceSqlList(DbType.KING_BASE, Collections.singletonList(new SequenceInfo("auto_multi_table_seq"))));
        assertTrue(builder.createSequenceSqlList(DbType.H2, Collections.<SequenceInfo>emptyList()).isEmpty());
        assertThrows(NullPointerException.class, () -> builder.createSequenceSqlList(DbType.H2, Collections.singletonList((SequenceInfo) null)));

        ColumnInfo postgresqlId = column(DbType.PGSQL, DDLAutoTest.SqlSequenceUser.class, "id");
        ColumnInfo postgresqlUsername = column(DbType.PGSQL, DDLAutoTest.SqlSequenceUser.class, "username");
        assertTrue(exposedBuilder.exposeSqlSequenceId(postgresqlId));
        assertFalse(exposedBuilder.exposeSqlSequenceId(postgresqlUsername));
        assertEquals("id_test_id_seq", exposedBuilder.exposeSequence(DbType.PGSQL, postgresqlId).getName());
        assertEquals(null, exposedBuilder.exposeSequenceName(DbType.PGSQL, ""));
        assertEquals("id_test_id_seq", exposedBuilder.exposeSequenceName(DbType.H2, "select nextval('id_test_id_seq')"));
        assertEquals(null, exposedBuilder.exposePostgresqlSequenceName("select 1"));
        assertEquals("schema.id_test_id_seq", exposedBuilder.exposePostgresqlSequenceName("select nextval('schema.id_test_id_seq'::regclass)"));
        assertEquals(null, exposedBuilder.exposeOracleSequenceName("select 1 from dual"));
        assertEquals("schema.id_test_seq", exposedBuilder.exposeOracleSequenceName("select schema . id_test_seq . NEXTVAL FROM dual"));
        assertEquals(null, exposedBuilder.exposeNextValueForSequenceName("select 1"));
        assertEquals("[dbo].[id_test_seq]", exposedBuilder.exposeNextValueForSequenceName("select next value for [dbo] . [id_test_seq]"));
        assertEquals("generic_id_seq", exposedBuilder.exposeDefaultSequenceName("select next value for generic_id_seq"));

        SequenceInfo sequence = exposedBuilder.exposeToSequenceInfo("\"CaseSchema\".\"CaseSeq\"");
        assertEquals("\"CaseSchema\"", sequence.getSchema());
        assertEquals("\"CaseSeq\"", sequence.getName());
        assertEquals("\"CaseSchema\".\"CaseSeq\"", sequence.getQualifiedName());
        assertEquals("CREATE SEQUENCE \"CaseSchema\".\"CaseSeq\"\n    START WITH 1\n    INCREMENT BY 1;",
                exposedBuilder.exposeBuildCreateSequenceSql(DbType.H2, sequence));
        StringBuilder sequenceName = new StringBuilder();
        exposedBuilder.exposeAppendSequenceName(sequenceName, sequence);
        assertEquals("\"CaseSchema\".\"CaseSeq\"", sequenceName.toString());
        assertThrows(NullPointerException.class, () -> new SequenceInfo(null));
        assertThrows(IllegalArgumentException.class, () -> new SequenceInfo(" "));
    }

    @Test
    void createSqlShouldCoverSchemaRawDefinitionUniqueAndCompositePrimaryKey() {
        DefaultDDLBuilder builder = new DefaultDDLBuilder();

        assertTrue(builder.buildCreateTableSql(DbType.H2, SchemaUser.class)
                .contains("CREATE TABLE IF NOT EXISTS schema_one.schema_user"));
        assertTrue(builder.buildCreateTableSql(DbType.H2, RawDefinitionUser.class)
                .contains("code CHAR(8) NOT NULL DEFAULT '000000'"));
        String definitionArgumentSql = builder.buildCreateTableSql(DbType.H2, DefinitionArgumentUser.class);
        assertTrue(definitionArgumentSql.contains("code VARCHAR(24) DEFAULT 'A001'"));
        assertTrue(definitionArgumentSql.contains("amount DECIMAL(8,3)"));
        assertTrue(definitionArgumentSql.contains("total DECIMAL(8)"));
        assertTrue(definitionArgumentSql.contains("description TEXT"));
        assertTrue(definitionArgumentSql.contains("integer_code INTEGER"));
        assertTrue(builder.buildCreateTableSql(DbType.H2, UniqueCreateUser.class)
                .contains("email VARCHAR(128) UNIQUE"));
        String tableFieldDefaultSql = builder.buildCreateTableSql(DbType.H2, TableFieldDefaultUser.class);
        assertTrue(tableFieldDefaultSql.contains("username VARCHAR(255) DEFAULT 'table_field_user'"));
        assertTrue(tableFieldDefaultSql.contains("amount DECIMAL(19,2) DEFAULT 0"));
        assertTrue(tableFieldDefaultSql.contains("nickname VARCHAR(32) DEFAULT 'column_definition_nickname'"));
        assertTrue(tableFieldDefaultSql.contains("remark VARCHAR(40) NOT NULL DEFAULT 'table_field_remark'"));
        assertFalse(tableFieldDefaultSql.contains("'table_field_nickname'"));
        assertTrue(tableFieldDefaultSql.contains("dynamic_value VARCHAR(255)"));
        assertFalse(tableFieldDefaultSql.contains("DEFAULT {NOW}"));
        assertEquals(ColumnDefinition.class, column(DbType.H2, TableFieldDefaultUser.class, "username")
                .getDefinition()
                .annotationType());
        ColumnDefinition remarkDefinition = column(DbType.H2, TableFieldDefaultUser.class, "remark").getDefinition();
        assertEquals(40, remarkDefinition.length());
        assertFalse(remarkDefinition.nullable());
        assertEquals("备注", remarkDefinition.comment());
        assertEquals("'table_field_remark'", remarkDefinition.defaultValue());
        String compositePrimaryKeySql = builder.buildCreateTableSql(DbType.H2, CompositePrimaryKeyUser.class);
        assertTrue(compositePrimaryKeySql.contains("tenant_id BIGINT NOT NULL"));
        assertTrue(compositePrimaryKeySql.contains("user_id BIGINT NOT NULL"));
        assertTrue(compositePrimaryKeySql.contains("PRIMARY KEY (tenant_id, user_id)"));
        assertFalse(compositePrimaryKeySql.contains("GENERATED BY DEFAULT AS IDENTITY"));
    }

    @Test
    void createSqlShouldResolveDynamicNowDefaultValueByDbType() {
        DefaultDDLBuilder builder = new DefaultDDLBuilder();

        assertDynamicNowDefaultSql(builder, DbType.H2, "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT CURRENT_DATE");
        assertDynamicNowDefaultSql(builder, DbType.PGSQL, "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT CURRENT_DATE");
        assertDynamicNowDefaultSql(builder, DbType.GAUSS, "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT CURRENT_DATE");
        assertDynamicNowDefaultSql(builder, DbType.KING_BASE, "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT CURRENT_DATE");
        assertDynamicNowDefaultSql(builder, DbType.HIGHGO, "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT CURRENT_DATE");
        assertDynamicNowDefaultSql(builder, DbType.SQLITE, "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT CURRENT_DATE");
        assertDynamicNowDefaultSql(builder, DbType.MYSQL, "DATETIME DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT (CURRENT_DATE)");
        assertDynamicNowDefaultSql(builder, DbType.MARIA_DB, "DATETIME DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT (CURRENT_DATE)");
        assertDynamicNowDefaultSql(builder, DbType.OCEAN_BASE, "DATETIME DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT (CURRENT_DATE)");
        assertDynamicNowDefaultSql(builder, DbType.COBAR, "DATETIME DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT (CURRENT_DATE)");
        assertDynamicNowDefaultSql(builder, DbType.SQL_SERVER, "DATETIME2 DEFAULT SYSDATETIME()", "DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET()", "DATE DEFAULT (CAST(GETDATE() AS DATE))");
        assertDynamicNowDefaultSql(builder, DbType.ORACLE, "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT TRUNC(SYSDATE)");
        assertDynamicNowDefaultSql(builder, DbType.DM, "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP", "DATE DEFAULT TRUNC(SYSDATE)");
        assertDynamicNowDefaultSql(builder, DbType.DB2, "TIMESTAMP DEFAULT CURRENT TIMESTAMP", "TIMESTAMP DEFAULT CURRENT TIMESTAMP", "DATE DEFAULT CURRENT DATE");
        assertDynamicNowDefaultSql(builder, DbType.CLICK_HOUSE, "TIMESTAMP DEFAULT now()", "DateTime64(3, 'UTC') DEFAULT now()", "DATE DEFAULT today()");
    }

    @Test
    void createIndexSqlShouldCoverNamedUniqueCompositeIndex() {
        ExposedSqlBuilder builder = new ExposedSqlBuilder();
        TableInfo tableInfo = Tables.get(FacadeUser.class);
        List<IndexInfo> indexes = Collections.singletonList(new IndexInfo(
                "idx_facade_user_username",
                true,
                Collections.singletonList(new IndexInfo.Field("username", IndexDirection.DESC))
        ));

        assertEquals(Collections.singletonList("CREATE UNIQUE INDEX idx_facade_user_username ON auto_facade_user (username DESC);"),
                builder.createIndexSqlList(DbType.H2, tableInfo, indexes));
        assertThrows(UnsupportedOperationException.class, () -> builder.createIndexSqlList(DbType.CLICK_HOUSE, tableInfo, indexes));
        assertTrue(builder.createIndexSqlList(DbType.H2, tableInfo, Collections.<IndexInfo>emptyList()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> builder.createIndexSqlList(DbType.H2, tableInfo,
                Collections.singletonList(new IndexInfo("idx_empty", false, Collections.<IndexInfo.Field>emptyList()))));

        IndexInfo generatedIndex = builder.exposeToIndexInfo(
                tableInfo,
                index("", false,
                        indexField("username", IndexDirection.DEFAULT),
                        indexField("id", IndexDirection.ASC))
        );
        assertEquals("idx_auto_facade_user_username_id", generatedIndex.getName());
        assertFalse(generatedIndex.isUnique());
        assertEquals(2, generatedIndex.getFields().size());
        assertEquals("username", generatedIndex.getFields().get(0).getColumnName());
        assertEquals(IndexDirection.DEFAULT, generatedIndex.getFields().get(0).getDirection());
        assertEquals("CREATE INDEX idx_auto_facade_user_username_id ON auto_facade_user (username, id ASC);",
                builder.createIndexSqlList(DbType.H2, tableInfo, Collections.singletonList(generatedIndex)).get(0));
        IndexInfo longGeneratedIndex = builder.exposeToIndexInfo(
                Tables.get(LongUniqueNameUser.class),
                index("", true, indexField("email", IndexDirection.DEFAULT))
        );
        assertTrue(longGeneratedIndex.getName().startsWith("uk_auto_long_unique_name_user_with_an_e_"));
        assertEquals("raw_value", builder.exposeToIndexField(Tables.get(UnknownTypeUser.class),
                indexField("raw_value", IndexDirection.DESC)).getColumnName());
        assertThrows(IllegalArgumentException.class, () -> builder.exposeToIndexInfo(tableInfo, index("", false)));
        assertThrows(IllegalArgumentException.class, () -> builder.exposeToIndexField(tableInfo, indexField("", IndexDirection.DEFAULT)));
        assertThrows(IllegalArgumentException.class, () -> builder.exposeToIndexField(tableInfo, indexField("missing", IndexDirection.DEFAULT)));
        assertTrue(builder.getIndexes(DbType.H2, FacadeUser.class).isEmpty());
        DefaultDDLBuilder annotatedBuilder = new ExposedSqlBuilder() {
            @Override
            protected List<Index> getIndexAnnotations(Class<?> entityClass) {
                return Collections.singletonList(index("idx_proxy_username", false, indexField("username", IndexDirection.DEFAULT)));
            }
        };
        List<IndexInfo> resolvedIndexes = annotatedBuilder.getIndexes(DbType.H2, tableInfo);
        assertEquals(1, resolvedIndexes.size());
        assertEquals("idx_proxy_username", resolvedIndexes.get(0).getName());

        List<String> createSqlList = DDLTestPrinter.ddl(DbType.H2)
                .builder(new IndexDDLBuilder())
                .add(FacadeUser.class)
                .sqlList();
        assertEquals(2, createSqlList.size());
        assertTrue(createSqlList.get(0).contains("CREATE TABLE IF NOT EXISTS auto_facade_user"));
        assertEquals("CREATE INDEX idx_auto_facade_user_username ON auto_facade_user (username ASC);", createSqlList.get(1));
    }

    @Test
    void indexNameGeneratorShouldKeepStableNamingRules() {
        List<IndexInfo.Field> fields = Arrays.asList(
                new IndexInfo.Field("username", IndexDirection.DEFAULT),
                new IndexInfo.Field("id", IndexDirection.ASC)
        );

        assertEquals("idx_auto_facade_user_username_id",
                DDLIndexNameGenerator.indexName("auto_facade_user", fields, false));
        assertEquals("uk_auto_facade_user_username_id",
                DDLIndexNameGenerator.indexName("auto_facade_user", fields, true));
        assertEquals("idx_auto_facade_user_username_id",
                DDLIndexNameGenerator.indexName("auto-facade.user", fields, false));
        assertEquals("uk_sqlite_unique_user_email",
                DDLIndexNameGenerator.uniqueName("sqlite_unique_user", "email"));
        assertTrue(DDLIndexNameGenerator.uniqueName(
                "auto_long_unique_name_user_with_an_extra_long_name",
                "very_long_email_address_column_name_for_unique_index"
        ).startsWith("uk_auto_long_unique_name_user_with_an_e_"));
    }

    @Test
    void updateModeShouldCreateOnlyMissingIndexes() throws Exception {
        IndexDDLBuilder builder = new IndexDDLBuilder();

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_update_index;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auto_facade_user (id BIGINT, username VARCHAR(255))");

            List<String> sqlList = DDLTestPrinter.ddl(DbType.H2)
                    .builder(builder)
                    .mode(Mode.UPDATE)
                    .add(FacadeUser.class)
                    .sqlList(connection);

            assertEquals(Collections.singletonList("CREATE INDEX idx_auto_facade_user_username ON auto_facade_user (username ASC);"), sqlList);

            DDLTestPrinter.ddl(DbType.H2)
                    .builder(builder)
                    .mode(Mode.UPDATE)
                    .add(FacadeUser.class)
                    .execute(connection);

            assertTrue(indexExists(connection, "AUTO_FACADE_USER", "IDX_AUTO_FACADE_USER_USERNAME"));
            assertTrue(DDLTestPrinter.ddl(DbType.H2)
                    .builder(builder)
                    .mode(Mode.UPDATE)
                    .add(FacadeUser.class)
                    .sqlList(connection)
                    .isEmpty());
        }
    }

    @Test
    void builderValidationBranchesShouldBeCovered() {
        DefaultDDLBuilder builder = new DefaultDDLBuilder();

        assertThrows(IllegalArgumentException.class, () -> DDLTestPrinter.ddl(DbType.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateTableSql(DbType.UNKNOWN, UnknownTypeUser.class));
        assertThrows(IllegalArgumentException.class, () -> builder.buildAddColumnSqlList(
                DbType.UNKNOWN,
                Tables.get(FacadeUser.class),
                Collections.singletonList(column(DbType.H2, FacadeUser.class, "username"))
        ));
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateTableSql(DbType.H2, EmptyColumns.class));
        assertThrows(IllegalArgumentException.class, () -> builder.buildAddColumnSql(DbType.H2, FacadeUser.class, "missing"));
        assertThrows(IllegalArgumentException.class, () -> builder.buildAddColumnSqlList(DbType.H2, FacadeUser.class, "missing"));
        assertThrows(NullPointerException.class, () -> builder.buildAddColumnSqlList(DbType.H2, FacadeUser.class, Collections.singletonList((ColumnInfo) null)));
    }

    @Test
    void updateModeShouldCreateOnlyMissingSequences() throws Exception {
        SequenceDDLBuilder builder = new SequenceDDLBuilder();

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_update_sequence;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auto_facade_user (id BIGINT, username VARCHAR(255))");

            List<String> sqlList = DDLTestPrinter.ddl(DbType.H2)
                    .builder(builder)
                    .mode(Mode.UPDATE)
                    .add(FacadeUser.class)
                    .sqlList(connection);

            assertEquals(Collections.singletonList("CREATE SEQUENCE auto_facade_user_seq\n    START WITH 1\n    INCREMENT BY 1;"), sqlList);

            DDLTestPrinter.ddl(DbType.H2)
                    .builder(builder)
                    .mode(Mode.UPDATE)
                    .add(FacadeUser.class)
                    .execute(connection);

            assertTrue(sequenceExists(connection, "AUTO_FACADE_USER_SEQ"));
            assertTrue(DDLTestPrinter.ddl(DbType.H2)
                    .builder(builder)
                    .mode(Mode.UPDATE)
                    .add(FacadeUser.class)
                    .sqlList(connection)
                    .isEmpty());
        }
    }

    @Test
    void metadataShouldSkipSequenceReadWhenEntitiesDoNotDeclareSequences() throws Exception {
        TableInfo tableInfo = Tables.get(FacadeUser.class);
        List<TableInfo> tableInfos = Collections.singletonList(tableInfo);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_skip_sequence_metadata;DB_CLOSE_DELAY=-1")) {
            SequenceReadCountingExecutor plainCreator = new SequenceReadCountingExecutor();
            assertFalse(plainCreator.exposeShouldReadSequences(DbType.H2, tableInfos));
            assertTrue(plainCreator.exposeShouldReadSequences(null, tableInfos));
            assertTrue(plainCreator.exposeSequenceSchemas(DbType.H2, tableInfos, "PUBLIC").isEmpty());
            plainCreator.exposeLoadDatabaseMetadata(DbType.H2, connection, tableInfos);
            assertEquals(0, plainCreator.sequenceReadCount);

            SequenceReadCountingExecutor sequenceCreator = new SequenceReadCountingExecutor(new SequenceDDLBuilder());
            assertTrue(sequenceCreator.exposeShouldReadSequences(DbType.H2, tableInfos));
            assertEquals(new LinkedHashSet<>(Collections.singletonList("PUBLIC")),
                    sequenceCreator.exposeSequenceSchemas(DbType.H2, tableInfos, "PUBLIC"));
            sequenceCreator.exposeLoadDatabaseMetadata(DbType.H2, connection, tableInfos);
            assertEquals(1, sequenceCreator.sequenceReadCount);
            assertEquals(new LinkedHashSet<>(Collections.singletonList("PUBLIC")), sequenceCreator.sequenceSchemas);

            SequenceReadCountingExecutor schemaSequenceCreator = new SequenceReadCountingExecutor(new SchemaSequenceDDLBuilder());
            assertEquals(new LinkedHashSet<>(Collections.singletonList("sequence_schema")),
                    schemaSequenceCreator.exposeSequenceSchemas(DbType.H2, tableInfos, "PUBLIC"));
        }
    }

    @Test
    void metadataShouldSkipIndexReadWhenEntitiesDoNotDeclareIndexes() throws Exception {
        TableInfo tableInfo = Tables.get(FacadeUser.class);
        List<TableInfo> tableInfos = Collections.singletonList(tableInfo);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_skip_index_metadata;DB_CLOSE_DELAY=-1")) {
            IndexReadCountingExecutor plainCreator = new IndexReadCountingExecutor();
            assertFalse(plainCreator.exposeShouldReadIndexes(DbType.H2, tableInfos));
            assertTrue(plainCreator.exposeShouldReadIndexes(null, tableInfos));
            plainCreator.exposeLoadDatabaseMetadata(DbType.H2, connection, tableInfos);
            assertEquals(0, plainCreator.indexReadCount);

            IndexReadCountingExecutor indexCreator = new IndexReadCountingExecutor(new IndexDDLBuilder());
            assertTrue(indexCreator.exposeShouldReadIndexes(DbType.H2, tableInfos));
            indexCreator.exposeLoadDatabaseMetadata(DbType.H2, connection, tableInfos);
            assertEquals(1, indexCreator.indexReadCount);
        }
    }

    @Test
    void db2SequenceMetadataAndExecutableSqlShouldBeCovered() throws Exception {
        ExposedMetadataExecutor creator = new ExposedMetadataExecutor();
        assertEquals("CREATE SEQUENCE auto_facade_user_seq",
                creator.exposeExecutableSql(DbType.DB2, "CREATE SEQUENCE auto_facade_user_seq;"));
        assertEquals("CREATE SEQUENCE auto_facade_user_seq;",
                creator.exposeExecutableSql(DbType.H2, "CREATE SEQUENCE auto_facade_user_seq;"));
        assertEquals("SELECT 1", creator.exposeExecutableSql(DbType.DB2, "SELECT 1"));

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:auto_table_db2_sequence_metadata;DB_CLOSE_DELAY=-1");
             Statement statement = connection.createStatement()) {
            SequenceFallbackCountingExecutor fallbackCreator = new SequenceFallbackCountingExecutor();
            fallbackCreator.exposeReadSequenceMetadata(DbType.DB2, connection);
            assertEquals(1, fallbackCreator.db2ReadCount);
            assertEquals(0, fallbackCreator.informationSchemaReadCount);

            fallbackCreator.exposeReadSequenceMetadata(DbType.H2, connection);
            assertEquals(1, fallbackCreator.db2ReadCount);
            assertEquals(1, fallbackCreator.informationSchemaReadCount);

            statement.execute("CREATE SCHEMA SYSCAT");
            statement.execute("CREATE TABLE SYSCAT.SEQUENCES (SEQSCHEMA VARCHAR(128), SEQNAME VARCHAR(128))");
            statement.execute("INSERT INTO SYSCAT.SEQUENCES (SEQSCHEMA, SEQNAME) VALUES ('PUBLIC', 'AUTO_FACADE_USER_SEQ')");

            assertTrue(creator.exposeDb2SequenceExists(
                    connection,
                    "PUBLIC",
                    Tables.get(FacadeUser.class),
                    new SequenceInfo("auto_facade_user_seq")
            ));
            assertTrue(creator.exposeSequenceSchemaCatalogFallback(
                    Tables.get(FacadeUser.class),
                    new SequenceInfo("auto_facade_user_seq")
            ));

            statement.execute("CREATE TABLE ALL_SEQUENCES (SEQUENCE_OWNER VARCHAR(128), SEQUENCE_NAME VARCHAR(128))");
            statement.execute("INSERT INTO ALL_SEQUENCES (SEQUENCE_OWNER, SEQUENCE_NAME) VALUES ('PUBLIC', 'AUTO_FACADE_USER_SEQ')");
            assertTrue(creator.exposeOracleSequenceExists(
                    connection,
                    "PUBLIC",
                    Tables.get(FacadeUser.class),
                    new SequenceInfo("auto_facade_user_seq")
            ));
            assertTrue(creator.exposeCreateAddSequenceSqlListWithExistingSequence(
                    DbType.ORACLE,
                    Tables.get(FacadeUser.class),
                    new SequenceInfo("auto_facade_user_seq")
            ).isEmpty());
            assertTrue(creator.exposeUsesOracleSequenceMetadata(DbType.ORACLE));
            assertTrue(creator.exposeUsesOracleSequenceMetadata(DbType.DM));
        }
    }

    @Test
    void defensiveDialectFallbackBranchesShouldBeCovered() {
        ExposedSqlBuilder builder = new ExposedSqlBuilder();
        TableInfo tableInfo = Tables.get(UniqueCreateUser.class);
        ColumnInfo column = column(DbType.H2, UniqueCreateUser.class, "email");
        List<ColumnInfo> columns = builder.getColumns(DbType.H2, FacadeUser.class);
        ColumnInfo username = columns.get(1);

        assertEquals(null, builder.exposeAddUniqueSql(DbType.H2, tableInfo, column));
        assertEquals(null, builder.exposeColumnCommentSql(DbType.H2, tableInfo, column));
        assertEquals(null, builder.exposeTableCommentSql(DbType.H2, tableInfo));
        assertEquals("COMMENT ON TABLE h2_table_comment_user IS 'H2表';",
                builder.exposeTableCommentSql(DbType.H2, Tables.get(DDLAutoTest.H2TableCommentUser.class)));
        assertEquals("ENGINE=InnoDB", builder.exposeTrimStatementTerminator(" ENGINE=InnoDB; "));
        assertTrue(builder.exposeSupportsTableCommentStatement(DbType.DB2));
        assertTrue(builder.exposeCreateTableSql(DbType.H2, FacadeUser.class).contains("CREATE TABLE IF NOT EXISTS auto_facade_user"));
        assertEquals("ALTER TABLE auto_facade_user ADD COLUMN username VARCHAR(255);",
                builder.exposeAddColumnSql(DbType.H2, Tables.get(FacadeUser.class), columns, username));
        assertEquals("ALTER TABLE auto_facade_user ADD COLUMN username VARCHAR(255);",
                builder.exposeAddColumnSqlWithEntityContext(DbType.H2, FacadeUser.class, columns, username));
        assertTrue(builder.exposeColumnSql(DbType.H2, columns.get(0), columns).contains("id BIGINT"));
        assertTrue(builder.exposeColumnSqlWithoutPrimaryKey(DbType.H2, username, columns).contains("username VARCHAR(255)"));
        assertEquals("INTEGER", builder.exposeIntegerType(DbType.H2));
        assertEquals("BIGINT", builder.exposeBigIntType(DbType.H2));
        assertEquals("SMALLINT", builder.exposeSmallIntType(DbType.H2));
        assertEquals("TINYINT", builder.exposeByteType(DbType.H2));
        assertEquals("BOOLEAN", builder.exposeBooleanType(DbType.H2));
        assertEquals("DECIMAL(8,3)", builder.exposeDecimalType(DbType.H2, 8, 3));
        assertEquals("BLOB", builder.exposeBlobType(DbType.H2));
        assertEquals("TIME", builder.exposeTimeType(DbType.H2));
        assertEquals("TIMESTAMP", builder.exposeDateTimeType(DbType.H2));
        assertTrue(builder.exposeColumnCommentSqlList(DbType.PGSQL, UniqueCreateUser.class).isEmpty());
        assertTrue(builder.exposeColumnCommentSqlList(DbType.PGSQL, tableInfo, Collections.singletonList(column)).isEmpty());
        assertEquals(username, builder.exposeFindColumn(FacadeUser.class, columns, "username"));
        assertThrows(IllegalArgumentException.class, () -> builder.exposeFindColumn(FacadeUser.class, columns, "missing"));
        StringBuilder columnName = new StringBuilder();
        builder.appendColumnName(columnName, DbType.H2, Tables.get(FacadeUser.class), username);
        assertEquals("auto_facade_user.username", columnName.toString());
        List<String> addColumnSqlList = new ArrayList<>();
        builder.exposeAppendAddColumnSqlList(DbType.H2, Tables.get(FacadeUser.class), columns, username, addColumnSqlList);
        assertEquals(Collections.singletonList("ALTER TABLE auto_facade_user ADD COLUMN username VARCHAR(255);"), addColumnSqlList);
        List<String> primaryKeySqlList = new ArrayList<>();
        builder.exposeAppendPrimaryKeySql(DbType.H2, builder.getColumns(DbType.H2, CompositePrimaryKeyUser.class), primaryKeySqlList);
        assertEquals(Collections.singletonList("  PRIMARY KEY (tenant_id, user_id)"), primaryKeySqlList);
        assertEquals(Integer.class, builder.exposeEnumSupportCodeType(DDLAutoTest.CodeStatus.class));
        assertEquals(Integer.class, builder.exposeEnumSupportCodeType(DDLAutoTest.CodeStatus.class));
        assertEquals(null, builder.exposeEnumSupportCodeType(DDLAutoTest.FallbackStatus.class));
        assertEquals(null, builder.exposeEnumSupportCodeType(DDLAutoTest.FallbackStatus.class));
    }

    @Test
    void sqliteUniqueIndexNameShouldBeShortenedWhenNameIsTooLong() {
        List<String> sqlList = new DefaultDDLBuilder().addColumnSqlList(
                DbType.SQLITE,
                LongUniqueNameUser.class,
                "very_long_email_address_column_name_for_unique_index"
        );

        assertEquals(2, sqlList.size());
        assertTrue(sqlList.get(1).startsWith("CREATE UNIQUE INDEX uk_auto_long_unique_name_user_with_an_e_"));
        assertTrue(sqlList.get(1).contains(" ON auto_long_unique_name_user_with_an_extra_long_name"));
    }

    @Test
    void sqlServerSchemaCommentShouldUseConfiguredSchema() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.SQL_SERVER)
                .add(SqlServerSchemaCommentUser.class)
                .sqlList();

        assertEquals(2, sqlList.size());
        assertTrue(sqlList.get(1).contains("@level0name=N'app_schema'"));
        assertFalse(sqlList.get(1).contains("DECLARE @schema"));
    }

    private static ColumnInfo column(IDbType dbType, Class<?> entityClass, String columnName) {
        return new DefaultDDLBuilder().getColumns(dbType, entityClass)
                .stream()
                .filter(item -> item.getName().equals(columnName))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static void assertDynamicNowDefaultSql(DefaultDDLBuilder builder, DbType dbType,
                                                   String dateTimeColumnSql,
                                                   String instantColumnSql,
                                                   String dateColumnSql) {
        String sql = builder.buildCreateTableSql(dbType, DynamicNowDefaultUser.class);
        assertTrue(sql.contains("created_at " + dateTimeColumnSql), dbType.getName() + " created_at SQL: " + sql);
        assertTrue(sql.contains("event_at " + instantColumnSql), dbType.getName() + " event_at SQL: " + sql);
        assertTrue(sql.contains("biz_date " + dateColumnSql), dbType.getName() + " biz_date SQL: " + sql);
        assertTrue(sql.contains("today_date " + dateColumnSql), dbType.getName() + " today_date SQL: " + sql);
    }

    private static List<TableInfo> repeatedTableInfos(Class<?> entityClass, int count) {
        List<TableInfo> tableInfos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tableInfos.add(Tables.get(entityClass));
        }
        return tableInfos;
    }

    private static Index index(String name, boolean unique, IndexField... fields) {
        return annotation(Index.class, new LinkedHashMap<String, Object>() {{
            put("name", name);
            put("unique", unique);
            put("fields", fields);
        }});
    }

    private static IndexField indexField(String name, IndexDirection direction) {
        return annotation(IndexField.class, new LinkedHashMap<String, Object>() {{
            put("name", name);
            put("direction", direction);
        }});
    }

    private static <T> T annotation(Class<T> type, Map<String, Object> values) {
        return proxy(type, (proxy, method, args) -> {
            if ("annotationType".equals(method.getName())) {
                return type;
            }
            if (values.containsKey(method.getName())) {
                return values.get(method.getName());
            }
            return method.getDefaultValue();
        });
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private static boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (resultSet.next()) {
                String actualIndexName = resultSet.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(actualIndexName)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    void dialectSqlShouldCoverMoreDatabaseBranches() {
        DefaultDDLBuilder builder = new DefaultDDLBuilder(new DDLDialect());

        String sqliteSql = builder.buildCreateTableSql(DbType.SQLITE, SqliteAutoUser.class);
        assertTrue(sqliteSql.contains("id INTEGER PRIMARY KEY AUTOINCREMENT"));
        String sqliteAutoDefinitionSql = builder.buildCreateTableSql(DbType.SQLITE, SqliteAutoDefinitionUser.class);
        assertTrue(sqliteAutoDefinitionSql.contains("id INTEGER PRIMARY KEY AUTOINCREMENT"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildCreateTableSql(DbType.SQLITE, SqliteInvalidAutoDefinitionUser.class));

        String oracleSql = builder.buildCreateTableSql(DbType.ORACLE, OracleTypeMatrix.class);
        assertTrue(oracleSql.contains("short_value NUMBER(5)"));
        assertTrue(oracleSql.contains("byte_value NUMBER(3)"));
        assertTrue(oracleSql.contains("enabled NUMBER(1)"));
        assertTrue(oracleSql.contains("amount NUMBER(9,2)"));
        assertTrue(oracleSql.contains("ratio BINARY_FLOAT"));
        assertTrue(oracleSql.contains("large_text CLOB"));

        String dmIntegerAutoSql = builder.buildCreateTableSql(DbType.DM, DmIntegerAutoUser.class);
        assertTrue(dmIntegerAutoSql.contains("id INTEGER IDENTITY(1,1) NOT NULL PRIMARY KEY"));
        String dmLongAutoSql = builder.buildCreateTableSql(DbType.DM, DmLongAutoUser.class);
        assertTrue(dmLongAutoSql.contains("id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY"));

        String sqlServerSql = builder.buildCreateTableSql(DbType.SQL_SERVER, SqlServerTypeMatrix.class);
        assertTrue(sqlServerSql.contains("large_text NVARCHAR(MAX)"));
        assertTrue(sqlServerSql.contains("payload VARBINARY(MAX)"));
        assertTrue(sqlServerSql.contains("enabled BIT"));

        String pgSql = builder.buildCreateTableSql(DbType.PGSQL, PostgresqlBlobUser.class);
        assertTrue(pgSql.contains("payload BYTEA"));

        String pgIntegerAutoSql = builder.buildCreateTableSql(DbType.PGSQL, PostgresqlIntegerAutoUser.class);
        assertTrue(pgIntegerAutoSql.contains("id INTEGER GENERATED BY DEFAULT AS IDENTITY"));
        String gaussIntegerAutoSql = builder.buildCreateTableSql(DbType.GAUSS, GaussIntegerAutoUser.class);
        assertTrue(gaussIntegerAutoSql.contains("id SERIAL NOT NULL PRIMARY KEY"));
        String gaussLongAutoSql = builder.buildCreateTableSql(DbType.GAUSS, GaussLongAutoUser.class);
        assertTrue(gaussLongAutoSql.contains("id BIGSERIAL NOT NULL PRIMARY KEY"));

        String mysqlSql = builder.buildCreateTableSql(DbType.MYSQL, MysqlTypeMatrix.class);
        assertTrue(mysqlSql.contains("amount DOUBLE"));
        assertTrue(mysqlSql.contains("created_at DATETIME"));
        assertTrue(mysqlSql.contains("enabled TINYINT(1)"));

        String fallbackTypeSql = builder.buildCreateTableSql(DbType.H2, UnknownTypeUser.class);
        assertTrue(fallbackTypeSql.contains("raw_value VARCHAR(255)"));
        assertTrue(fallbackTypeSql.contains("byte_value TINYINT"));
        assertTrue(fallbackTypeSql.contains("name VARCHAR(255)"));

        assertEquals("VARCHAR(255)", builder.getStringType(DbType.H2, 0));
        assertThrows(NullPointerException.class, () -> new DDLDialect().getStringType(null, 1));
        assertThrows(IllegalArgumentException.class, () -> builder.getStringType(DbType.UNKNOWN, 0));
        assertThrows(IllegalArgumentException.class, () -> builder.getAutoIncrementSql(DbType.UNKNOWN));
    }

    private static TestDataSource dataSource(String databaseName) {
        return new TestDataSource("jdbc:h2:mem:auto_table_" + databaseName + ";DB_CLOSE_DELAY=-1");
    }

    private static Connection metadataConnection(String catalog) {
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (proxy, method, args) -> defaultValue(method.getReturnType()));
        return proxy(Connection.class, (proxy, method, args) -> {
            if ("getMetaData".equals(method.getName())) {
                return metaData;
            }
            if ("getCatalog".equals(method.getName())) {
                return catalog;
            }
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler invocationHandler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, invocationHandler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    static class ExposedSqlBuilder extends DefaultDDLBuilder {

        String exposeCreateTableSql(IDbType dbType, Class<?> entityClass) {
            TableInfo tableInfo = Tables.get(entityClass);
            return buildCreateTableSql(dbType, entityClass, tableInfo, getColumns(dbType, tableInfo));
        }

        String exposeAddColumnSql(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, ColumnInfo column) {
            return buildAddColumnSql(dbType, tableInfo, columns, column);
        }

        String exposeAddColumnSqlWithEntityContext(IDbType dbType, Class<?> entityClass, List<ColumnInfo> columns, ColumnInfo column) {
            return buildAddColumnSql(createContext(dbType, entityClass, columns), column);
        }

        void exposeAppendAddColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, ColumnInfo column, List<String> sqlList) {
            appendAddColumnSqlList(dbType, tableInfo, columns, column, sqlList);
        }

        String exposeAddUniqueSql(IDbType dbType, TableInfo tableInfo, ColumnInfo column) {
            return buildAddUniqueSql(dbType, tableInfo, column);
        }

        String exposeColumnCommentSql(IDbType dbType, TableInfo tableInfo, ColumnInfo column) {
            return buildColumnCommentSql(dbType, tableInfo, column);
        }

        String exposeTableCommentSql(IDbType dbType, TableInfo tableInfo) {
            return buildTableCommentSql(dbType, tableInfo);
        }

        String exposeColumnSql(IDbType dbType, ColumnInfo column, List<ColumnInfo> columns) {
            return buildColumnSql(dbType, column, columns);
        }

        String exposeColumnSqlWithoutPrimaryKey(IDbType dbType, ColumnInfo column, List<ColumnInfo> columns) {
            return buildColumnSql(dbType, column, columns, false);
        }

        String exposeIntegerType(IDbType dbType) {
            return getIntegerType(dbType);
        }

        String exposeBigIntType(IDbType dbType) {
            return getBigIntType(dbType);
        }

        String exposeSmallIntType(IDbType dbType) {
            return getSmallIntType(dbType);
        }

        String exposeByteType(IDbType dbType) {
            return getByteType(dbType);
        }

        String exposeBooleanType(IDbType dbType) {
            return getBooleanType(dbType);
        }

        String exposeDecimalType(IDbType dbType, int precision, int scale) {
            return getDecimalType(dbType, precision, scale);
        }

        String exposeBlobType(IDbType dbType) {
            return getBlobType(dbType);
        }

        String exposeTimeType(IDbType dbType) {
            return getTimeType(dbType);
        }

        String exposeDateTimeType(IDbType dbType) {
            return getDateTimeType(dbType);
        }

        ColumnInfo exposeFindColumn(Class<?> entityClass, List<ColumnInfo> columns, String columnName) {
            return findColumn(entityClass, columns, columnName);
        }

        void exposeAppendPrimaryKeySql(IDbType dbType, List<ColumnInfo> columns, List<String> ddlItems) {
            appendPrimaryKeySql(dbType, columns, ddlItems);
        }

        List<String> exposeColumnCommentSqlList(IDbType dbType, Class<?> entityClass) {
            return buildColumnCommentSqlList(dbType, entityClass);
        }

        List<String> exposeColumnCommentSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns) {
            return buildColumnCommentSqlList(dbType, tableInfo, columns);
        }

        String exposeTrimStatementTerminator(String value) {
            return trimStatementTerminator(value);
        }

        boolean exposeSupportsTableCommentStatement(IDbType dbType) {
            return supportsTableCommentStatement(dbType);
        }

        Class<?> exposeEnumSupportCodeType(Class<?> enumType) {
            return getEnumSupportCodeType(enumType);
        }

        IndexInfo exposeToIndexInfo(TableInfo tableInfo, Index index) {
            return toIndexInfo(tableInfo, index);
        }

        IndexInfo.Field exposeToIndexField(TableInfo tableInfo, IndexField indexField) {
            return toIndexField(tableInfo, indexField);
        }

        @Override
        protected boolean supportsInlineUniqueInAddColumn(IDbType dbType) {
            return false;
        }

        @Override
        protected boolean isSqlite(IDbType dbType) {
            return false;
        }

        @Override
        protected boolean supportsColumnCommentStatement(IDbType dbType) {
            return true;
        }

        @Override
        protected boolean isPostgresql(IDbType dbType) {
            return false;
        }

        @Override
        protected boolean isOracle(IDbType dbType) {
            return false;
        }

        @Override
        protected boolean isSqlServer(IDbType dbType) {
            return false;
        }
    }

    static class IndexDDLBuilder extends DefaultDDLBuilder {

        @Override
        public List<IndexInfo> getIndexes(IDbType dbType, TableInfo tableInfo) {
            if (tableInfo.getType() == FacadeUser.class) {
                return Collections.singletonList(new IndexInfo(
                        "idx_auto_facade_user_username",
                        false,
                        Collections.singletonList(new IndexInfo.Field("username", IndexDirection.ASC))
                ));
            }
            return Collections.emptyList();
        }
    }

    static class SequenceExposedBuilder extends DefaultDDLBuilder {

        SequenceInfo exposeSequence(IDbType dbType, ColumnInfo column) {
            return getSequence(dbType, column);
        }

        boolean exposeSqlSequenceId(ColumnInfo column) {
            return isSqlSequenceId(column);
        }

        String exposeSequenceName(IDbType dbType, String sql) {
            return getSequenceName(dbType, sql);
        }

        String exposePostgresqlSequenceName(String sql) {
            return getPostgresqlSequenceName(sql);
        }

        String exposeOracleSequenceName(String sql) {
            return getOracleSequenceName(sql);
        }

        String exposeNextValueForSequenceName(String sql) {
            return getNextValueForSequenceName(sql);
        }

        String exposeDefaultSequenceName(String sql) {
            return getDefaultSequenceName(sql);
        }

        SequenceInfo exposeToSequenceInfo(String sequenceName) {
            return toSequenceInfo(sequenceName);
        }

        String exposeBuildCreateSequenceSql(IDbType dbType, SequenceInfo sequence) {
            return buildCreateSequenceSql(dbType, sequence);
        }

        void exposeAppendSequenceName(StringBuilder ddl, SequenceInfo sequence) {
            appendSequenceName(ddl, sequence);
        }
    }

    static class SequenceDDLBuilder extends DefaultDDLBuilder {

        @Override
        public List<SequenceInfo> getSequences(IDbType dbType, TableInfo tableInfo) {
            if (tableInfo.getType() == FacadeUser.class) {
                return Collections.singletonList(new SequenceInfo("auto_facade_user_seq"));
            }
            return Collections.emptyList();
        }
    }

    static class SchemaSequenceDDLBuilder extends DefaultDDLBuilder {

        @Override
        public List<SequenceInfo> getSequences(IDbType dbType, TableInfo tableInfo) {
            if (tableInfo.getType() == FacadeUser.class) {
                return Collections.singletonList(new SequenceInfo("sequence_schema", "auto_facade_user_seq"));
            }
            return Collections.emptyList();
        }
    }

    static class MetadataBranchCreator extends DefaultDDLAutoExecutor {

        private boolean fallbackColumnRead;

        boolean exposeTableExists(Connection connection, Class<?> entityClass) throws SQLException {
            return tableExists(connection, entityClass);
        }

        @Override
        protected boolean tableExists(DatabaseMetaData metaData, String catalog, String schema, String tableName, String[] types) {
            return "schema_meta".equals(catalog) && schema == null;
        }

        @Override
        protected void readColumnNames(DatabaseMetaData metaData, String catalog, String schema, String tableName, Set<String> columnNames) {
            if ("schema_meta".equals(catalog) && schema == null) {
                fallbackColumnRead = true;
                columnNames.add("fallback_col");
            }
        }
    }

    static class NoSchemaCatalogFallbackCreator extends MetadataBranchCreator {

        @Override
        protected boolean supportsSchemaAsCatalogFallback(DatabaseMetaData metaData) {
            return false;
        }
    }

    static class ExposedMetadataExecutor extends DefaultDDLAutoExecutor {

        boolean exposeTableExists(Connection connection, Class<?> entityClass) throws SQLException {
            return tableExists(connection, entityClass);
        }

        List<String> exposeCreateSqlList(IDbType dbType, Connection connection, Mode mode, TableInfo tableInfo) throws SQLException {
            return createSqlList(dbType, connection, mode, tableInfo);
        }

        List<String> exposeCreateSqlList(IDbType dbType, Connection connection, Mode mode, Class<?> entityClass) throws SQLException {
            return createSqlList(dbType, connection, mode, entityClass);
        }

        List<String> exposeCreateAddColumnSqlList(IDbType dbType, Connection connection, Class<?> entityClass) throws SQLException {
            return createAddColumnSqlList(dbType, connection, entityClass);
        }

        boolean exposeDatabaseMetadataSchemaCatalogFallback(TableInfo tableInfo) {
            DatabaseMetadata databaseMetadata = new DatabaseMetadata("catalog");
            databaseMetadata.addTable(tableInfo.getSchema(), null, tableInfo.getTableName());
            return databaseMetadata.objectType(tableInfo) == 1;
        }

        boolean exposeContainsMetadataName(Set<String> actualNames, String expectedName) {
            return containsMetadataName(actualNames, expectedName);
        }

        boolean exposeIndexedContainsMetadataName(List<String> actualNames, String expectedName) {
            return metadataNameIndex(actualNames).contains(expectedName);
        }

        String exposeGetString(ResultSet resultSet, String columnLabel) throws SQLException {
            return getString(resultSet, columnLabel);
        }

        boolean exposeMatchesMetadataName(String expectedName, String actualName) {
            return matchesMetadataName(expectedName, actualName);
        }

        boolean exposeMatchesOptionalMetadataName(String expectedName, String actualName) {
            return matchesOptionalMetadataName(expectedName, actualName);
        }

        boolean exposeIsQuotedIdentifier(String value) {
            return isQuotedIdentifier(value);
        }

        String exposeUnquoteIdentifier(String value) {
            return unquoteIdentifier(value);
        }

        String exposeGetSchema(Connection connection) throws SQLException {
            return getSchema(connection);
        }

        String exposeMetadataLookupKey(String value) {
            return metadataLookupKey(value);
        }

        String exposeExecutableSql(IDbType dbType, String sql) {
            return executableSql(dbType, sql);
        }

        boolean exposeDb2SequenceExists(Connection connection, String schema, TableInfo tableInfo, SequenceInfo sequence) throws SQLException {
            DatabaseMetadata databaseMetadata = new DatabaseMetadata(connection.getCatalog(), schema);
            readDb2SequenceMetadata(connection.getMetaData(), schema, databaseMetadata);
            return databaseMetadata.sequenceExists(tableInfo, sequence);
        }

        boolean exposeOracleSequenceExists(Connection connection, String schema, TableInfo tableInfo, SequenceInfo sequence) throws SQLException {
            DatabaseMetadata databaseMetadata = new DatabaseMetadata(connection.getCatalog(), schema);
            readOracleSequenceMetadata(connection.getMetaData(), schema, databaseMetadata);
            return databaseMetadata.sequenceExists(tableInfo, sequence);
        }

        List<String> exposeCreateAddSequenceSqlListWithExistingSequence(IDbType dbType, TableInfo tableInfo, SequenceInfo sequence) {
            DatabaseMetadata databaseMetadata = new DatabaseMetadata("catalog", "PUBLIC");
            databaseMetadata.addSequence("catalog", "PUBLIC", sequence.getName());
            return createAddSequenceSqlList(dbType, tableInfo, Collections.singletonList(sequence), databaseMetadata);
        }

        boolean exposeUsesOracleSequenceMetadata(IDbType dbType) {
            return usesOracleSequenceMetadata(dbType);
        }

        boolean exposeSequenceSchemaCatalogFallback(TableInfo tableInfo, SequenceInfo sequence) {
            DatabaseMetadata databaseMetadata = new DatabaseMetadata("catalog", "sequence_schema");
            databaseMetadata.addSequence("sequence_schema", null, sequence.getName());
            return databaseMetadata.sequenceExists(tableInfo, sequence);
        }

        Set<String> exposeEmptyColumnNames(TableInfo tableInfo) {
            return new DatabaseMetadata("catalog").getColumnNames(tableInfo);
        }

        boolean exposeDatabaseMetadataBlankSchemaTable(TableInfo tableInfo) {
            DatabaseMetadata databaseMetadata = new DatabaseMetadata("catalog");
            databaseMetadata.addTable("catalog", null, tableInfo.getTableName(), null);
            return databaseMetadata.objectType(tableInfo) == 1;
        }

        int exposeDatabaseMetadataView(TableInfo tableInfo) {
            DatabaseMetadata databaseMetadata = new DatabaseMetadata("catalog");
            databaseMetadata.addTable("catalog", null, tableInfo.getTableName(), "VIEW");
            return databaseMetadata.objectType(tableInfo);
        }
    }

    static class SequenceFallbackCountingExecutor extends ExposedMetadataExecutor {

        private int db2ReadCount;

        private int informationSchemaReadCount;

        void exposeReadSequenceMetadata(IDbType dbType, Connection connection) throws SQLException {
            readSequenceMetadata(dbType, connection.getMetaData(), connection.getCatalog(), null,
                    new DatabaseMetadata(connection.getCatalog(), null));
        }

        @Override
        protected void readDb2SequenceMetadata(DatabaseMetaData metaData, String schema, DatabaseMetadata databaseMetadata) {
            db2ReadCount++;
        }

        @Override
        protected void readInformationSchemaSequenceMetadata(DatabaseMetaData metaData, String schema, DatabaseMetadata databaseMetadata) {
            informationSchemaReadCount++;
        }
    }

    static class SequenceReadCountingExecutor extends DefaultDDLAutoExecutor {

        private int sequenceReadCount;

        private final Set<String> sequenceSchemas = new LinkedHashSet<>();

        SequenceReadCountingExecutor() {
            super();
        }

        SequenceReadCountingExecutor(DDLBuilder ddlBuilder) {
            super(ddlBuilder);
        }

        void exposeLoadDatabaseMetadata(IDbType dbType, Connection connection, Collection<TableInfo> tableInfos) throws SQLException {
            loadDatabaseMetadata(dbType, connection, tableInfos, false);
        }

        boolean exposeShouldReadSequences(IDbType dbType, Collection<TableInfo> tableInfos) {
            return shouldReadSequences(dbType, tableInfos);
        }

        Set<String> exposeSequenceSchemas(IDbType dbType, Collection<TableInfo> tableInfos, String defaultSchema) {
            return sequenceSchemas(dbType, tableInfos, defaultSchema);
        }

        @Override
        protected void readSequences(IDbType dbType, DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<String> schemas) {
            sequenceReadCount++;
            sequenceSchemas.addAll(schemas);
        }
    }

    static class IndexReadCountingExecutor extends DefaultDDLAutoExecutor {

        private int indexReadCount;

        IndexReadCountingExecutor() {
            super();
        }

        IndexReadCountingExecutor(DDLBuilder ddlBuilder) {
            super(ddlBuilder);
        }

        void exposeLoadDatabaseMetadata(IDbType dbType, Connection connection, Collection<TableInfo> tableInfos) throws SQLException {
            loadDatabaseMetadata(dbType, connection, tableInfos, true);
        }

        boolean exposeShouldReadIndexes(IDbType dbType, Collection<TableInfo> tableInfos) {
            return shouldReadIndexes(dbType, tableInfos);
        }

        @Override
        protected void readIndexes(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) {
            indexReadCount++;
        }
    }

    static class NullSchemaMetadataExecutor extends ExposedMetadataExecutor {

        void exposeReadTablesWithBlankSchema() throws SQLException {
            readTables(null, new DatabaseMetadata("catalog"), (String) null);
        }

        void exposeReadColumnsWithBlankSchema(TableInfo tableInfo) throws SQLException {
            readColumns(null, new DatabaseMetadata("catalog"), tableInfo, new LinkedHashSet<>());
        }

        void exposeReadIndexesWithBlankSchema(TableInfo tableInfo) throws SQLException {
            readIndexes(null, new DatabaseMetadata("catalog"), tableInfo, new LinkedHashSet<>());
        }

        @Override
        protected void readTableMetadata(DatabaseMetaData metaData, String catalog, String schema, DatabaseMetadata databaseMetadata) {
        }

        @Override
        protected void readColumnMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) {
        }

        @Override
        protected void readIndexMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) {
        }

        @Override
        protected boolean tableExists(DatabaseMetaData metaData, String catalog, String schema, String tableName, String[] types) {
            return false;
        }

        @Override
        protected void readColumnNames(DatabaseMetaData metaData, String catalog, String schema, String tableName, Set<String> columnNames) {
            columnNames.add("null_schema_col");
        }
    }

    static class SchemaCountingExecutor extends DefaultDDLAutoExecutor {

        private final List<String> tableMetadataReads = new ArrayList<>();

        private final List<String> columnMetadataReads = new ArrayList<>();

        private final List<String> indexMetadataReads = new ArrayList<>();

        void exposeLoadDatabaseMetadata(Connection connection, Collection<TableInfo> tableInfos, boolean includeColumns) throws SQLException {
            loadDatabaseMetadata(connection, tableInfos, includeColumns);
        }

        Set<String> exposeSchemas(Collection<TableInfo> tableInfos) {
            return schemas(tableInfos);
        }

        Set<String> exposeSchemas(Collection<TableInfo> tableInfos, String defaultSchema) {
            return schemas(tableInfos, defaultSchema);
        }

        boolean hasTableReadForSchema(String schema) {
            for (String read : tableMetadataReads) {
                if (read.contains("|" + schema + "|") || read.endsWith("|" + schema)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasTableRead(String catalog, String schema) {
            String readPrefix = readKey(catalog, schema);
            for (String read : tableMetadataReads) {
                if (read.equals(readPrefix) || read.startsWith(readPrefix + "|")) {
                    return true;
                }
            }
            return false;
        }

        boolean hasTableRead(String catalog, String schema, String tableName) {
            for (String read : tableMetadataReads) {
                if (read.equals(readKey(catalog, schema, tableName))) {
                    return true;
                }
            }
            return false;
        }

        boolean hasColumnRead(String catalog, String schema, String tableName) {
            for (String read : columnMetadataReads) {
                if (read.equals(readKey(catalog, schema, tableName))) {
                    return true;
                }
            }
            return false;
        }

        boolean hasIndexRead(String catalog, String schema, String tableName) {
            for (String read : indexMetadataReads) {
                if (read.equals(readKey(catalog, schema, tableName))) {
                    return true;
                }
            }
            return false;
        }

        boolean hasIndexReadForTable(String tableName) {
            for (String read : indexMetadataReads) {
                if (read.endsWith("|" + tableName)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasColumnReadForTable(String tableName) {
            for (String read : columnMetadataReads) {
                if (read.endsWith("|" + tableName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void readTableMetadata(DatabaseMetaData metaData, String catalog, String schema, DatabaseMetadata databaseMetadata) {
            readTableMetadata(metaData, catalog, schema, null, databaseMetadata);
        }

        @Override
        protected void readTableMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) {
            tableMetadataReads.add(readKey(catalog, schema, tableName));
            databaseMetadata.addTable(catalog, schema, "schema_group_one");
            databaseMetadata.addTable(catalog, schema, "schema_group_two");
            databaseMetadata.addTable(catalog, schema, "auto_facade_user");
        }

        @Override
        protected void readColumnMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) throws SQLException {
            columnMetadataReads.add(readKey(catalog, schema, tableName));
        }

        @Override
        protected void readIndexMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) throws SQLException {
            indexMetadataReads.add(readKey(catalog, schema, tableName));
        }

        private String readKey(String catalog, String schema) {
            return (catalog == null ? "<null>" : catalog) + "|" + (schema == null ? "<null>" : schema);
        }

        private String readKey(String catalog, String schema, String tableName) {
            return readKey(catalog, schema) + "|" + (tableName == null ? "<null>" : tableName);
        }
    }

    static class NoSchemaCatalogFallbackCountingExecutor extends SchemaCountingExecutor {

        @Override
        protected boolean supportsSchemaAsCatalogFallback(DatabaseMetaData metaData) {
            return false;
        }
    }

    static class SchemaIndexFallbackCountingExecutor extends SchemaCountingExecutor {

        private boolean schemaIndexReadFailed;

        @Override
        protected void readIndexMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) throws SQLException {
            if (tableName == null) {
                schemaIndexReadFailed = true;
                throw new SQLException("schema index read failed");
            }
            super.readIndexMetadata(metaData, catalog, schema, tableName, databaseMetadata);
        }
    }

    static class SchemaColumnFallbackCountingExecutor extends SchemaCountingExecutor {

        private boolean schemaColumnReadFailed;

        @Override
        protected void readColumnMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) throws SQLException {
            if (tableName == null) {
                schemaColumnReadFailed = true;
                throw new SQLException("schema column read failed");
            }
            super.readColumnMetadata(metaData, catalog, schema, tableName, databaseMetadata);
        }
    }

    private static ResultSet metadataResultSetWithoutColumns() {
        return proxy(ResultSet.class, (proxy, method, args) -> {
            if ("getString".equals(method.getName())) {
                throw new SQLException("column missing");
            }
            return defaultValue(method.getReturnType());
        });
    }

    static class TestDataSource implements DataSource {

        private final String url;

        TestDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unwrap not supported");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    @Table("auto_facade_user")
    static class FacadeUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table("generic_sql_sequence_user")
    static class GenericSqlSequenceUser {

        @TableId(value = IdAutoType.SQL, sql = "select next value for generic_id_seq")
        private Long id;
    }

    @Table("auto_batch_snapshot_user")
    static class BatchSnapshotUser {

        @TableId
        private Long id;
    }

    @Table("empty_columns")
    static class EmptyColumns {

        @TableField(exists = false)
        private String ignored;
    }

    @Table(schema = "schema_one", value = "schema_user")
    static class SchemaUser {

        @TableId
        private Long id;
    }

    @Table("raw_definition_user")
    static class RawDefinitionUser {

        @TableId
        private Long id;

        @ColumnDefinition(definition = "CHAR(8)", nullable = false, defaultValue = "'000000'")
        private String code;
    }

    @Table("definition_argument_user")
    static class DefinitionArgumentUser {

        @TableId
        private Long id;

        @ColumnDefinition(definition = "VARCHAR", length = 24, defaultValue = "'A001'")
        private String code;

        @ColumnDefinition(definition = "DECIMAL", precision = 8, scale = 3)
        private BigDecimal amount;

        @ColumnDefinition(definition = "DECIMAL", precision = 8)
        private BigDecimal total;

        @ColumnDefinition(definition = "TEXT")
        private String description;

        @ColumnDefinition(javaType = Integer.class)
        private String integerCode;
    }

    @Table("unique_create_user")
    static class UniqueCreateUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 128, unique = true, comment = "邮箱")
        private String email;
    }

    @Table("table_field_default_user")
    static class TableFieldDefaultUser {

        @TableId
        private Long id;

        @TableField(defaultValue = "'table_field_user'")
        private String username;

        @TableField(defaultValue = "0")
        private BigDecimal amount;

        @TableField(defaultValue = "'table_field_nickname'")
        @ColumnDefinition(length = 32, defaultValue = "'column_definition_nickname'")
        private String nickname;

        @TableField(defaultValue = "'table_field_remark'")
        @ColumnDefinition(length = 40, nullable = false, comment = "备注")
        private String remark;

        @TableField(defaultValue = "{NOW}")
        private String dynamicValue;
    }

    @Table("dynamic_now_default_user")
    static class DynamicNowDefaultUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @TableField(defaultValue = "{NOW}")
        private LocalDateTime createdAt;

        @TableField(defaultValue = "{NOW}")
        private Instant eventAt;

        @TableField(defaultValue = "{NOW}")
        private LocalDate bizDate;

        @TableField(defaultValue = "{TODAY}")
        private LocalDate todayDate;
    }

    @Table("composite_primary_key_user")
    static class CompositePrimaryKeyUser {

        @TableId
        private Long tenantId;

        @TableId
        private Long userId;
    }

    @Table("sqlite_auto_user")
    static class SqliteAutoUser {

        @TableId
        private Long id;
    }

    @Table("sqlite_auto_definition_user")
    static class SqliteAutoDefinitionUser {

        @TableId
        @ColumnDefinition(definition = "INTEGER")
        private Long id;
    }

    @Table("sqlite_invalid_auto_definition_user")
    static class SqliteInvalidAutoDefinitionUser {

        @TableId
        @ColumnDefinition(definition = "BIGINT")
        private Long id;
    }

    @Table("oracle_type_matrix")
    static class OracleTypeMatrix {

        @TableId
        private Long id;

        private Short shortValue;

        private Byte byteValue;

        private Boolean enabled;

        @ColumnDefinition(precision = 9)
        private BigDecimal amount;

        private Float ratio;

        @ColumnDefinition(length = 5000)
        private String largeText;
    }

    @Table("dm_integer_auto_user")
    static class DmIntegerAutoUser {

        @TableId
        private Integer id;
    }

    @Table("dm_long_auto_user")
    static class DmLongAutoUser {

        @TableId
        private Long id;
    }

    @Table("sqlserver_type_matrix")
    static class SqlServerTypeMatrix {

        @TableId
        private Long id;

        @ColumnDefinition(length = 5000)
        private String largeText;

        private byte[] payload;

        private Boolean enabled;
    }

    @Table("postgresql_blob_user")
    static class PostgresqlBlobUser {

        @TableId
        private Long id;

        private byte[] payload;
    }

    @Table("postgresql_integer_auto_user")
    static class PostgresqlIntegerAutoUser {

        @TableId
        private Integer id;
    }

    @Table("gauss_integer_auto_user")
    static class GaussIntegerAutoUser {

        @TableId
        private Integer id;
    }

    @Table("gauss_long_auto_user")
    static class GaussLongAutoUser {

        @TableId
        private Long id;
    }

    @Table("mysql_type_matrix")
    static class MysqlTypeMatrix {

        @TableId
        private Long id;

        private Double amount;

        private java.time.LocalDateTime createdAt;

        private Boolean enabled;
    }

    @Table("unknown_type_user")
    static class UnknownTypeUser {

        @TableId
        private Long id;

        private Object rawValue;

        private Byte byteValue;

        @ColumnDefinition(length = -1)
        private String name;

        private UUID requestId;
    }

    @Table(schema = "schema_meta", value = "metadata_user")
    static class MetadataSchemaUser {

        @TableId
        private Long id;
    }

    @Table(schema = "alpha", value = "same_name_user")
    static class MissingSchemaUser {

        @TableId
        private Long id;
    }

    @Table(schema = "\"CaseSchema\"", value = "\"CaseUser\"")
    static class QuotedCaseUser {

        @TableId
        @TableField(value = "\"Id\"")
        private Long id;
    }

    @Table(schema = "app_schema", value = "sqlserver_schema_comment_user")
    static class SqlServerSchemaCommentUser {

        @TableId
        private Long id;

        @ColumnDefinition(comment = "用户名")
        private String username;
    }

    @Table("auto_long_unique_name_user_with_an_extra_long_name")
    static class LongUniqueNameUser {

        @TableId
        private Long id;

        @TableField("very_long_email_address_column_name_for_unique_index")
        @ColumnDefinition(unique = true)
        private String email;
    }

    @Table(schema = "schema_group", value = "schema_group_one")
    static class SchemaGroupOne {

        @TableId
        private Long id;
    }

    @Table(schema = "schema_group", value = "schema_group_two")
    static class SchemaGroupTwo {

        @TableId
        private Long id;
    }
}
