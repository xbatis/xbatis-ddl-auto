# xbatis-ddl-auto

xbatis-ddl-auto 是一个基于 xbatis 实体元数据的轻量自动建表工具。

它复用 xbatis 的 `@Table`、`@TableId`、`@TableField`、`@ColumnDefinition` 等注解解析结果，根据实体类生成并执行数据库 DDL，提供接近 JPA `ddl-auto=create/update` 的使用体验，但不引入 JPA 或 Hibernate。

## 功能

- 根据 xbatis 实体生成 `CREATE TABLE` SQL
- 表不存在时自动建表
- 表存在时可在 `UPDATE` 模式下自动追加新增字段
- 支持只生成 SQL，不执行数据库操作
- 通过 JDBC `DatabaseMetaData` 判断表、字段和索引是否存在
- 支持常见 Java 类型到数据库列类型映射
- 支持 `@ColumnDefinition` 配置字段长度、精度、小数位、默认值、唯一约束、非空和字段注释
- 支持类级 `@Index` 创建普通索引和唯一索引

## 安全边界

`UPDATE` 模式只会自动新增字段和缺失索引，不会自动执行以下高风险操作：

- 删除数据库已有字段
- 修改字段类型
- 修改字段长度
- 修改字段是否可空
- 修改默认值
- 重命名字段
- 修改或删除已有索引
- 删除或重建表

这些操作可能造成数据丢失或生产事故，建议通过人工审核 SQL 或专业迁移工具处理。

## Maven

本工具 Maven 坐标：

```xml
<dependency>
    <groupId>cn.xbatis</groupId>
    <artifactId>xbatis-ddl-auto</artifactId>
    <version>1.0.1</version>
</dependency>
```

运行依赖 xbatis core：

```xml
<dependency>
    <groupId>cn.xbatis</groupId>
    <artifactId>xbatis-core</artifactId>
    <version>1.10.6</version>
</dependency>
```

默认测试使用 JUnit 5 和 H2；需要真实数据库的集成测试通过 Maven profile 单独执行。

## 快速开始

定义 xbatis 实体：

```java
import cn.xbatis.db.annotations.ColumnDefinition;
import cn.xbatis.db.annotations.Table;
import cn.xbatis.db.annotations.TableId;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table("sys_user")
public class SysUser {

    @TableId
    private Long id;

    @ColumnDefinition(length = 64, nullable = false)
    private String username;

    @ColumnDefinition(precision = 10, scale = 2, defaultValue = "0")
    private BigDecimal balance;

    private LocalDateTime createdAt;
}
```

执行自动建表：

```java

import db.sql.api.DbType;

DDLAuto.of(DbType.MYSQL)
    .add(SysUser.class)
    .execute(dataSource);
```

## CREATE 模式

`CREATE` 是默认模式：

```java
DDLAuto.of(DbType.MYSQL)
    .add(SysUser.class)
    .execute(dataSource);
```

行为：

- 表不存在：执行 `CREATE TABLE`
- 表已存在：跳过，不做任何变更

## UPDATE 模式

`UPDATE` 模式用于补新增字段和缺失索引：

```java
import cn.xbatis.ddl.auto.Mode;

DDLAuto.of(DbType.MYSQL)
    .mode(Mode.UPDATE)
    .add(SysUser.class)
    .execute(dataSource);
```

行为：

- 表不存在：执行 `CREATE TABLE`
- 表已存在：读取数据库已有列和索引，只对实体中新增的字段执行 `ALTER TABLE ... ADD COLUMN ...`，并创建缺失索引

重复执行 `UPDATE` 模式不会重复添加已存在字段或已存在索引。

## 只生成 SQL / 预览 SQL

不连接数据库，只生成建表 SQL：

```java
List<String> sqlList = DDLAuto.of(DbType.PGSQL)
        .add(SysUser.class)
        .sqlList();
```

如果要按当前数据库状态预览将要执行的 SQL，可以传入 `DataSource` 或 `Connection`。该方法只读取 JDBC 元数据并生成 SQL，不会执行 DDL：

```java
List<String> sqlList = DDLAuto.of(DbType.MYSQL)
        .mode(Mode.UPDATE)
        .add(SysUser.class)
        .sqlList(dataSource);
```

行为：

- 表不存在：返回 `CREATE TABLE` 及附属 DDL
- 表已存在且是 `CREATE` 模式：返回空列表
- 表已存在且是 `UPDATE` 模式：只返回缺失字段的 `ALTER TABLE ... ADD COLUMN ...` 及附属 DDL，以及缺失索引的 `CREATE INDEX`

也可以使用底层构建器生成单个字段的新增列 SQL：

```java
import cn.xbatis.ddl.auto.DDLBuilder;
import cn.xbatis.ddl.auto.DefaultDDLBuilder;

DDLBuilder builder = new DefaultDDLBuilder();
String sql = builder.addColumnSql(DbType.MYSQL, SysUser.class, "email");
```

