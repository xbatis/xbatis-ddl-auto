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
                "ALTER TABLE auto_multi_column_add_user ADD (age NUMBER(10), email VARCHAR2(128));"
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
                "id NUMBER(10) NOT NULL PRIMARY KEY",
                "id NUMBER(19) NOT NULL PRIMARY KEY"
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
