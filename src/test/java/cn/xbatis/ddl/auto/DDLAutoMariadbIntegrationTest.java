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
                "ALTER TABLE auto_mariadb_itg_user ADD COLUMN email VARCHAR(128);"
        );
    }

    @Test
    void mariadbShouldAddMultipleMissingColumnsInSingleAlter() throws Exception {
        assertMultiColumnAddColumnFlow(
                DATABASE,
                "ALTER TABLE auto_multi_column_add_user ADD COLUMN age INTEGER, ADD COLUMN email VARCHAR(128);"
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