生产或准生产环境建议先通过 `sqlList(dataSource)` 或 `sqlList(connection)` 生成 SQL 并审核，再决定是否执行。

## 执行监听

DDL 会按 SQL 列表逐条执行。若中途失败，数据库可能已经保留前面成功执行的 DDL。可以配置执行监听器记录已执行 SQL：

```java
import cn.xbatis.ddl.auto.Mode;
import cn.xbatis.ddl.auto.DDLExecutionListener;

import java.util.ArrayList;
import java.util.List;

List<String> executedSqlLog = new ArrayList<>();

DDLAuto.of(DbType.MYSQL)
    .mode(Mode.UPDATE)
    .executionListener(new DDLExecutionListener() {
    @Override
    public void afterExecute(String sql, List<String> executedSqlList) {
        executedSqlLog.add(sql);
    }

    @Override
    public void onExecuteError(String sql, SQLException exception, List<String> executedSqlList) {
        // sql 为当前失败 SQL，executedSqlList 为失败前已成功执行的 SQL。
    }
    })
    .add(SysUser.class)
    .execute(dataSource);
```

执行失败时抛出的 `SQLException` 消息也会包含当前失败 SQL 和失败前已执行 SQL。

## 支持的注解

### `@Table`

用于解析表名和 schema：

```java
@Table("sys_user")
public class SysUser {
}
```

### `@TableId`

用于识别主键和数据库自增：

```java
@TableId
private Long id;
```

默认自增类型为 xbatis 的 `IdAutoType.AUTO`。
单主键会按数据库方言生成自增片段；联合主键不会为每个主键字段自动生成自增片段。

如果主键使用 `IdAutoType.SQL` 通过数据库序列取值，会从 `sql` 中解析序列名并在建表前生成 `CREATE SEQUENCE`：

```java
@TableId(dbType = DbType.Name.PGSQL, value = IdAutoType.SQL, sql = "select nextval('id_test_id_seq')")
@TableId(dbType = DbType.Name.ORACLE, value = IdAutoType.SQL, sql = "select id_test_seq.NEXTVAL FROM dual")
@TableId(dbType = DbType.Name.SQL_SERVER, value = IdAutoType.SQL, sql = "select next value for id_test_sqlserver_seq")
@TableId(dbType = DbType.Name.DB2, value = IdAutoType.SQL, sql = "select next value for id_test_db2_seq from sysibm.sysdummy1")
private Long id;
```

当前支持解析：

- PostgreSQL：`nextval('sequence_name')`
- Oracle / DM：`sequence_name.NEXTVAL`
- SQL Server / DB2：`NEXT VALUE FOR sequence_name`
- 其他数据库：兜底解析上述常见形式，并生成通用序列 DDL：

```sql
CREATE SEQUENCE my_sequence
    START WITH 1
    INCREMENT BY 1;
```

`UPDATE` 模式会读取数据库已有序列，只创建缺失序列，不重复创建。

### `@ColumnDefinition`

用于控制建表字段定义：

```java
@ColumnDefinition(
        length = 64,
        nullable = false,
        unique = true,
        comment = "用户名"
)
private String username;
```

常用配置：

- `length`：字符串长度
- `precision`：数值精度
- `scale`：小数位数
- `defaultValue`：数据库默认值 SQL 片段
- `nullable`：是否允许为空
- `unique`：是否唯一
- `definition`：字段类型片段，配置后优先替代 Java 类型到数据库类型的自动推导，`length`、`precision`、`scale`、`defaultValue`、`nullable`、`unique`、`comment` 等其他配置仍会继续生效
- `comment`：字段注释；MySQL 使用列内联 `COMMENT`，PostgreSQL / Oracle / DM 使用独立 `COMMENT ON COLUMN`，SQL Server 使用 `sys.sp_addextendedproperty`

当 `definition` 本身没有包含括号参数时，会按配置补齐长度或精度，例如 `@ColumnDefinition(definition = "VARCHAR", length = 64)` 会生成 `VARCHAR(64)`；如果已经写成 `VARCHAR(64)`，则不会再追加参数。

`unique = true` 目前表示单字段唯一约束：

- `CREATE` 模式下，多数关系型数据库使用列内联 `UNIQUE`
- `UPDATE` 模式新增字段时，SQLite 不支持 `ALTER TABLE ADD COLUMN ... UNIQUE`，会改为先新增字段再生成 `CREATE UNIQUE INDEX`
- ClickHouse 不支持传统唯一约束，配置 `unique = true` 时会直接抛出异常，避免生成无效 SQL
- 不支持联合唯一、部分唯一索引、命名唯一约束和已存在字段的唯一约束同步

### `@Index`

用于在实体类上声明数据库索引：

```java
import cn.xbatis.db.IndexDirection;
import cn.xbatis.db.annotations.Index;
import cn.xbatis.db.annotations.IndexField;
import cn.xbatis.db.annotations.Table;

@Index(name = "idx_sys_user_username", fields = @IndexField(name = "username"))
@Index(
        name = "uk_sys_user_username_created_at",
        unique = true,
        fields = {
                @IndexField(name = "username"),
                @IndexField(name = "createdAt", direction = IndexDirection.DESC)
        }
)
@Table("sys_user")
public class SysUser {
}
```

