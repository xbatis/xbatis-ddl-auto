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
class DDLAutoMariadbIntegrationTest extends DDLAutoExternalDatabaseIntegrationSupport {

    private static final DatabaseCase DATABASE = new DatabaseCase(
            DbType.MARIA_DB,
            "MariaDB",
            "org.mariadb.jdbc.Driver",
            System.getProperty("mariadb.test.url", "jdbc:mariadb://127.0.0.1:3307/ddl_test?createDatabaseIfNotExist=true&connectTimeout=2000&socketTimeout=5000"),
            System.getProperty("mariadb.test.username", "root"),
            System.getProperty("mariadb.test.password", "123456")
    );

    @Test
    void mariadbShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        assertCreateUpdateFlow(
                DATABASE,
                MariadbIntegrationUserV1.class,
                MariadbIntegrationUserV2.class,
                "auto_mariadb_itg_user",
                "idx_mdb_itg_user_name",
                "idx_mdb_itg_email",
                "idx_mdb_itg_name_ct",
                "ALTER TABLE auto_mariadb_itg_user ADD COLUMN email VARCHAR(128) AFTER created_at;"
        );
    }

    @Test
    void mariadbShouldAddMultipleMissingColumnsFollowingEntityOrder() throws Exception {
        assertMultiColumnAddColumnFlow(
                DATABASE,
                "ALTER TABLE auto_multi_column_add_user ADD COLUMN age INTEGER AFTER username, "
                        + "ADD COLUMN email VARCHAR(128) AFTER age;"
        );
    }

    @Test
    void mariadbShouldCreateBooleanDefaultValueColumns() throws Exception {
        assertBooleanDefaultValueFlow(
                DATABASE,
                "TINYINT(1)",
                "0",
                "1"
        );
    }

    @Test
    void mariadbShouldCreateDateTimeDefaultValueColumns() throws Exception {
        assertDateTimeDefaultValueFlow(
                DATABASE,
                "created_at DATETIME DEFAULT CURRENT_TIMESTAMP",
                "event_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
        );
    }

    @Test
    void mariadbShouldCreateDateDefaultValueColumns() throws Exception {
        assertDateDefaultValueFlow(
                DATABASE,
                "biz_date DATE DEFAULT (CURRENT_DATE)",
                "today_date DATE DEFAULT (CURRENT_DATE)"
        );
    }

    @Test
    void mariadbShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        assertIntLongAutoAndManualIdFlow(
                DATABASE,
                "id INTEGER AUTO_INCREMENT NOT NULL PRIMARY KEY",
                "id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY",
                "id INTEGER NOT NULL PRIMARY KEY",
                "id BIGINT NOT NULL PRIMARY KEY"
        );
    }

    @Test
    void mariadbShouldCreateTableDefinition() throws Exception {
        assertTableDefinitionFlow(
                DATABASE,
                MariadbTableDefinitionIntegrationUser.class,
                "auto_mariadb_table_definition_user",
                "ENGINE=InnoDB COMMENT='MariaDB表'",
                null
        );
    }

    @Test
    void mariadbShouldCreateMultiplePhysicalTablesWithIndexes() throws Exception {
        assertMultiTableIndexFlow(DATABASE);
    }

    @Test
    void mariadbShouldSyncAndDeleteMissingColumnsAndIndexes() throws Exception {
        assertSyncFlow(
                DATABASE,
                SyncUserV1.class,
                SyncUserV2.class,
                "auto_sync_user",
                "DROP INDEX idx_sync_legacy_code ON auto_sync_user;",
                "ALTER TABLE auto_sync_user DROP COLUMN legacy_code;",
                "ALTER TABLE auto_sync_user ADD COLUMN email VARCHAR(128) AFTER username;",
                "CREATE INDEX idx_sync_email ON auto_sync_user (email);"
        );
    }

    @Test
    void mariadbShouldModifyChangedColumnsInSyncMode() throws Exception {
        assertSyncModifyFlow(
                DATABASE,
                SyncModifyUserV1.class,
                SyncModifyUserV2.class,
                "auto_sync_modify_user",
                "ALTER TABLE auto_sync_modify_user MODIFY COLUMN username VARCHAR(128);"
        );
    }

    @Test
    void mariadbShouldModifyColumnDefaultInSyncMode() throws Exception {
        assertSyncModifyDefaultFlow(
                DATABASE,
                "ALTER TABLE auto_sync_modify_default_user ALTER COLUMN username SET DEFAULT 'new';"
        );
    }

    @Test
    void mariadbShouldDropColumnDefaultInSyncMode() throws Exception {
        assertSyncDropDefaultFlow(
                DATABASE,
                "ALTER TABLE auto_sync_drop_default_user ALTER COLUMN username DROP DEFAULT;"
        );
    }

    @Test
    void mariadbShouldModifyPrimaryKeyAutoIncrementInSyncMode() throws Exception {
        assertSyncModifyAutoIncrementFlow(
                DATABASE,
                "ALTER TABLE auto_sync_modify_auto_id_user MODIFY COLUMN id BIGINT AUTO_INCREMENT NOT NULL;"
        );
    }

    @Test
    void mariadbShouldRemovePrimaryKeyAutoIncrementInSyncMode() throws Exception {
        assertSyncModifyAutoIncrementReverseFlow(
                DATABASE,
                "ALTER TABLE auto_sync_modify_auto_id_reverse_user MODIFY COLUMN id BIGINT NOT NULL;"
        );
    }

    @Test
    void mariadbShouldModifyMultipleChangedColumnsInSingleAlter() throws Exception {
        assertSyncModifyBatchFlow(
                DATABASE,
                SyncModifyBatchUserV1.class,
                SyncModifyBatchUserV2.class,
                "auto_sync_modify_batch_user",
                "ALTER TABLE auto_sync_modify_batch_user MODIFY COLUMN username VARCHAR(128), MODIFY COLUMN balance DECIMAL(18,4);",
                64,
                128,
                12,
                18,
                4
        );
    }

    @Test
    void mariadbShouldModifyChangedColumnCommentInSyncMode() throws Exception {
        assertSyncModifyCommentFlow(
                DATABASE,
                SyncModifyCommentUserV1.class,
                SyncModifyCommentUserV2.class,
                "auto_sync_modify_comment_user",
                "ALTER TABLE auto_sync_modify_comment_user MODIFY COLUMN username VARCHAR(64) NOT NULL COMMENT 'new comment';"
        );
    }

    @Table("auto_mariadb_itg_user")
    @Index(name = "idx_mdb_itg_user_name", fields = @IndexField(name = "username"))
    static class MariadbIntegrationUserV1 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_mariadb_itg_user")
    @Indexs({
            @Index(name = "idx_mdb_itg_user_name", fields = @IndexField(name = "username")),
            @Index(name = "idx_mdb_itg_email", fields = @IndexField(name = "email")),
            @Index(name = "idx_mdb_itg_name_ct", fields = {
                    @IndexField(name = "username", direction = IndexDirection.ASC),
                    @IndexField(name = "createdAt", direction = IndexDirection.DESC)
            })
    })
    static class MariadbIntegrationUserV2 {

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

    @Table("auto_mariadb_table_definition_user")
    @TableDefinition(definition = "ENGINE=InnoDB", comment = "MariaDB表")
    static class MariadbTableDefinitionIntegrationUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
