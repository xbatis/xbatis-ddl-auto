package cn.xbatis.ddl.auto;

import db.sql.api.IDbType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * 自动建表执行器接口。
 * <p>
 * 只暴露按指定模式生成 SQL 和执行 SQL 的基础能力；具体元数据读取、方言适配和执行记录由实现类完成。
 */
public interface DDLAutoExecutor {

    /**
     * 从数据源获取连接，按当前数据库状态生成将要执行的 DDL SQL，不执行。
     *
     * @param dbType        数据库类型
     * @param dataSource    数据源
     * @param mode          自动建表模式
     * @param entityClasses xbatis 实体类集合
     * @return 将要执行的 DDL SQL 列表
     * @throws SQLException 元数据读取失败时抛出
     */
    List<String> sqlList(IDbType dbType, DataSource dataSource, Mode mode, Collection<Class<?>> entityClasses) throws SQLException;

    /**
     * 使用已有连接，按当前数据库状态生成将要执行的 DDL SQL，不执行。
     *
     * @param dbType        数据库类型
     * @param connection    数据库连接
     * @param mode          自动建表模式
     * @param entityClasses xbatis 实体类集合
     * @return 将要执行的 DDL SQL 列表
     * @throws SQLException 元数据读取失败时抛出
     */
    List<String> sqlList(IDbType dbType, Connection connection, Mode mode, Collection<Class<?>> entityClasses) throws SQLException;

    /**
     * 从数据源获取连接并按指定模式批量执行自动建表。
     *
     * @param dbType        数据库类型
     * @param dataSource    数据源
     * @param mode          自动建表模式
     * @param entityClasses xbatis 实体类集合
     * @throws SQLException 执行失败时抛出
     */
    void execute(IDbType dbType, DataSource dataSource, Mode mode, Collection<Class<?>> entityClasses) throws SQLException;

    /**
     * 使用已有连接按指定模式批量执行自动建表。
     *
     * @param dbType        数据库类型
     * @param connection    数据库连接
     * @param mode          自动建表模式
     * @param entityClasses xbatis 实体类集合
     * @throws SQLException 执行失败时抛出
     */
    void execute(IDbType dbType, Connection connection, Mode mode, Collection<Class<?>> entityClasses) throws SQLException;

    /**
     * 获取最近一次自动建表执行中已成功执行的 SQL。
     *
     * @return 不可变 SQL 列表
     */
    List<String> getExecutedSqlList();
}
