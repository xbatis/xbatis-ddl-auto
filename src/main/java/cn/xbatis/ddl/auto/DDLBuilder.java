package cn.xbatis.ddl.auto;

import cn.xbatis.core.db.reflect.TableInfo;
import db.sql.api.IDbType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 自动建表 SQL 构建器。
 * <p>
 * 该接口负责把一个 xbatis 实体转换为一组 DDL SQL。
 */
public interface DDLBuilder {

    /**
     * 为单个实体生成建表 SQL。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @return 建表 SQL
     */
    String createTableSql(IDbType dbType, Class<?> entityClass);

    /**
     * 为实体新增字段生成 ALTER TABLE ADD COLUMN SQL。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @param columnName  需要新增的列名
     * @return 新增字段 SQL
     */
    String addColumnSql(IDbType dbType, Class<?> entityClass, String columnName);

    /**
     * 为实体新增字段生成 ALTER TABLE ADD COLUMN SQL 列表。
     * <p>
     * 某些数据库的注释不能内联在 ADD COLUMN 中，例如 PostgreSQL 会额外生成 COMMENT ON COLUMN。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @param columnName  需要新增的列名
     * @return 新增字段 SQL 列表
     */
    List<String> addColumnSqlList(IDbType dbType, Class<?> entityClass, String columnName);

    /**
     * 为实体批量新增字段生成 ALTER TABLE ADD COLUMN SQL 列表。
     * <p>
     * 默认逐列生成，可能重复解析实体元数据；具体实现可以覆盖该方法以复用已解析的列元数据。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @param columns     需要新增的列元数据集合
     * @return 新增字段 SQL 列表
     */
    default List<String> addColumnSqlList(IDbType dbType, Class<?> entityClass, List<ColumnInfo> columns) {
        Objects.requireNonNull(columns, "columns");
        List<String> sqlList = new ArrayList<>();
        for (ColumnInfo column : columns) {
            Objects.requireNonNull(column, "column");
            sqlList.addAll(addColumnSqlList(dbType, entityClass, column.getName()));
        }
        return sqlList;
    }

    /**
     * 为实体批量新增字段生成 ALTER TABLE ADD COLUMN SQL 列表。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @param columns   需要新增的列元数据集合
     * @return 新增字段 SQL 列表
     */
    default List<String> addColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        return addColumnSqlList(dbType, tableInfo.getType(), columns);
    }

    /**
     * 为指定物理表批量新增字段生成 ALTER TABLE ADD COLUMN SQL 列表。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @param columns   需要新增的列元数据集合
     * @param tableName 物理表名
     * @return 新增字段 SQL 列表
     */
    default List<String> addColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        return addColumnSqlList(dbType, tableInfo, columns);
    }

    /**
     * 获取实体对应的数据库列元数据。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @return 数据库列元数据
     */
    List<ColumnInfo> getColumns(IDbType dbType, Class<?> entityClass);

    /**
     * 获取实体对应的数据库列元数据。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @return 数据库列元数据
     */
    default List<ColumnInfo> getColumns(IDbType dbType, TableInfo tableInfo) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        return getColumns(dbType, tableInfo.getType());
    }

    /**
     * 获取实体对应的数据库索引元数据。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @return 数据库索引元数据
     */
    default List<IndexInfo> getIndexes(IDbType dbType, Class<?> entityClass) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(entityClass, "entityClass");
        return Collections.emptyList();
    }

    /**
     * 获取实体对应的数据库索引元数据。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @return 数据库索引元数据
     */
    default List<IndexInfo> getIndexes(IDbType dbType, TableInfo tableInfo) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        return getIndexes(dbType, tableInfo.getType());
    }

    /**
     * 为实体索引生成 CREATE INDEX SQL 列表。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @param indexes   需要创建的索引元数据集合
     * @return 创建索引 SQL 列表
     */
    default List<String> createIndexSqlList(IDbType dbType, TableInfo tableInfo, List<IndexInfo> indexes) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(tableInfo, "tableInfo");
        Objects.requireNonNull(indexes, "indexes");
        return Collections.emptyList();
    }

    /**
     * 将实体索引元数据解析为指定物理表上的索引元数据。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @param indexes   实体索引元数据集合
     * @param tableName 物理表名
     * @return 物理表索引元数据
     */
    default List<IndexInfo> resolveIndexes(IDbType dbType, TableInfo tableInfo, List<IndexInfo> indexes, String tableName) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(tableInfo, "tableInfo");
        Objects.requireNonNull(indexes, "indexes");
        Objects.requireNonNull(tableName, "tableName");
        return indexes;
    }

    /**
     * 为指定物理表索引生成 CREATE INDEX SQL 列表。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @param indexes   需要创建的索引元数据集合
     * @param tableName 物理表名
     * @return 创建索引 SQL 列表
     */
    default List<String> createIndexSqlList(IDbType dbType, TableInfo tableInfo, List<IndexInfo> indexes, String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        return createIndexSqlList(dbType, tableInfo, resolveIndexes(dbType, tableInfo, indexes, tableName));
    }

    /**
     * 一次解析实体 DDL 元数据，供执行层复用。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @return 实体 DDL 元数据
     */
    default EntityDDLMetadata getEntityDDLMetadata(IDbType dbType, TableInfo tableInfo) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        return new EntityDDLMetadata(
                tableInfo,
                getColumns(dbType, tableInfo),
                getIndexes(dbType, tableInfo),
                getSequences(dbType, tableInfo)
        );
    }

    /**
     * 获取实体对应的数据库序列元数据。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @return 数据库序列元数据
     */
    default List<SequenceInfo> getSequences(IDbType dbType, Class<?> entityClass) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(entityClass, "entityClass");
        return Collections.emptyList();
    }

    /**
     * 获取实体对应的数据库序列元数据。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @return 数据库序列元数据
     */
    default List<SequenceInfo> getSequences(IDbType dbType, TableInfo tableInfo) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        return getSequences(dbType, tableInfo.getType());
    }

    /**
     * 为实体序列生成 CREATE SEQUENCE SQL 列表。
     *
     * @param dbType    数据库类型
     * @param sequences 需要创建的序列元数据集合
     * @return 创建序列 SQL 列表
     */
    default List<String> createSequenceSqlList(IDbType dbType, List<SequenceInfo> sequences) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(sequences, "sequences");
        return Collections.emptyList();
    }

    /**
     * 为单个实体生成建表 SQL 列表。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @return 建表 SQL 列表
     */
    List<String> createTableSqlList(IDbType dbType, Class<?> entityClass);

    /**
     * 为单个实体生成建表 SQL 列表。
     *
     * @param dbType   数据库类型
     * @param metadata 已解析的实体 DDL 元数据
     * @return 建表 SQL 列表
     */
    default List<String> createTableSqlList(IDbType dbType, EntityDDLMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return createTableSqlList(dbType, metadata.getTableInfo());
    }

    /**
     * 为指定物理表生成建表 SQL 列表，不包含实体级序列 SQL。
     *
     * @param dbType    数据库类型
     * @param metadata  已解析的实体 DDL 元数据
     * @param tableName 物理表名
     * @return 建表 SQL 列表
     */
    default List<String> createTableSqlList(IDbType dbType, EntityDDLMetadata metadata, String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        return createTableSqlList(dbType, metadata);
    }

    /**
     * 为单个实体生成建表 SQL 列表。
     *
     * @param dbType    数据库类型
     * @param tableInfo xbatis 表元数据
     * @return 建表 SQL 列表
     */
    default List<String> createTableSqlList(IDbType dbType, TableInfo tableInfo) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        return createTableSqlList(dbType, tableInfo.getType());
    }
}
