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
class DDLAutoOceanbaseIntegrationTest extends DDLAutoExternalDatabaseIntegrationSupport {

    private static final DatabaseCase DATABASE = new DatabaseCase(
            DbType.OCEAN_BASE,
            "OceanBase",
            "com.oceanbase.jdbc.Driver",
            System.getProperty("oceanbase.test.url", "jdbc:oceanbase://127.0.0.1:2881/test3?createDatabaseIfNotExist=true&connectTimeout=2000&socketTimeout=5000"),
            System.getProperty("oceanbase.test.username", "root"),
            System.getProperty("oceanbase.test.password", "test@123")
    );

    @Test
    void oceanbaseShouldCreateTableAddColumnAndCreateMissingIndexes() throws Exception {
        assertCreateUpdateFlow(
                DATABASE,
                OceanbaseIntegrationUserV1.class,
                OceanbaseIntegrationUserV2.class,
                "auto_ob_itg_user",
                "idx_ob_itg_user_name",
                "idx_ob_itg_email",
                "idx_ob_itg_name_ct",
                "ALTER TABLE auto_ob_itg_user ADD COLUMN email VARCHAR(128);"
        );
    }

    @Test
    void oceanbaseShouldCreateIntLongAutoAndManualIdTables() throws Exception {
        assertIntLongAutoAndManualIdFlow(
                DATABASE,
                "id INTEGER AUTO_INCREMENT NOT NULL PRIMARY KEY",
                "id BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY",
                "id INTEGER NOT NULL PRIMARY KEY",
                "id BIGINT NOT NULL PRIMARY KEY"
        );
    }

    @Test
    void oceanbaseShouldCreateTableDefinition() throws Exception {
        assertTableDefinitionFlow(
                DATABASE,
                OceanbaseTableDefinitionIntegrationUser.class,
                "auto_ob_table_definition_user",
                "ENGINE=InnoDB COMMENT='OceanBase表'",
                null
        );
    }

    @Test
    void oceanbaseShouldCreateMultiplePhysicalTablesWithIndexes() throws Exception {
        assertMultiTableIndexFlow(DATABASE);
    }

    @Table("auto_ob_itg_user")
    @Index(name = "idx_ob_itg_user_name", fields = @IndexField(name = "username"))
    static class OceanbaseIntegrationUserV1 {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false, comment = "用户名")
        private String username;

        @ColumnDefinition(precision = 12, scale = 2, defaultValue = "0")
        private BigDecimal balance;

        private LocalDateTime createdAt;
    }

    @Table("auto_ob_itg_user")
    @Indexs({
            @Index(name = "idx_ob_itg_user_name", fields = @IndexField(name = "username")),
            @Index(name = "idx_ob_itg_email", fields = @IndexField(name = "email")),
            @Index(name = "idx_ob_itg_name_ct", fields = {
                    @IndexField(name = "username", direction = IndexDirection.ASC),
                    @IndexField(name = "createdAt", direction = IndexDirection.DESC)
            })
    })
    static class OceanbaseIntegrationUserV2 {

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

    @Table("auto_ob_table_definition_user")
    @TableDefinition(definition = "ENGINE=InnoDB", comment = "OceanBase表")
    static class OceanbaseTableDefinitionIntegrationUser {

        @TableId(value = IdAutoType.NONE)
        private Long id;

        @ColumnDefinition(length = 64, nullable = false)
        private String username;
    }
}
