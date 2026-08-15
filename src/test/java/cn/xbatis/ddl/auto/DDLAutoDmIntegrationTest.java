package cn.xbatis.ddl.auto;

import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.IndexDirection;
import cn.xbatis.db.annotations.*;
import db.sql.api.DbType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Tag("integration")
class DDLAutoDmIntegrationTest extends DDLAutoExternalDatabaseIntegrationSupport {

    private static final DatabaseCase DATABASE = new DatabaseCase(
            DbType.DM,
            "DM",
            "dm.jdbc.driver.DmDriver",
            System.getProperty("dm.test.url", "jdbc:dm://127.0.0.1:5236"),
            System.getProperty("dm.test.username", "SYSDBA"),
            System.getProperty("dm.test.password", "SYSDBA001")
    );

    @Test
    void dmShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        assertCreateUpdateFlow(
                DATABASE,
                DmIntegrationUserV1.class,
                DmIntegrationUserV2.class,
                "auto_dm_itg_user",
                "idx_dm_itg_user_name",
                "idx_dm_itg_email",
                "idx_dm_itg_name_ct",
                "ALTER TABLE auto_dm_itg_user ADD email VARCHAR2(128);"
        );
    }

    @Test
    void dmShouldAddMultipleMissingColumnsInSingleAlter() throws Exception {
        assertMultiColumnAddColumnFlow(
                DATABASE,
                "ALTER TABLE auto_multi_column_add_user ADD (age INTEGER, email VARCHAR2(128));"
        );
    }

    @Test
    void dmShouldCreateBooleanDefaultValueColumns() throws Exception {
        assertBooleanDefaultValueFlow(
                DATABASE,
                "NUMBER(1)",
                "0",
                "1"
        );
    }

    @Test
    void dmShouldCreateDateTimeDefaultValueColumns() throws Exception {
        assertDateTimeDefaultValueFlow(
                DATABASE,
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "event_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP"
        );
    }

    @Test
    void dmShouldCreateDateDefaultValueColumns() throws Exception {
        assertDateDefaultValueFlow(
                DATABASE,
                "biz_date DATE DEFAULT TRUNC(SYSDATE)",
                "today_date DATE DEFAULT TRUNC(SYSDATE)"
        );
    }

    @Test
    void dmShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        assertIntLongAutoAndManualIdFlow(
                DATABASE,
                "id INTEGER IDENTITY(1,1) NOT NULL PRIMARY KEY",
                "id BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY",
                "id INTEGER NOT NULL PRIMARY KEY",
                "id BIGINT NOT NULL PRIMARY KEY"
        );
    }

    @Test
    void dmShouldCreateTableDefinitionComment() throws Exception {
        assertTableDefinitionFlow(
                DATABASE,
                DmTableDefinitionIntegrationUser.class,
                "auto_dm_table_definition_user",
                null,
                "COMMENT ON TABLE auto_dm_table_definition_user IS 'DM表';"
        );
    }

    @Test
    void dmShouldCreateMultiplePhysicalTablesWithSequenceAndIndexes() throws Exception {
        assertMultiTableSequenceAndIndexFlow(DATABASE);
    }

    @Test
    void dmShouldSyncAndDeleteMissingColumnsAndIndexes() throws Exception {
        assertSyncFlow(
                DATABASE,
                SyncUserV1.class,
                SyncUserV2.class,
                "auto_sync_user",
                "DROP INDEX idx_sync_legacy_code;",
                "ALTER TABLE auto_sync_user DROP COLUMN legacy_code;",
                "ALTER TABLE auto_sync_user ADD email VARCHAR2(128);",
                "CREATE INDEX idx_sync_email ON auto_sync_user (email);"
        );
    }

    @Test
    void dmShouldModifyChangedColumnsInSyncMode() throws Exception {
        assertSyncModifyFlow(
                DATABASE,
                SyncModifyUserV1.class,
                SyncModifyUserV2.class,
                "auto_sync_modify_user",
                "ALTER TABLE auto_sync_modify_user MODIFY (username VARCHAR2(128));"
        );
    }

    @Test
    void dmShouldModifyColumnDefaultInSyncMode() throws Exception {
        assertSyncModifyDefaultFlow(
                DATABASE,
                "ALTER TABLE auto_sync_modify_default_user MODIFY (username DEFAULT 'new');",
                "ALTER TABLE auto_sync_modify_default_user MODIFY (create_time DEFAULT CURRENT_TIMESTAMP);"
        );
    }

    @Test
    void dmShouldSyncTypeLengthAndDefaultCombinations() throws Exception {
        assertTypeLengthDefaultCombinationFlow(
                DATABASE,
                "ALTER TABLE auto_type_length_default_user MODIFY (nickname DEFAULT 'new');",
                "ALTER TABLE auto_type_length_default_user MODIFY (amount DEFAULT 2.5);",
                "ALTER TABLE auto_type_length_default_user MODIFY (biz_date DEFAULT TRUNC(SYSDATE));",
                "ALTER TABLE auto_type_length_default_user MODIFY (sign_date DEFAULT TRUNC(SYSDATE));",
                "ALTER TABLE auto_type_length_default_user MODIFY (start_time DEFAULT CURRENT_TIMESTAMP);",
                "ALTER TABLE auto_type_length_default_user MODIFY (created_at DEFAULT CURRENT_TIMESTAMP);",
                "ALTER TABLE auto_type_length_default_user MODIFY (event_at DEFAULT CURRENT_TIMESTAMP);"
        );
    }

    @Test
    void dmShouldCreateColumnTypeMatrix() throws Exception {
        assertColumnTypeMatrixFlow(
                DATABASE,
                "short_text VARCHAR2(64)",
                "large_text CLOB",
                "int_value INTEGER",
                "long_value BIGINT",
                "big_number BIGINT",
                "short_value NUMBER(5)",
                "byte_value NUMBER(3)",
                "enabled NUMBER(1)",
                "amount NUMBER(12,4)",
                "ratio BINARY_FLOAT",
                "score DOUBLE PRECISION",
                "grade VARCHAR2(1)",
                "payload BLOB",
                "biz_date DATE",
                "biz_time TIMESTAMP",
                "sql_date DATE",
                "sql_time TIMESTAMP",
                "created_at TIMESTAMP",
                "sql_created_at TIMESTAMP",
                "legacy_created_at TIMESTAMP",
                "event_at TIMESTAMP WITH TIME ZONE",
                "offset_at TIMESTAMP WITH TIME ZONE",
                "zoned_at TIMESTAMP WITH TIME ZONE",
                "request_id VARCHAR2(36)"
        );
    }

    @Test
    void dmShouldDropColumnDefaultInSyncMode() throws Exception {
        assertSyncDropDefaultFlow(
                DATABASE,
                "ALTER TABLE auto_sync_drop_default_user MODIFY (username DEFAULT NULL);"
        );
    }

    @Test
    void dmShouldModifyPrimaryKeyAutoIncrementInSyncMode() throws Exception {
        assertSyncModifyAutoIncrementFlow(
                DATABASE,
                "ALTER TABLE auto_sync_modify_auto_id_user ADD COLUMN id IDENTITY(1,1);"
        );
    }

    @Test
    void dmShouldRemovePrimaryKeyAutoIncrementInSyncMode() throws Exception {
        assertSyncModifyAutoIncrementReverseFlow(
                DATABASE,
                "ALTER TABLE auto_sync_modify_auto_id_reverse_user DROP IDENTITY;"
        );
    }

    @Test
    void dmShouldModifyMultipleChangedColumnsInSingleAlter() throws Exception {
        assertSyncModifyBatchFlow(
                DATABASE,
                SyncModifyBatchUserV1.class,
                SyncModifyBatchUserV2.class,
                "auto_sync_modify_batch_user",
                "ALTER TABLE auto_sync_modify_batch_user MODIFY (username VARCHAR2(128), balance NUMBER(18,4));",
                64,
                128,
                12,
                18,
                4
        );
    }

    @Test
    void dmShouldModifyChangedColumnCommentInSyncMode() throws Exception {
        assertSyncModifyCommentFlow(
                DATABASE,
                SyncModifyCommentUserV1.class,
                SyncModifyCommentUserV2.class,
                "auto_sync_modify_comment_user",
                "COMMENT ON COLUMN auto_sync_modify_comment_user.username IS 'new comment';"
        );
    }

    @Table("auto_dm_itg_user")
    @Index(name = "idx_dm_itg_user_name", fields = @IndexField(name = "username"))
    static class DmIntegrationUserV1 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_dm_itg_user")
    @Indexs({
            @Index(name = "idx_dm_itg_user_name", fields = @IndexField(name = "username")),
            @Index(name = "idx_dm_itg_email", fields = @IndexField(name = "email")),
            @Index(name = "idx_dm_itg_name_ct", fields = {
                    @IndexField(name = "username", direction = IndexDirection.ASC),
                    @IndexField(name = "createdAt", direction = IndexDirection.DESC)
            })
    })
    static class DmIntegrationUserV2 {

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

    @Table("auto_dm_table_definition_user")
    @TableDefinition(comment = "DM表")
    static class DmTableDefinitionIntegrationUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
