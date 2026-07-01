package cn.xbatis.ddl.auto;

import db.sql.api.DbType;
import db.sql.api.IDbType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * 自动建表的门面类，提供接近 JPA hbm2ddl.auto=create/update 的使用体验。
 * <p>
 * 使用者只需要指定数据库类型、注册 xbatis 实体类，然后选择生成 SQL 或直接执行建表。
 */
public class DDLAuto {

    private final IDbType dbType;

    private final List<Class<?>> entityClasses = new ArrayList<>();

    private DDLBuilder ddlBuilder = new DefaultDDLBuilder();

    private Mode mode = Mode.CREATE;

    private DDLExecutionListener executionListener = DDLExecutionListener.NONE;

    private DDLAuto(IDbType dbType) {
        this.dbType = Objects.requireNonNull(dbType, "dbType");
        if (dbType == DbType.UNKNOWN) {
            throw new IllegalArgumentException("dbType must be a concrete database type");
        }
    }

    /**
     * 创建自动建表入口。
     *
     * @param dbType xbatis SQL API 的数据库类型
     * @return 自动建表配置对象
     */
    public static DDLAuto of(IDbType dbType) {
        return new DDLAuto(dbType);
    }

    /**
     * 替换默认 SQL 构建器。
     * <p>
     * 适用于项目需要定制字段类型映射、默认值规则或数据库方言时。
     *
     * @param ddlBuilder SQL 构建器
     * @return 当前对象
     */
    public DDLAuto builder(DDLBuilder ddlBuilder) {
        this.ddlBuilder = Objects.requireNonNull(ddlBuilder, "ddlBuilder");
        return this;
    }

    /**
     * 设置 DDL 执行监听器。
     * <p>
     * 可用于记录已经执行的 SQL，或在执行失败时输出当前 SQL。
     *
     * @param executionListener 执行监听器
     * @return 当前对象
     */
    public DDLAuto executionListener(DDLExecutionListener executionListener) {
        this.executionListener = executionListener == null ? DDLExecutionListener.NONE : executionListener;
        return this;
    }

    /**
     * 设置自动建表模式。
     *
     * @param mode 自动建表模式
     * @return 当前对象
     */
    public DDLAuto mode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * 注册需要自动建表的 xbatis 实体类。
     *
     * @param entityClasses 实体类
     * @return 当前对象
     */
    public DDLAuto add(Class<?>... entityClasses) {
        Objects.requireNonNull(entityClasses, "entityClasses");
        return add(Arrays.asList(entityClasses));
    }

    /**
     * 批量注册需要自动建表的 xbatis 实体类。
     *
     * @param entityClasses 实体类集合
     * @return 当前对象
     */
    public DDLAuto add(Collection<Class<?>> entityClasses) {
        Objects.requireNonNull(entityClasses, "entityClasses");
        List<Class<?>> checkedEntityClasses = new ArrayList<>(entityClasses.size());
        for (Class<?> entityClass : entityClasses) {
            checkedEntityClasses.add(Objects.requireNonNull(entityClass, "entityClass"));
        }
        this.entityClasses.addAll(checkedEntityClasses);
        return this;
    }

    /**
     * 获取已注册的实体类。
     *
     * @return 不可变实体类列表
     */
    public List<Class<?>> getEntityClasses() {
        return Collections.unmodifiableList(entityClassesSnapshot());
    }

    /**
     * 只生成建表 SQL，不连接数据库。
     *
     * @return 所有已注册实体对应的建表 SQL
     */
    public List<String> sqlList() {
        if (mode == Mode.NONE) {
            return Collections.emptyList();
        }
        List<Class<?>> entityClasses = entityClassesSnapshot();
        List<String> result = new ArrayList<>(entityClasses.size());
        for (Class<?> entityClass : entityClasses) {
            result.addAll(ddlBuilder.createTableSqlList(dbType, entityClass));
        }
        return result;
    }

    /**
     * 从数据源获取连接，按当前数据库状态生成将要执行的 DDL SQL，不执行。
     * <p>
     * CREATE 模式下只返回缺失表的 CREATE TABLE；UPDATE 模式下还会返回缺失字段的 ADD COLUMN。
     *
     * @param dataSource 数据源
     * @return 将要执行的 DDL SQL 列表
     */
    public List<String> sqlList(DataSource dataSource)  {
        if (mode == Mode.NONE) {
            return Collections.emptyList();
        }
        try {
            return executor().sqlList(dbType, dataSource, mode, entityClassesSnapshot());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 使用已有连接，按当前数据库状态生成将要执行的 DDL SQL，不执行。
     * <p>
     * CREATE 模式下只返回缺失表的 CREATE TABLE；UPDATE 模式下还会返回缺失字段的 ADD COLUMN。
     *
     * @param connection 数据库连接
     * @return 将要执行的 DDL SQL 列表
     */
    public List<String> sqlList(Connection connection)  {
        if (mode == Mode.NONE) {
            return Collections.emptyList();
        }
        try {
            return executor().sqlList(dbType, connection, mode, entityClassesSnapshot());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 使用数据源执行自动建表。
     *
     * @param dataSource 数据源
     */
    public void execute(DataSource dataSource)  {
        if (mode == Mode.NONE) {
            return;
        }
        try {
            executor().execute(dbType, dataSource, mode, entityClassesSnapshot());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 使用已有数据库连接执行自动建表。
     *
     * @param connection 数据库连接
     */
    public void execute(Connection connection) {
        if (mode == Mode.NONE) {
            return;
        }
        try {
            executor().execute(dbType, connection, mode, entityClassesSnapshot());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Class<?>> entityClassesSnapshot() {
        return new ArrayList<>(entityClasses);
    }

    private DDLAutoExecutor executor() {
        return new DefaultDDLAutoExecutor(ddlBuilder, executionListener);
    }
}
