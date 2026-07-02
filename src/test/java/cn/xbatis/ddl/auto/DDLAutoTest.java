package cn.xbatis.ddl.auto;

import cn.xbatis.core.mybatis.typeHandler.EnumSupport;
import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.annotations.ColumnDefinition;
import cn.xbatis.db.annotations.Index;
import cn.xbatis.db.annotations.IndexField;
import cn.xbatis.db.annotations.Table;
import cn.xbatis.db.annotations.TableDefinition;
import cn.xbatis.db.annotations.TableId;
import db.sql.api.DbType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DDLAutoTest {

    @Test
    void createShouldCreateTableAndMappedColumns() throws Exception {
        try (Connection connection = openConnection("create")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .add(CreateUser.class)
                    .execute(connection);

            assertTrue(tableExists(connection, "AUTO_CREATE_USER"));
            assertTrue(columnExists(connection, "AUTO_CREATE_USER", "ID"));
            assertTrue(columnExists(connection, "AUTO_CREATE_USER", "USERNAME"));
            assertTrue(columnExists(connection, "AUTO_CREATE_USER", "BALANCE"));
            assertTrue(columnExists(connection, "AUTO_CREATE_USER", "CREATED_AT"));
        }
    }

    @Test
    void createShouldCreateCompleteTableDefinition() throws Exception {
        try (Connection connection = openConnection("complete_create")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .add(CompleteCreateTable.class)
                    .execute(connection);

            assertTrue(tableExists(connection, "AUTO_COMPLETE_CREATE"));
            assertEquals(11, columnCount(connection, "AUTO_COMPLETE_CREATE"));
            assertTrue(primaryKeyExists(connection, "AUTO_COMPLETE_CREATE", "ID"));

            ColumnMeta username = columnMeta(connection, "AUTO_COMPLETE_CREATE", "USERNAME");
            assertEquals(Types.VARCHAR, username.dataType);
            assertEquals(80, username.size);
            assertEquals(DatabaseMetaData.columnNoNulls, username.nullable);

            ColumnMeta amount = columnMeta(connection, "AUTO_COMPLETE_CREATE", "AMOUNT");
            assertEquals(Types.DECIMAL, amount.dataType);
            assertEquals(12, amount.size);
            assertEquals(4, amount.decimalDigits);

            assertEquals(Types.INTEGER, columnMeta(connection, "AUTO_COMPLETE_CREATE", "LOGIN_COUNT").dataType);
            assertEquals(Types.BOOLEAN, columnMeta(connection, "AUTO_COMPLETE_CREATE", "ENABLED").dataType);
            assertEquals(Types.DATE, columnMeta(connection, "AUTO_COMPLETE_CREATE", "BIZ_DATE").dataType);
            assertEquals(Types.TIME, columnMeta(connection, "AUTO_COMPLETE_CREATE", "BIZ_TIME").dataType);
            assertEquals(Types.TIMESTAMP, columnMeta(connection, "AUTO_COMPLETE_CREATE", "CREATED_AT").dataType);
            assertEquals(Types.BLOB, columnMeta(connection, "AUTO_COMPLETE_CREATE", "PAYLOAD").dataType);
            assertEquals(36, columnMeta(connection, "AUTO_COMPLETE_CREATE", "REQUEST_ID").size);
            assertEquals(64, columnMeta(connection, "AUTO_COMPLETE_CREATE", "STATUS").size);
        }
    }

    @Test
    void updateShouldAddOnlyMissingColumns() throws Exception {
        try (Connection connection = openConnection("update")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .add(UpdateUserV1.class)
                    .execute(connection);

            assertTrue(columnExists(connection, "AUTO_UPDATE_USER", "ID"));
            assertTrue(columnExists(connection, "AUTO_UPDATE_USER", "USERNAME"));
            assertFalse(columnExists(connection, "AUTO_UPDATE_USER", "AGE"));

            DDLTestPrinter.ddl(DbType.H2)
                    .mode(Mode.UPDATE)
                    .add(UpdateUserV2.class)
                    .execute(connection);

            assertTrue(columnExists(connection, "AUTO_UPDATE_USER", "AGE"));

            DDLTestPrinter.ddl(DbType.H2)
                    .mode(Mode.UPDATE)
                    .add(UpdateUserV2.class)
                    .execute(connection);

            assertEquals(1, columnCount(connection, "AUTO_UPDATE_USER", "AGE"));
        }
    }

    @Test
    void createModeShouldNotAddMissingColumnsWhenTableExists() throws Exception {
        try (Connection connection = openConnection("create_no_update")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .add(CreateNoUpdateUserV1.class)
                    .execute(connection);

            DDLTestPrinter.ddl(DbType.H2)
                    .add(CreateNoUpdateUserV2.class)
                    .execute(connection);

            assertTrue(columnExists(connection, "AUTO_CREATE_NO_UPDATE_USER", "ID"));
            assertTrue(columnExists(connection, "AUTO_CREATE_NO_UPDATE_USER", "USERNAME"));
            assertFalse(columnExists(connection, "AUTO_CREATE_NO_UPDATE_USER", "AGE"));
        }
    }

    @Test
    void updateModeShouldCreateTableWhenTableDoesNotExist() throws Exception {
        try (Connection connection = openConnection("update_create")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .mode(Mode.UPDATE)
                    .add(UpdateCreateUser.class)
                    .execute(connection);

            assertTrue(tableExists(connection, "AUTO_UPDATE_CREATE_USER"));
            assertTrue(columnExists(connection, "AUTO_UPDATE_CREATE_USER", "ID"));
            assertTrue(columnExists(connection, "AUTO_UPDATE_CREATE_USER", "USERNAME"));
            assertTrue(columnExists(connection, "AUTO_UPDATE_CREATE_USER", "ENABLED"));
        }
    }

    @Test
    void updateModeShouldAddMultipleMissingColumns() throws Exception {
        try (Connection connection = openConnection("update_multi")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .add(UpdateMultiUserV1.class)
                    .execute(connection);

            List<String> executedSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.H2, executedSqlList)
                    .mode(Mode.UPDATE)
                    .add(UpdateMultiUserV2.class)
                    .execute(connection);

            assertEquals(1, executedSqlList.size());
            assertEquals("ALTER TABLE auto_update_multi_user ADD (age INTEGER, email VARCHAR(128));",
                    executedSqlList.get(0));
            assertTrue(columnExists(connection, "AUTO_UPDATE_MULTI_USER", "AGE"));
            assertTrue(columnExists(connection, "AUTO_UPDATE_MULTI_USER", "EMAIL"));
            assertEquals(1, columnCount(connection, "AUTO_UPDATE_MULTI_USER", "AGE"));
            assertEquals(1, columnCount(connection, "AUTO_UPDATE_MULTI_USER", "EMAIL"));
        }
    }

    @Test
    void h2ShouldCreateBooleanDefaultValueColumns() throws Exception {
        try (Connection connection = openConnection("boolean_default")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertBooleanDefaultValueFlow(
                    DbType.H2,
                    connection,
                    "BOOLEAN",
                    "FALSE",
                    "TRUE"
            );
        }
    }

    @Test
    void h2ShouldCreateDateTimeDefaultValueColumns() throws Exception {
        try (Connection connection = openConnection("datetime_default")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateTimeDefaultValueFlow(
                    DbType.H2,
                    connection,
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                    "event_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP"
            );
        }
    }

    @Test
    void h2ShouldCreateDateDefaultValueColumns() throws Exception {
        try (Connection connection = openConnection("date_default")) {
            DDLAutoExternalDatabaseIntegrationSupport.assertDateDefaultValueFlow(
                    DbType.H2,
                    connection,
                    "biz_date DATE DEFAULT CURRENT_DATE",
                    "today_date DATE DEFAULT CURRENT_DATE"
            );
        }
    }

    @Test
    void addMultipleColumnsShouldUseDialectSpecificStatements() {
        DefaultDDLBuilder builder = new DefaultDDLBuilder();

        assertEquals("ALTER TABLE auto_update_multi_user ADD (age INTEGER, email VARCHAR(128));",
                builder.addColumnSqlList(DbType.H2, UpdateMultiUserV2.class,
                        columns(DbType.H2, UpdateMultiUserV2.class, "age", "email")).get(0));
        assertEquals("ALTER TABLE auto_update_multi_user ADD COLUMN age INTEGER, ADD COLUMN email VARCHAR(128);",
                builder.addColumnSqlList(DbType.MYSQL, UpdateMultiUserV2.class,
                        columns(DbType.MYSQL, UpdateMultiUserV2.class, "age", "email")).get(0));
        assertEquals("ALTER TABLE auto_update_multi_user ADD COLUMN age INTEGER, ADD COLUMN email VARCHAR(128);",
                builder.addColumnSqlList(DbType.PGSQL, UpdateMultiUserV2.class,
                        columns(DbType.PGSQL, UpdateMultiUserV2.class, "age", "email")).get(0));
        assertEquals("ALTER TABLE auto_update_multi_user ADD (age NUMBER(10), email VARCHAR2(128));",
                builder.addColumnSqlList(DbType.ORACLE, UpdateMultiUserV2.class,
                        columns(DbType.ORACLE, UpdateMultiUserV2.class, "age", "email")).get(0));
        assertEquals("ALTER TABLE auto_update_multi_user ADD age INTEGER, email NVARCHAR(128);",
                builder.addColumnSqlList(DbType.SQL_SERVER, UpdateMultiUserV2.class,
                        columns(DbType.SQL_SERVER, UpdateMultiUserV2.class, "age", "email")).get(0));
        assertEquals("ALTER TABLE auto_update_multi_user ADD COLUMN age INTEGER ADD COLUMN email VARCHAR(128);",
                builder.addColumnSqlList(DbType.DB2, UpdateMultiUserV2.class,
                        columns(DbType.DB2, UpdateMultiUserV2.class, "age", "email")).get(0));
        assertEquals(java.util.Arrays.asList(
                        "ALTER TABLE auto_update_multi_user ADD COLUMN age INTEGER;",
                        "ALTER TABLE auto_update_multi_user ADD COLUMN email VARCHAR(128);"
                ),
                builder.addColumnSqlList(DbType.SQLITE, UpdateMultiUserV2.class,
                        columns(DbType.SQLITE, UpdateMultiUserV2.class, "age", "email")));
    }

    @Test
    void createShouldHandleMultipleEntities() throws Exception {
        try (Connection connection = openConnection("batch_create")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .add(BatchUser.class, BatchOrder.class)
                    .execute(connection);

            assertTrue(tableExists(connection, "AUTO_BATCH_USER"));
            assertTrue(tableExists(connection, "AUTO_BATCH_ORDER"));
            assertTrue(columnExists(connection, "AUTO_BATCH_USER", "USERNAME"));
            assertTrue(columnExists(connection, "AUTO_BATCH_ORDER", "ORDER_NO"));
        }
    }

    @Test
    void resolverShouldCreateAndUpdateMultiplePhysicalTables() throws Exception {
        DDLTableNameResolverUtil.set(ResolverUserV1.class,
                tableName -> java.util.Arrays.asList(tableName + "_00", tableName + "_01"));
        DDLTableNameResolverUtil.set(ResolverUserV2.class,
                tableName -> java.util.Arrays.asList(tableName + "_00", tableName + "_01"));
        try (Connection connection = openConnection("resolver_multi")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .add(ResolverUserV1.class)
                    .execute(connection);

            assertTrue(tableExists(connection, "AUTO_RESOLVER_USER_00"));
            assertTrue(tableExists(connection, "AUTO_RESOLVER_USER_01"));
            assertTrue(columnExists(connection, "AUTO_RESOLVER_USER_00", "USERNAME"));
            assertTrue(columnExists(connection, "AUTO_RESOLVER_USER_01", "USERNAME"));
            assertTrue(indexExists(connection, "AUTO_RESOLVER_USER_00", "IDX_AUTO_RESOLVER_USER_00_USERNAME"));
            assertTrue(indexExists(connection, "AUTO_RESOLVER_USER_01", "IDX_AUTO_RESOLVER_USER_01_USERNAME"));

            List<String> updateSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.H2, updateSqlList)
                    .mode(Mode.UPDATE)
                    .add(ResolverUserV2.class)
                    .execute(connection);

            assertTrue(columnExists(connection, "AUTO_RESOLVER_USER_00", "EMAIL"));
            assertTrue(columnExists(connection, "AUTO_RESOLVER_USER_01", "EMAIL"));
            assertTrue(updateSqlList.contains("ALTER TABLE auto_resolver_user_00 ADD COLUMN email VARCHAR(255);"));
            assertTrue(updateSqlList.contains("ALTER TABLE auto_resolver_user_01 ADD COLUMN email VARCHAR(255);"));

            List<String> secondUpdateSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.H2, secondUpdateSqlList)
                    .mode(Mode.UPDATE)
                    .add(ResolverUserV2.class)
                    .execute(connection);

            assertTrue(secondUpdateSqlList.isEmpty());
        } finally {
            DDLTableNameResolverUtil.remove(ResolverUserV1.class);
            DDLTableNameResolverUtil.remove(ResolverUserV2.class);
        }
    }

    @Test
    void resolverShouldSkipEntityWhenResolvedTableNamesAreEmpty() throws Exception {
        DDLTableNameResolverUtil.set(EmptyResolverUser.class, tableName -> java.util.Collections.emptyList());
        try (Connection connection = openConnection("resolver_empty")) {
            assertTrue(DDLTestPrinter.ddl(DbType.H2)
                    .add(EmptyResolverUser.class)
                    .sqlList()
                    .isEmpty());

            List<String> executedSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.H2, executedSqlList)
                    .add(EmptyResolverUser.class)
                    .execute(connection);

            assertTrue(executedSqlList.isEmpty());
            assertFalse(tableExists(connection, "AUTO_EMPTY_RESOLVER_USER"));
        } finally {
            DDLTableNameResolverUtil.remove(EmptyResolverUser.class);
        }
    }

    @Test
    void tableFalseShouldSkipDdlGenerationAndExecution() throws Exception {
        DDLBuilder builder = new DefaultDDLBuilder();
        assertEquals("", builder.createTableSql(DbType.H2, TableFalseUser.class));
        assertTrue(builder.createTableSqlList(DbType.PGSQL, TableFalseUser.class).isEmpty());
        assertEquals("", builder.addColumnSql(DbType.H2, TableFalseUserV2.class, "email"));
        assertTrue(builder.addColumnSqlList(DbType.H2, TableFalseUserV2.class, "email").isEmpty());

        try (Connection connection = openConnection("table_false_skip");
             Statement statement = connection.createStatement()) {
            List<String> executedSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.H2, executedSqlList)
                    .add(TableFalseUser.class, BatchUser.class)
                    .execute(connection);

            assertFalse(tableExists(connection, "AUTO_TABLE_FALSE_USER"));
            assertTrue(tableExists(connection, "AUTO_BATCH_USER"));
            assertTrue(executedSqlList.stream().noneMatch(sql -> sql.contains("auto_table_false_user")));

            statement.execute("CREATE TABLE auto_table_false_user (id BIGINT PRIMARY KEY, username VARCHAR(64))");

            List<String> previewSqlList = DDLTestPrinter.ddl(DbType.H2)
                    .mode(Mode.UPDATE)
                    .add(TableFalseUserV2.class)
                    .sqlList(connection);
            assertTrue(previewSqlList.isEmpty());

            List<String> updateSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.H2, updateSqlList)
                    .mode(Mode.UPDATE)
                    .add(TableFalseUserV2.class)
                    .execute(connection);

            assertTrue(updateSqlList.isEmpty());
            assertFalse(columnExists(connection, "AUTO_TABLE_FALSE_USER", "EMAIL"));
        }
    }

    @Test
    void resolverShouldGenerateSequenceOnlyOnceForMultiplePhysicalTables() {
        DDLTableNameResolverUtil.set(SqlSequenceUser.class,
                tableName -> java.util.Arrays.asList(tableName + "_00", tableName + "_01"));
        try {
            List<String> sqlList = DDLTestPrinter.ddl(DbType.PGSQL)
                    .add(SqlSequenceUser.class)
                    .sqlList();

            assertEquals(3, sqlList.size());
            assertEquals(1, sqlList.stream()
                    .filter(sql -> sql.equals("CREATE SEQUENCE IF NOT EXISTS id_test_id_seq;"))
                    .count());
            assertTrue(sqlList.get(1).contains("CREATE TABLE IF NOT EXISTS sql_sequence_user_00"));
            assertTrue(sqlList.get(2).contains("CREATE TABLE IF NOT EXISTS sql_sequence_user_01"));
        } finally {
            DDLTableNameResolverUtil.remove(SqlSequenceUser.class);
        }
    }

    @Test
    void resolverShouldRejectExplicitIndexNameForMultiplePhysicalTables() {
        DDLTableNameResolverUtil.set(NamedIndexResolverUser.class,
                tableName -> java.util.Arrays.asList(tableName + "_00", tableName + "_01"));
        try {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> DDLTestPrinter.ddl(DbType.H2)
                            .add(NamedIndexResolverUser.class)
                            .sqlList());

            assertTrue(exception.getMessage().contains("explicit @Index name is not allowed"));
            assertTrue(exception.getMessage().contains("idx_named_resolver_username"));
        } finally {
            DDLTableNameResolverUtil.remove(NamedIndexResolverUser.class);
        }
    }

    @Test
    void sqlListShouldGenerateSqlWithoutConnectingDatabase() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.H2)
                .add(SqlOnlyUser.class)
                .sqlList();

        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE IF NOT EXISTS auto_sql_only_user"));
        assertTrue(sqlList.get(0).contains("username VARCHAR(32) NOT NULL"));
    }

    @Test
    void sqlListWithConnectionShouldPreviewUpdateSqlWithoutExecuting() throws Exception {
        try (Connection connection = openConnection("preview_update")) {
            DDLTestPrinter.ddl(DbType.H2)
                    .add(UpdateUserV1.class)
                    .execute(connection);

            List<String> sqlList = DDLTestPrinter.ddl(DbType.H2)
                    .mode(Mode.UPDATE)
                    .add(UpdateUserV2.class)
                    .sqlList(connection);

            assertEquals(1, sqlList.size());
            assertEquals("ALTER TABLE auto_update_user ADD COLUMN age INTEGER;", sqlList.get(0));
            assertFalse(columnExists(connection, "AUTO_UPDATE_USER", "AGE"));
        }
    }

    @Test
    void sqlListWithConnectionShouldPreviewCreateSqlWhenTableDoesNotExist() throws Exception {
        try (Connection connection = openConnection("preview_create")) {
            List<String> sqlList = DDLTestPrinter.ddl(DbType.H2)
                    .mode(Mode.UPDATE)
                    .add(UpdateCreateUser.class)
                    .sqlList(connection);

            assertEquals(1, sqlList.size());
            assertTrue(sqlList.get(0).contains("CREATE TABLE IF NOT EXISTS auto_update_create_user"));
            assertFalse(tableExists(connection, "AUTO_UPDATE_CREATE_USER"));
        }
    }

    @Test
    void existingViewShouldBeSkipped() throws Exception {
        try (Connection connection = openConnection("view_skip");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auto_view_source (id BIGINT, username VARCHAR(64))");
            statement.execute("CREATE VIEW auto_view_user AS SELECT id, username FROM auto_view_source");

            assertTrue(viewExists(connection, "AUTO_VIEW_USER"));
            assertTrue(DDLTestPrinter.ddl(DbType.H2)
                    .add(ViewUser.class)
                    .sqlList(connection)
                    .isEmpty());
            assertTrue(DDLTestPrinter.ddl(DbType.H2)
                    .mode(Mode.UPDATE)
                    .add(ViewUser.class)
                    .sqlList(connection)
                    .isEmpty());

            DDLTestPrinter.ddl(DbType.H2)
                    .mode(Mode.UPDATE)
                    .add(ViewUser.class)
                    .execute(connection);

            assertFalse(columnExists(connection, "AUTO_VIEW_USER", "AGE"));
        }
    }

    @Test
    void postgresqlCreateShouldGenerateColumnCommentStatements() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.PGSQL)
                .add(PostgresqlCommentUser.class)
                .sqlList();

        assertEquals(3, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE IF NOT EXISTS pg_comment_user"));
        assertTrue(sqlList.contains("COMMENT ON COLUMN pg_comment_user.username IS '用户名';"));
        assertTrue(sqlList.contains("COMMENT ON COLUMN pg_comment_user.description IS '用户''描述';"));
    }

    @Test
    void postgresqlAddColumnShouldGenerateColumnCommentStatement() {
        DDLBuilder builder = new DefaultDDLBuilder();
        List<String> sqlList = builder.addColumnSqlList(DbType.PGSQL, PostgresqlCommentUser.class, "description");

        assertEquals(2, sqlList.size());
        assertEquals("ALTER TABLE pg_comment_user ADD COLUMN description VARCHAR(255);", sqlList.get(0));
        assertEquals("COMMENT ON COLUMN pg_comment_user.description IS '用户''描述';", sqlList.get(1));
    }

    @Test
    void sqlServerCreateShouldGenerateExtendedPropertyColumnComments() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.SQL_SERVER)
                .add(SqlServerCommentUser.class)
                .sqlList();

        assertEquals(3, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE sqlserver_comment_user"));
        assertTrue(sqlList.get(1).contains("EXEC sys.sp_addextendedproperty"));
        assertTrue(sqlList.get(1).contains("@value=N'用户名'"));
        assertTrue(sqlList.get(1).contains("@level1name=N'sqlserver_comment_user'"));
        assertTrue(sqlList.get(1).contains("@level2name=N'username'"));
        assertTrue(sqlList.get(2).contains("@value=N'用户''描述'"));
        assertTrue(sqlList.get(2).contains("@level2name=N'description'"));
    }

    @Test
    void sqlServerAddColumnShouldGenerateExtendedPropertyColumnComment() {
        DDLBuilder builder = new DefaultDDLBuilder();
        List<String> sqlList = builder.addColumnSqlList(DbType.SQL_SERVER, SqlServerCommentUser.class, "description");

        assertEquals(2, sqlList.size());
        assertEquals("ALTER TABLE sqlserver_comment_user ADD description NVARCHAR(255);", sqlList.get(0));
        assertTrue(sqlList.get(1).contains("EXEC sys.sp_addextendedproperty"));
        assertTrue(sqlList.get(1).contains("@value=N'用户''描述'"));
        assertTrue(sqlList.get(1).contains("@level2name=N'description'"));
    }

    @Test
    void mysqlCreateShouldGenerateInlineColumnComment() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.MYSQL)
                .add(MysqlCommentUser.class)
                .sqlList();

        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).contains("username VARCHAR(64) COMMENT '用户名'"));
        assertTrue(sqlList.get(0).contains("description VARCHAR(255) COMMENT '用户''描述'"));
    }

    @Test
    void oracleCreateShouldGenerateColumnCommentStatements() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.ORACLE)
                .add(OracleCommentUser.class)
                .sqlList();

        assertEquals(3, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE oracle_comment_user"));
        assertTrue(sqlList.contains("COMMENT ON COLUMN oracle_comment_user.username IS '用户名';"));
        assertTrue(sqlList.contains("COMMENT ON COLUMN oracle_comment_user.description IS '用户''描述';"));
    }

    @Test
    void mysqlCreateShouldUseTableDefinitionAndInlineTableComment() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.MYSQL)
                .add(MysqlTableDefinitionUser.class)
                .sqlList();

        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE IF NOT EXISTS mysql_table_definition_user"));
        assertTrue(sqlList.get(0).endsWith(") ENGINE=InnoDB COMMENT='用户''表';"));
    }

    @Test
    void postgresqlCreateShouldGenerateTableCommentStatement() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.PGSQL)
                .add(PostgresqlTableCommentUser.class)
                .sqlList();

        assertEquals(2, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE IF NOT EXISTS pg_table_comment_user"));
        assertEquals("COMMENT ON TABLE pg_table_comment_user IS '用户''表';", sqlList.get(1));
    }

    @Test
    void oracleCreateShouldGenerateTableCommentStatement() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.ORACLE)
                .add(OracleTableCommentUser.class)
                .sqlList();

        assertEquals(2, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE oracle_table_comment_user"));
        assertEquals("COMMENT ON TABLE oracle_table_comment_user IS '用户''表';", sqlList.get(1));
    }

    @Test
    void sqlServerCreateShouldGenerateExtendedPropertyTableComment() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.SQL_SERVER)
                .add(SqlServerTableCommentUser.class)
                .sqlList();

        assertEquals(2, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE sqlserver_table_comment_user"));
        assertTrue(sqlList.get(1).contains("EXEC sys.sp_addextendedproperty"));
        assertTrue(sqlList.get(1).contains("@value=N'用户''表'"));
        assertTrue(sqlList.get(1).contains("@level1name=N'sqlserver_table_comment_user'"));
        assertFalse(sqlList.get(1).contains("@level2name"));
    }

    @Test
    void h2CreateShouldExecuteTableCommentStatement() throws Exception {
        try (Connection connection = openConnection("table_comment")) {
            List<String> executedSqlList = new java.util.ArrayList<>();
            DDLTestPrinter.ddl(DbType.H2, executedSqlList)
                    .add(H2TableCommentUser.class)
                    .execute(connection);

            assertTrue(tableExists(connection, "H2_TABLE_COMMENT_USER"));
            assertTrue(executedSqlList.contains("COMMENT ON TABLE h2_table_comment_user IS 'H2表';"));
        }
    }

    @Test
    void db2CreateShouldGenerateTableCommentStatement() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.DB2)
                .add(Db2TableCommentUser.class)
                .sqlList();

        assertEquals(2, sqlList.size());
        assertTrue(sqlList.get(0).contains("CREATE TABLE db2_table_comment_user"));
        assertEquals("COMMENT ON TABLE db2_table_comment_user IS 'DB2表';", sqlList.get(1));
    }

    @Test
    void compatibleDatabaseCreateShouldApplyTableDefinition() {
        List<String> dmSqlList = DDLTestPrinter.ddl(DbType.DM)
                .add(OracleTableCommentUser.class)
                .sqlList();
        assertEquals("COMMENT ON TABLE oracle_table_comment_user IS '用户''表';", dmSqlList.get(1));

        List<String> gaussSqlList = DDLTestPrinter.ddl(DbType.GAUSS)
                .add(PostgresqlTableCommentUser.class)
                .sqlList();
        assertEquals("COMMENT ON TABLE pg_table_comment_user IS '用户''表';", gaussSqlList.get(1));

        List<String> kingbaseSqlList = DDLTestPrinter.ddl(DbType.KING_BASE)
                .add(PostgresqlTableCommentUser.class)
                .sqlList();
        assertEquals("COMMENT ON TABLE pg_table_comment_user IS '用户''表';", kingbaseSqlList.get(1));

        List<String> mariadbSqlList = DDLTestPrinter.ddl(DbType.MARIA_DB)
                .add(MysqlTableDefinitionUser.class)
                .sqlList();
        assertTrue(mariadbSqlList.get(0).endsWith(") ENGINE=InnoDB COMMENT='用户''表';"));

        List<String> oceanbaseSqlList = DDLTestPrinter.ddl(DbType.OCEAN_BASE)
                .add(MysqlTableDefinitionUser.class)
                .sqlList();
        assertTrue(oceanbaseSqlList.get(0).endsWith(") ENGINE=InnoDB COMMENT='用户''表';"));
    }

    @Test
    void sqlServerShouldUseDateTime2ForJavaDateTime() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.SQL_SERVER)
                .add(SqlServerTypeUser.class)
                .sqlList();

        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).contains("created_at DATETIME2"));
        assertFalse(sqlList.get(0).contains("created_at TIMESTAMP"));
    }

    @Test
    void oracleShouldNotGenerateTimeTypeForLocalTime() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.ORACLE)
                .add(OracleTypeUser.class)
                .sqlList();

        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).contains("biz_time TIMESTAMP"));
        assertFalse(sqlList.get(0).contains("biz_time TIME,"));
        assertFalse(sqlList.get(0).contains("biz_time TIME\n"));
    }

    @Test
    void postgresqlShouldUseSmallIntForJavaByte() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.PGSQL)
                .add(PostgresqlTypeUser.class)
                .sqlList();

        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).contains("level SMALLINT"));
        assertFalse(sqlList.get(0).contains("level TINYINT"));
    }

    @Test
    void tableIdSqlShouldGeneratePostgresqlAndOracleSequenceDdl() {
        List<String> postgresqlSqlList = DDLTestPrinter.ddl(DbType.PGSQL)
                .add(SqlSequenceUser.class)
                .sqlList();

        assertEquals(2, postgresqlSqlList.size());
        assertEquals("CREATE SEQUENCE IF NOT EXISTS id_test_id_seq;", postgresqlSqlList.get(0));
        assertTrue(postgresqlSqlList.get(1).contains("CREATE TABLE IF NOT EXISTS sql_sequence_user"));
        assertTrue(postgresqlSqlList.get(1).contains("id BIGINT NOT NULL PRIMARY KEY"));
        assertFalse(postgresqlSqlList.get(1).contains("GENERATED"));

        List<String> oracleSqlList = DDLTestPrinter.ddl(DbType.ORACLE)
                .add(SqlSequenceUser.class)
                .sqlList();

        assertEquals(2, oracleSqlList.size());
        assertEquals("CREATE SEQUENCE id_test_seq;", oracleSqlList.get(0));
        assertTrue(oracleSqlList.get(1).contains("CREATE TABLE sql_sequence_user"));
        assertTrue(oracleSqlList.get(1).contains("id NUMBER(19) NOT NULL PRIMARY KEY"));
        assertFalse(oracleSqlList.get(1).contains("GENERATED"));

        List<String> sqlServerSqlList = DDLTestPrinter.ddl(DbType.SQL_SERVER)
                .add(SqlSequenceUser.class)
                .sqlList();

        assertEquals(2, sqlServerSqlList.size());
        assertEquals("CREATE SEQUENCE id_test_sqlserver_seq;", sqlServerSqlList.get(0));
        assertTrue(sqlServerSqlList.get(1).contains("CREATE TABLE sql_sequence_user"));
        assertTrue(sqlServerSqlList.get(1).contains("id BIGINT NOT NULL PRIMARY KEY"));
        assertFalse(sqlServerSqlList.get(1).contains("IDENTITY"));

        List<String> db2SqlList = DDLTestPrinter.ddl(DbType.DB2)
                .add(SqlSequenceUser.class)
                .sqlList();

        assertEquals(2, db2SqlList.size());
        assertEquals("CREATE SEQUENCE id_test_db2_seq;", db2SqlList.get(0));
        assertTrue(db2SqlList.get(1).contains("CREATE TABLE sql_sequence_user"));
        assertTrue(db2SqlList.get(1).contains("id BIGINT NOT NULL PRIMARY KEY"));
        assertFalse(db2SqlList.get(1).contains("GENERATED"));
    }

    @Test
    void enumSupportShouldUseCodeTypeForColumnType() {
        List<String> sqlList = DDLTestPrinter.ddl(DbType.H2)
                .add(EnumSupportUser.class)
                .sqlList();

        assertEquals(1, sqlList.size());
        assertTrue(sqlList.get(0).contains("status INTEGER"));
        assertTrue(sqlList.get(0).contains("fallback_status VARCHAR(64)"));
        assertTrue(sqlList.get(0).contains("other_status VARCHAR(64)"));
        assertTrue(sqlList.get(0).contains("marker_status VARCHAR(64)"));
        assertTrue(sqlList.get(0).contains("interface_code_status INTEGER"));
    }

    @Test
    void sqliteAddColumnShouldGenerateSeparateUniqueIndex() {
        DDLBuilder builder = new DefaultDDLBuilder();
        List<String> sqlList = builder.addColumnSqlList(DbType.SQLITE, SqliteUniqueUser.class, "email");

        assertEquals(2, sqlList.size());
        assertEquals("ALTER TABLE sqlite_unique_user ADD COLUMN email VARCHAR(128);", sqlList.get(0));
        assertEquals("CREATE UNIQUE INDEX uk_sqlite_unique_user_email ON sqlite_unique_user (email);", sqlList.get(1));
    }

    @Test
    void clickHouseShouldRejectUniqueConstraint() {
        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class, () -> DDLTestPrinter.ddl(DbType.CLICK_HOUSE)
                .add(ClickHouseUniqueUser.class)
                .sqlList());

        assertTrue(exception.getMessage().contains("CLICK_HOUSE"));
        assertTrue(exception.getMessage().contains("UNIQUE"));
    }

    private List<ColumnInfo> columns(DbType dbType, Class<?> entityClass, String... columnNames) {
        List<ColumnInfo> allColumns = new DefaultDDLBuilder().getColumns(dbType, entityClass);
        List<ColumnInfo> result = new java.util.ArrayList<>(columnNames.length);
        for (String columnName : columnNames) {
            ColumnInfo matchedColumn = null;
            for (ColumnInfo column : allColumns) {
                if (columnName.equals(column.getName())) {
                    matchedColumn = column;
                    break;
                }
            }
            if (matchedColumn == null) {
                throw new AssertionError("Column not found: " + columnName);
            }
            result.add(matchedColumn);
        }
        return result;
    }

    private Connection openConnection(String databaseName) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:auto_table_" + databaseName + ";DB_CLOSE_DELAY=-1");
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private boolean viewExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(null, null, tableName, new String[]{"VIEW"})) {
            return resultSet.next();
        }
    }

    private int columnCount(Connection connection, String tableName, String columnName) throws SQLException {
        int count = 0;
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(null, null, tableName, columnName)) {
            while (resultSet.next()) {
                count++;
            }
        }
        return count;
    }

    private int columnCount(Connection connection, String tableName) throws SQLException {
        int count = 0;
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(null, null, tableName, null)) {
            while (resultSet.next()) {
                count++;
            }
        }
        return count;
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(null, null, tableName, columnName)) {
            return resultSet.next();
        }
    }

    private boolean primaryKeyExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getPrimaryKeys(null, null, tableName)) {
            while (resultSet.next()) {
                if (columnName.equals(resultSet.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getIndexInfo(null, null, tableName, false, false)) {
            while (resultSet.next()) {
                if (indexName.equals(resultSet.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private ColumnMeta columnMeta(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(null, null, tableName, columnName)) {
            assertTrue(resultSet.next(), "column not found: " + tableName + "." + columnName);
            return new ColumnMeta(
                    resultSet.getInt("DATA_TYPE"),
                    resultSet.getInt("COLUMN_SIZE"),
                    resultSet.getInt("DECIMAL_DIGITS"),
                    resultSet.getInt("NULLABLE")
            );
        }
    }

    static class ColumnMeta {

        private final int dataType;

        private final int size;

        private final int decimalDigits;

        private final int nullable;

        ColumnMeta(int dataType, int size, int decimalDigits, int nullable) {
            this.dataType = dataType;
            this.size = size;
            this.decimalDigits = decimalDigits;
            this.nullable = nullable;
        }
    }

    @Table("auto_create_user")
    static class CreateUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;

        @ColumnDefinition(precision = 10, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_complete_create")
    static class CompleteCreateTable {

        @TableId
        private Long id;

        @ColumnDefinition(length = 80, nullable = false)
        private String username;

        @ColumnDefinition(precision = 12, scale = 4, defaultValue = "0")
        private BigDecimal amount;

        private Integer loginCount;

        private Boolean enabled;

        private LocalDate bizDate;

        private LocalTime bizTime;

        private LocalDateTime createdAt;

        private byte[] payload;

        private UUID requestId;

        private CompleteStatus status;
    }

    enum CompleteStatus {
        ENABLED,
        DISABLED
    }

    @Table("auto_update_user")
    static class UpdateUserV1 {

        @TableId
        private Long id;

        private String username;
    }

    @Table("auto_update_user")
    static class UpdateUserV2 {

        @TableId
        private Long id;

        private String username;

        private Integer age;
    }

    @Table("auto_create_no_update_user")
    static class CreateNoUpdateUserV1 {

        @TableId
        private Long id;

        private String username;
    }

    @Table("auto_create_no_update_user")
    static class CreateNoUpdateUserV2 {

        @TableId
        private Long id;

        private String username;

        private Integer age;
    }

    @Table("auto_update_create_user")
    static class UpdateCreateUser {

        @TableId
        private Long id;

        private String username;

        private Boolean enabled;
    }

    @Table("auto_view_user")
    static class ViewUser {

        @TableId
        private Long id;

        private String username;

        private Integer age;
    }

    @Table("auto_update_multi_user")
    static class UpdateMultiUserV1 {

        @TableId
        private Long id;

        private String username;
    }

    @Table("auto_update_multi_user")
    static class UpdateMultiUserV2 {

        @TableId
        private Long id;

        private String username;

        private Integer age;

        @ColumnDefinition(length = 128)
        private String email;
    }

    @Table("auto_h2_boolean_default_user")
    static class H2BooleanDefaultUser {

        @TableId
        private Long id;

        @ColumnDefinition(defaultValue = "FALSE")
        private Boolean defaultFalse;

        @ColumnDefinition(defaultValue = "TRUE")
        private Boolean defaultTrue;
    }

    @Table("auto_batch_user")
    static class BatchUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table("auto_batch_order")
    static class BatchOrder {

        @TableId
        private Long id;

        private String orderNo;
    }

    @Table("auto_resolver_user")
    @Index(fields = @IndexField(name = "username"))
    static class ResolverUserV1 {

        @TableId
        private Long id;

        private String username;
    }

    @Table("auto_resolver_user")
    @Index(fields = @IndexField(name = "username"))
    static class ResolverUserV2 {

        @TableId
        private Long id;

        private String username;

        private String email;
    }

    @Table("auto_empty_resolver_user")
    static class EmptyResolverUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table(value = "auto_table_false_user", table = false)
    @Index(fields = @IndexField(name = "username"))
    static class TableFalseUser {

        @TableId
        @TableId(dbType = DbType.Name.PGSQL, value = IdAutoType.SQL, sql = "select nextval('table_false_seq')")
        private Long id;

        private String username;
    }

    @Table(value = "auto_table_false_user", table = false)
    @Index(fields = @IndexField(name = "email"))
    static class TableFalseUserV2 {

        @TableId
        private Long id;

        private String username;

        private String email;
    }

    @Table("auto_named_resolver_user")
    @Index(name = "idx_named_resolver_username", fields = @IndexField(name = "username"))
    static class NamedIndexResolverUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table("auto_sql_only_user")
    static class SqlOnlyUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 32, nullable = false)
        private String username;
    }

    @Table("pg_comment_user")
    static class PostgresqlCommentUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, comment = "用户名")
        private String username;

        @ColumnDefinition(comment = "用户'描述")
        private String description;
    }

    @Table("sqlserver_comment_user")
    static class SqlServerCommentUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, comment = "用户名")
        private String username;

        @ColumnDefinition(comment = "用户'描述")
        private String description;
    }

    @Table("mysql_comment_user")
    static class MysqlCommentUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, comment = "用户名")
        private String username;

        @ColumnDefinition(comment = "用户'描述")
        private String description;
    }

    @Table("oracle_comment_user")
    static class OracleCommentUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64, comment = "用户名")
        private String username;

        @ColumnDefinition(comment = "用户'描述")
        private String description;
    }

    @Table("mysql_table_definition_user")
    @TableDefinition(definition = "ENGINE=InnoDB;", comment = "用户'表")
    static class MysqlTableDefinitionUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 64)
        private String username;
    }

    @Table("pg_table_comment_user")
    @TableDefinition(comment = "用户'表")
    static class PostgresqlTableCommentUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table("oracle_table_comment_user")
    @TableDefinition(comment = "用户'表")
    static class OracleTableCommentUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table("sqlserver_table_comment_user")
    @TableDefinition(comment = "用户'表")
    static class SqlServerTableCommentUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table("h2_table_comment_user")
    @TableDefinition(comment = "H2表")
    static class H2TableCommentUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table("db2_table_comment_user")
    @TableDefinition(comment = "DB2表")
    static class Db2TableCommentUser {

        @TableId
        private Long id;

        private String username;
    }

    @Table("sqlserver_type_user")
    static class SqlServerTypeUser {

        @TableId
        private Long id;

        private LocalDateTime createdAt;
    }

    @Table("oracle_type_user")
    static class OracleTypeUser {

        @TableId
        private Long id;

        private LocalTime bizTime;
    }

    @Table("postgresql_type_user")
    static class PostgresqlTypeUser {

        @TableId
        private Long id;

        private Byte level;
    }

    @Table("sql_sequence_user")
    static class SqlSequenceUser {

        @TableId(dbType = DbType.Name.PGSQL, value = IdAutoType.SQL, sql = "select nextval('id_test_id_seq')")
        @TableId(dbType = DbType.Name.ORACLE, value = IdAutoType.SQL, sql = "select id_test_seq.NEXTVAL FROM dual")
        @TableId(dbType = DbType.Name.SQL_SERVER, value = IdAutoType.SQL, sql = "select next value for id_test_sqlserver_seq")
        @TableId(dbType = DbType.Name.DB2, value = IdAutoType.SQL, sql = "select next value for id_test_db2_seq from sysibm.sysdummy1")
        private Long id;

        private String username;
    }

    @Table("enum_support_user")
    static class EnumSupportUser {

        @TableId
        private Long id;

        private CodeStatus status;

        private FallbackStatus fallbackStatus;

        private OtherStatus otherStatus;

        private MarkerStatus markerStatus;

        private InterfaceCodeStatus interfaceCodeStatus;
    }

    enum CodeStatus implements EnumSupport<Integer> {
        ENABLED(1),
        DISABLED(0);

        private final Integer code;

        CodeStatus(Integer code) {
            this.code = code;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        public static CodeStatus of(Integer code) {
            for (CodeStatus item : values()) {
                if (Objects.equals(item.getCode(), code)) {
                    return item;
                }
            }
            return null;
        }
    }

    enum FallbackStatus {
        ENABLED,
        DISABLED
    }

    enum OtherStatus implements OtherGeneric<String> {
        ENABLED,
        DISABLED
    }

    interface OtherGeneric<T> {
    }

    enum MarkerStatus implements MarkerStatusInterface {
        ENABLED,
        DISABLED
    }

    interface MarkerStatusInterface {
    }

    enum InterfaceCodeStatus implements IntegerCodeEnum {
        ENABLED(1),
        DISABLED(0);

        private final Integer code;

        InterfaceCodeStatus(Integer code) {
            this.code = code;
        }

        @Override
        public Integer getCode() {
            return code;
        }
    }

    interface IntegerCodeEnum extends EnumSupport<Integer> {
    }

    @Table("sqlite_unique_user")
    static class SqliteUniqueUser {

        @TableId
        private Long id;

        @ColumnDefinition(length = 128, unique = true)
        private String email;
    }

    @Table("clickhouse_unique_user")
    static class ClickHouseUniqueUser {

        @TableId
        private Long id;

        @ColumnDefinition(unique = true)
        private String email;
    }
}