说明：

- `name`：索引名；为空时按表名和列名生成稳定索引名
- `unique`：是否唯一索引
- `fields`：索引字段，`name` 使用实体字段名，也兼容已映射的列名
- `direction`：索引字段排序，支持 `ASC`、`DESC`，默认不追加排序片段
- `CREATE` 模式下，建表后生成 `CREATE INDEX`
- `UPDATE` 模式下，只按索引名创建数据库中缺失的索引，不修改或删除已有索引
- ClickHouse 不支持传统 `CREATE INDEX` 时会直接抛出异常，避免生成无效 SQL

PostgreSQL 字段注释会生成额外 SQL：

```sql
COMMENT ON COLUMN sys_user.username IS '用户名';
```

SQL Server 字段注释会生成扩展属性 SQL：

```sql
DECLARE @schema sysname = SCHEMA_NAME();
EXEC sys.sp_addextendedproperty
  @name=N'MS_Description',
  @value=N'用户名',
  @level0type=N'SCHEMA',
  @level0name=@schema,
  @level1type=N'TABLE',
  @level1name=N'sys_user',
  @level2type=N'COLUMN',
  @level2name=N'username';
```

## 类型映射

默认类型映射包括：

- `String` / `Character` / `UUID` -> `VARCHAR`
- `Integer` / `int` -> `INTEGER`
- `Long` / `long` / `BigInteger` -> `BIGINT`
- `Short` / `short` -> `SMALLINT`
- `Boolean` / `boolean` -> `BOOLEAN`、MySQL 为 `TINYINT(1)`
- `BigDecimal` -> `DECIMAL(precision, scale)`
- `Float` / `Double` -> 浮点类型
- `byte[]` -> 二进制大字段
- `LocalDate` -> `DATE`
- `LocalTime` -> `TIME`，Oracle / DM 使用 `TIMESTAMP`
- `LocalDateTime` / `Timestamp` / `Date` -> `TIMESTAMP`，MySQL 使用 `DATETIME`，SQL Server 使用 `DATETIME2`
- 普通 `enum` -> `VARCHAR(64)`
- 实现 xbatis `EnumSupport<T>` 的枚举 -> 按 `T` 的类型映射，例如 `EnumSupport<Integer>` -> `INTEGER`

具体类型会根据 `DbType` 做方言调整。

## 测试

运行：

```bash
mvn test
```

默认 `mvn test` 会排除带 `integration` tag 的真实数据库集成测试，只运行本地单元测试和 H2 测试。执行真实数据库集成测试时使用：

```bash
mvn -Pintegration-tests test
```

本地 MySQL 集成用例会默认连接 `127.0.0.1:3306/ddl_test`，账号 `root`，密码 `123456`；测试 URL 会使用 MySQL JDBC 的
`createDatabaseIfNotExist=true` 自动建库，连接不可用时自动跳过。需要改连接信息时可使用：

```bash
mvn -Pintegration-tests test \
    -Dmysql.test.url='jdbc:mysql://127.0.0.1:3306/ddl_test?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
    -Dmysql.test.username=root \
    -Dmysql.test.password=123456
```

执行 JaCoCo 行覆盖率检查：

```bash
mvn -Pcoverage verify
```

当前测试覆盖：

- `CREATE` 模式真实创建 H2 表和字段
- `UPDATE` 模式对已存在表追加新增字段
- `UPDATE` 模式对已存在表创建缺失索引
- `UPDATE` 模式重复执行不重复添加字段或索引
- MySQL 真实连接下的建表、追加字段和补索引集成用例
- DDL 执行监听和失败 SQL 记录
- schema 同名表误判防护和 quoted identifier 元数据匹配
- MySQL / PostgreSQL / Oracle / SQL Server 字段注释 SQL 生成
- SQL Server、Oracle、PostgreSQL 的关键类型映射差异
- SQLite 新增唯一字段时单独生成唯一索引
- ClickHouse 不支持唯一约束时快速失败

## 注意事项

- 该工具只处理 xbatis 实体类，实体类必须标注 `@Table`
- 不会扫描包路径，需要显式传入实体类
- 不负责事务管理，调用方应按项目环境决定是否在外部包裹事务
- DDL 按语句逐条执行；并非所有数据库都支持 DDL 事务，中途失败时可能留下半完成状态
- 执行前建议先调用 `sqlList()` 审核 SQL；直接执行时建议配置 `DDLExecutionListener` 记录已执行 SQL
- 表和字段存在性依赖 JDBC `DatabaseMetaData`，已兼容常见大小写、schema、catalog 和 quoted identifier 场景；特殊驱动仍建议先在目标数据库做集成验证
- 自动建表更适合开发环境、测试环境、首次初始化或可控场景
- 生产环境建议先生成 SQL 审核后再执行
