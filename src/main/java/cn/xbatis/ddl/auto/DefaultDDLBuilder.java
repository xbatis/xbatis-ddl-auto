package cn.xbatis.ddl.auto;

import cn.xbatis.core.db.reflect.TableFieldInfo;
import cn.xbatis.core.db.reflect.TableInfo;
import cn.xbatis.core.db.reflect.Tables;
import cn.xbatis.core.mybatis.typeHandler.EnumSupport;
import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.IndexDirection;
import cn.xbatis.db.annotations.ColumnDefinition;
import cn.xbatis.db.annotations.Index;
import cn.xbatis.db.annotations.IndexField;
import cn.xbatis.db.annotations.TableDefinition;
import db.sql.api.DbType;
import db.sql.api.IDbType;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 默认 xbatis 实体建表 SQL 构建器。
 * <p>
 * 该类只负责根据 xbatis 元数据生成 DDL，不负责连接数据库和判断表是否存在。
 */
public class DefaultDDLBuilder implements DDLBuilder {

    private static final Pattern POSTGRESQL_NEXTVAL_PATTERN = Pattern.compile(
            "\\bnextval\\s*\\(\\s*'([^']+)'(?:\\s*::\\s*regclass)?\\s*\\)",
            Pattern.CASE_INSENSITIVE
    );

    private static final String SQL_IDENTIFIER_PATTERN = "(?:\"[^\"]+\"|\\[[^\\]]+\\]|`[^`]+`|[A-Za-z_][A-Za-z0-9_$#]*)";

    private static final Pattern ORACLE_NEXTVAL_PATTERN = Pattern.compile(
            "\\b(" + SQL_IDENTIFIER_PATTERN + "(?:\\s*\\.\\s*" + SQL_IDENTIFIER_PATTERN + ")*)\\s*\\.\\s*NEXTVAL\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SQL_SERVER_NEXT_VALUE_PATTERN = Pattern.compile(
            "\\bNEXT\\s+VALUE\\s+FOR\\s+(" + SQL_IDENTIFIER_PATTERN + "(?:\\s*\\.\\s*" + SQL_IDENTIFIER_PATTERN + ")*)(?=\\s|;|,|\\)|$)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern QUALIFIED_NAME_DOT_SPACING_PATTERN = Pattern.compile("\\s*\\.\\s*");

    private final DDLDialect dialect;

    private final ColumnTypeMapper columnTypeMapper;

    private final ConcurrentMap<Class<?>, Optional<Class<?>>> enumSupportCodeTypeCache = new ConcurrentHashMap<>();

    private final ConcurrentMap<Class<?>, List<Index>> indexAnnotationsCache = new ConcurrentHashMap<>();

    public DefaultDDLBuilder() {
        this(new DDLDialect());
    }

    public DefaultDDLBuilder(DDLDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.columnTypeMapper = new ColumnTypeMapper(this.dialect);
    }

    /**
     * 创建单个实体的 DDL 构建上下文。
     */
    protected DDLContext createContext(IDbType dbType, Class<?> entityClass) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(entityClass, "entityClass");

        return createContext(dbType, Tables.get(entityClass));
    }

    /**
     * 创建单个实体的 DDL 构建上下文。
     */
    protected DDLContext createContext(IDbType dbType, TableInfo tableInfo) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(tableInfo, "tableInfo");
        return createContext(dbType, tableInfo, getColumns(dbType, tableInfo));
    }

    /**
     * 使用已解析列元数据创建 DDL 构建上下文。
     */
    protected DDLContext createContext(IDbType dbType, Class<?> entityClass, List<ColumnInfo> columns) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(entityClass, "entityClass");

        return createContext(dbType, Tables.get(entityClass), columns);
    }

    /**
     * 使用已解析列元数据创建 DDL 构建上下文。
     */
    protected DDLContext createContext(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns) {
        return createContext(dbType, tableInfo, columns, tableInfo.getTableName());
    }

    /**
     * 使用已解析列元数据和物理表名创建 DDL 构建上下文。
     */
    protected DDLContext createContext(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, String tableName) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(tableInfo, "tableInfo");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(tableName, "tableName");
        validateDbType(dbType);

        return new DDLContext(dbType, tableInfo.getType(), tableInfo, columns, tableName);
    }

    @Override
    public List<String> addColumnSqlList(IDbType dbType, Class<?> entityClass, List<ColumnInfo> columns) {
        return buildAddColumnSqlList(dbType, entityClass, columns);
    }

    @Override
    public List<String> addColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns) {
        return buildAddColumnSqlList(dbType, tableInfo, columns);
    }

    @Override
    public List<String> addColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, String tableName) {
        return buildAddColumnSqlList(dbType, tableInfo, columns, tableName);
    }

    @Override
    public String createTableSql(IDbType dbType, Class<?> entityClass) {
        return buildCreateTableSql(dbType, entityClass);
    }

    @Override
    public String addColumnSql(IDbType dbType, Class<?> entityClass, String columnName) {
        return buildAddColumnSql(dbType, entityClass, columnName);
    }

    @Override
    public List<String> addColumnSqlList(IDbType dbType, Class<?> entityClass, String columnName) {
        return buildAddColumnSqlList(dbType, entityClass, columnName);
    }

    /**
     * 根据 xbatis 实体生成 CREATE TABLE SQL。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类，必须标注 @Table
     * @return CREATE TABLE SQL
     */
    public String buildCreateTableSql(IDbType dbType, Class<?> entityClass) {
        TableInfo tableInfo = Tables.get(Objects.requireNonNull(entityClass, "entityClass"));
        if (!DDLTableNameResolverUtil.isTable(tableInfo)) {
            return "";
        }
        return buildCreateTableSql(createContext(dbType, tableInfo));
    }

    @Override
    public List<String> createTableSqlList(IDbType dbType, Class<?> entityClass) {
        return buildCreateTableSqlList(dbType, entityClass);
    }

    @Override
    public List<String> createTableSqlList(IDbType dbType, TableInfo tableInfo) {
        return buildCreateTableSqlList(dbType, tableInfo);
    }

    @Override
    public EntityDDLMetadata getEntityDDLMetadata(IDbType dbType, TableInfo tableInfo) {
        if (!DDLTableNameResolverUtil.isTable(tableInfo)) {
            return new EntityDDLMetadata(tableInfo, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        DDLContext context = createContext(dbType, tableInfo);
        List<SequenceInfo> sequences = getClass() == DefaultDDLBuilder.class ? getSequences(context) : getSequences(dbType, tableInfo);
        return new EntityDDLMetadata(tableInfo, context.columns, getIndexes(dbType, tableInfo), sequences);
    }

    @Override
    public List<String> createTableSqlList(IDbType dbType, EntityDDLMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return buildCreateTableSqlList(dbType, metadata);
    }

    @Override
    public List<String> createTableSqlList(IDbType dbType, EntityDDLMetadata metadata, String tableName) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(tableName, "tableName");
        return buildCreateTableSqlList(dbType, metadata, tableName);
    }

    /**
     * 根据已解析的实体元数据生成 CREATE TABLE SQL。
     */
    protected String buildCreateTableSql(IDbType dbType, Class<?> entityClass, TableInfo tableInfo, List<ColumnInfo> columns) {
        return buildCreateTableSql(new DDLContext(dbType, entityClass, tableInfo, columns));
    }

    /**
     * 根据已解析的实体元数据和物理表名生成 CREATE TABLE SQL。
     */
    protected String buildCreateTableSql(IDbType dbType, Class<?> entityClass, TableInfo tableInfo, List<ColumnInfo> columns, String tableName) {
        return buildCreateTableSql(new DDLContext(dbType, entityClass, tableInfo, columns, tableName));
    }

    /**
     * 根据已解析的实体上下文生成 CREATE TABLE SQL。
     */
    protected String buildCreateTableSql(DDLContext context) {
        IDbType dbType = context.dbType;
        List<ColumnInfo> columns = context.columns;
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Entity " + context.entityClass.getName() + " has no table columns");
        }

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ");
        // 不支持 IF NOT EXISTS 的数据库由执行层先查表是否存在。
        if (supportsCreateTableIfNotExists(dbType)) {
            ddl.append("IF NOT EXISTS ");
        }

        appendTableName(ddl, context);
        ddl.append(" (\n");

        List<String> ddlItems = new ArrayList<>(columns.size() + 1);
        for (ColumnInfo column : columns) {
            ddlItems.add(buildColumnSql(context, column, true, true));
        }
        appendPrimaryKeySql(context, ddlItems);

        ddl.append(String.join(",\n", ddlItems));
        ddl.append("\n");
        ddl.append(")");
        appendTableDefinitionSql(ddl, context);
        ddl.append(";");
        return ddl.toString();
    }

    /**
     * 根据 xbatis 实体生成建表 SQL 列表。
     * <p>
     * 部分数据库的字段注释不能写在 CREATE TABLE 的列定义中，需要额外生成注释语句。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @return CREATE TABLE 及附属 DDL
     */
    public List<String> buildCreateTableSqlList(IDbType dbType, Class<?> entityClass) {
        return buildCreateTableSqlList(dbType, Tables.get(entityClass));
    }

    /**
     * 根据 xbatis 表元数据生成建表 SQL 列表。
     */
    public List<String> buildCreateTableSqlList(IDbType dbType, TableInfo tableInfo) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        if (!DDLTableNameResolverUtil.isTable(tableInfo)) {
            return Collections.emptyList();
        }
        return buildCreateTableSqlList(dbType, getEntityDDLMetadata(dbType, tableInfo));
    }

    /**
     * 根据已解析的实体 DDL 元数据生成建表 SQL 列表。
     */
    protected List<String> buildCreateTableSqlList(IDbType dbType, EntityDDLMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        List<String> tableNames = DDLTableNameResolverUtil.resolve(metadata.getTableInfo());
        if (tableNames.isEmpty()) {
            return Collections.emptyList();
        }
        validateExplicitIndexNames(metadata.getTableInfo(), metadata.getIndexes(), tableNames.size());

        List<String> sequenceSqlList = createSequenceSqlList(dbType, metadata.getSequences());
        List<String> sqlList = new ArrayList<>(sequenceSqlList.size() + tableNames.size());
        sqlList.addAll(sequenceSqlList);
        for (String tableName : tableNames) {
            sqlList.addAll(buildCreateTableSqlList(dbType, metadata, tableName));
        }
        return sqlList;
    }

    /**
     * 根据已解析的实体 DDL 元数据为指定物理表生成建表 SQL 列表，不包含序列 SQL。
     */
    protected List<String> buildCreateTableSqlList(IDbType dbType, EntityDDLMetadata metadata, String tableName) {
        DDLContext context = createContext(dbType, metadata.getTableInfo(), metadata.getColumns(), tableName);

        String tableCommentSql = buildTableCommentSql(context);
        List<String> commentSqlList = buildColumnCommentSqlList(context);
        List<String> indexSqlList = createIndexSqlList(dbType, metadata.getTableInfo(), metadata.getIndexes(), tableName);

        List<String> sqlList = new ArrayList<>(1 + (isBlank(tableCommentSql) ? 0 : 1)
                + commentSqlList.size() + indexSqlList.size());
        sqlList.add(buildCreateTableSql(context));
        if (!isBlank(tableCommentSql)) {
            sqlList.add(tableCommentSql);
        }
        sqlList.addAll(commentSqlList);
        sqlList.addAll(indexSqlList);
        return sqlList;
    }

    /**
     * 根据实体字段生成 ALTER TABLE ADD COLUMN SQL。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @param columnName  需要新增的列名
     * @return ALTER TABLE ADD COLUMN SQL
     */
    public String buildAddColumnSql(IDbType dbType, Class<?> entityClass, String columnName) {
        TableInfo tableInfo = Tables.get(Objects.requireNonNull(entityClass, "entityClass"));
        if (!DDLTableNameResolverUtil.isTable(tableInfo)) {
            return "";
        }
        DDLContext context = createContext(dbType, tableInfo);
        return buildAddColumnSql(context, context.column(columnName));
    }

    /**
     * 根据已解析的实体元数据生成 ALTER TABLE ADD COLUMN SQL。
     */
    protected String buildAddColumnSql(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, ColumnInfo column) {
        return buildAddColumnSql(new DDLContext(dbType, null, tableInfo, columns), column);
    }

    /**
     * 根据已解析的实体元数据和物理表名生成 ALTER TABLE ADD COLUMN SQL。
     */
    protected String buildAddColumnSql(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, ColumnInfo column, String tableName) {
        return buildAddColumnSql(new DDLContext(dbType, tableInfo.getType(), tableInfo, columns, tableName), column);
    }

    /**
     * 根据已解析的实体上下文生成 ALTER TABLE ADD COLUMN SQL。
     */
    protected String buildAddColumnSql(DDLContext context, ColumnInfo column) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("ALTER TABLE ");
        appendTableName(ddl, context);
        ddl.append(" ADD ");
        if (supportsAddColumnKeyword(context.dbType)) {
            ddl.append("COLUMN ");
        }
        ddl.append(buildColumnSql(context, column, false, supportsInlineUniqueInAddColumn(context.dbType)).trim());
        ddl.append(";");
        return ddl.toString();
    }

    /**
     * 根据实体字段生成新增列 SQL 列表。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @param columnName  需要新增的列名
     * @return ALTER TABLE ADD COLUMN 及附属 DDL
     */
    public List<String> buildAddColumnSqlList(IDbType dbType, Class<?> entityClass, String columnName) {
        TableInfo tableInfo = Tables.get(Objects.requireNonNull(entityClass, "entityClass"));
        if (!DDLTableNameResolverUtil.isTable(tableInfo)) {
            return Collections.emptyList();
        }
        DDLContext context = createContext(dbType, tableInfo);
        ColumnInfo column = context.column(columnName);

        List<String> sqlList = new ArrayList<>();
        appendAddColumnSqlList(context, column, sqlList);
        return sqlList;
    }

    /**
     * 根据实体字段批量生成新增列 SQL 列表。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @param addColumns  需要新增的列元数据集合
     * @return ALTER TABLE ADD COLUMN 及附属 DDL
     */
    public List<String> buildAddColumnSqlList(IDbType dbType, Class<?> entityClass, List<ColumnInfo> addColumns) {
        Objects.requireNonNull(entityClass, "entityClass");
        return buildAddColumnSqlList(dbType, Tables.get(entityClass), addColumns);
    }

    /**
     * 根据实体字段批量生成新增列 SQL 列表。
     *
     * @param dbType     数据库类型
     * @param tableInfo  xbatis 表元数据
     * @param addColumns 需要新增的列元数据集合
     * @return ALTER TABLE ADD COLUMN 及附属 DDL
     */
    public List<String> buildAddColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> addColumns) {
        return buildAddColumnSqlList(dbType, tableInfo, addColumns, tableInfo.getTableName());
    }

    /**
     * 根据实体字段批量生成指定物理表的新增列 SQL 列表。
     *
     * @param dbType     数据库类型
     * @param tableInfo  xbatis 表元数据
     * @param addColumns 需要新增的列元数据集合
     * @param tableName  物理表名
     * @return ALTER TABLE ADD COLUMN 及附属 DDL
     */
    public List<String> buildAddColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> addColumns, String tableName) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(tableInfo, "tableInfo");
        Objects.requireNonNull(addColumns, "addColumns");
        Objects.requireNonNull(tableName, "tableName");

        if (!DDLTableNameResolverUtil.isTable(tableInfo) || addColumns.isEmpty()) {
            return Collections.emptyList();
        }

        DDLContext context = createContext(dbType, tableInfo, addColumns, tableName);
        List<String> sqlList = new ArrayList<>(addColumns.size());
        for (ColumnInfo column : context.columns) {
            appendAddColumnSqlList(context, column, sqlList);
        }
        return sqlList;
    }

    /**
     * 追加单个新增列 SQL 及附属 DDL。
     */
    protected void appendAddColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, ColumnInfo column, List<String> sqlList) {
        appendAddColumnSqlList(new DDLContext(dbType, null, tableInfo, columns), column, sqlList);
    }

    /**
     * 追加指定物理表的单个新增列 SQL 及附属 DDL。
     */
    protected void appendAddColumnSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns, ColumnInfo column, String tableName, List<String> sqlList) {
        appendAddColumnSqlList(new DDLContext(dbType, tableInfo.getType(), tableInfo, columns, tableName), column, sqlList);
    }

    /**
     * 追加单个新增列 SQL 及附属 DDL。
     */
    protected void appendAddColumnSqlList(DDLContext context, ColumnInfo column, List<String> sqlList) {
        sqlList.add(buildAddColumnSql(context, column));
        String commentSql = buildColumnCommentSql(context, column);
        if (!isBlank(commentSql)) {
            sqlList.add(commentSql);
        }
        String uniqueSql = buildAddUniqueSql(context, column);
        if (!isBlank(uniqueSql)) {
            sqlList.add(uniqueSql);
        }
    }

    /**
     * 追加 schema 和表名。
     */
    protected void appendTableName(StringBuilder ddl, DDLContext context) {
        appendTableName(ddl, context.dbType, context.tableInfo, context.tableName);
    }

    /**
     * 获取实体中参与建表的字段列。
     */
    @Override
    public List<ColumnInfo> getColumns(IDbType dbType, Class<?> entityClass) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(entityClass, "entityClass");
        return getColumns(dbType, Tables.get(entityClass));
    }

    /**
     * 获取实体中参与建表的字段列。
     */
    @Override
    public List<ColumnInfo> getColumns(IDbType dbType, TableInfo tableInfo) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (TableFieldInfo tableFieldInfo : tableInfo.getTableFieldInfos()) {
            if (tableFieldInfo.isExists()) {
                columns.add(new ColumnInfo(tableFieldInfo, dbType));
            }
        }
        columns.sort(Comparator.comparingInt(column -> column.getDefinition().index()));
        return columns;
    }

    /**
     * 获取实体中参与建表的索引。
     */
    @Override
    public List<IndexInfo> getIndexes(IDbType dbType, Class<?> entityClass) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(entityClass, "entityClass");
        return getIndexes(dbType, Tables.get(entityClass));
    }

    /**
     * 获取实体中参与建表的索引。
     */
    @Override
    public List<IndexInfo> getIndexes(IDbType dbType, TableInfo tableInfo) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(tableInfo, "tableInfo");
        validateDbType(dbType);

        List<Index> indexAnnotations = getIndexAnnotations(tableInfo.getType());
        if (indexAnnotations.isEmpty()) {
            return Collections.emptyList();
        }
        List<IndexInfo> indexes = new ArrayList<>(indexAnnotations.size());
        for (Index indexAnnotation : indexAnnotations) {
            indexes.add(toIndexInfo(tableInfo, indexAnnotation));
        }
        return indexes;
    }

    /**
     * 获取实体中通过 @TableId(SQL) 声明的数据库序列。
     */
    @Override
    public List<SequenceInfo> getSequences(IDbType dbType, Class<?> entityClass) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(entityClass, "entityClass");
        return getSequences(dbType, Tables.get(entityClass));
    }

    /**
     * 获取实体中通过 @TableId(SQL) 声明的数据库序列。
     */
    @Override
    public List<SequenceInfo> getSequences(IDbType dbType, TableInfo tableInfo) {
        return getSequences(createContext(dbType, tableInfo));
    }

    /**
     * 获取实体中通过 @TableId(SQL) 声明的数据库序列。
     */
    protected List<SequenceInfo> getSequences(DDLContext context) {
        List<SequenceInfo> sequences = new ArrayList<>();
        Set<String> sequenceKeys = new LinkedHashSet<>();
        for (ColumnInfo column : context.columns) {
            SequenceInfo sequence = getSequence(context.dbType, column);
            if (sequence != null && sequenceKeys.add(sequenceKey(sequence))) {
                sequences.add(sequence);
            }
        }
        return sequences;
    }

    /**
     * 为实体序列生成 CREATE SEQUENCE SQL 列表。
     */
    @Override
    public List<String> createSequenceSqlList(IDbType dbType, List<SequenceInfo> sequences) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(sequences, "sequences");
        validateDbType(dbType);
        if (sequences.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sqlList = new ArrayList<>(sequences.size());
        for (SequenceInfo sequence : sequences) {
            sqlList.add(buildCreateSequenceSql(dbType, sequence));
        }
        return sqlList;
    }

    /**
     * 生成 CREATE SEQUENCE SQL。
     */
    protected String buildCreateSequenceSql(IDbType dbType, SequenceInfo sequence) {
        Objects.requireNonNull(sequence, "sequence");
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE SEQUENCE ");
        if (supportsCreateSequenceIfNotExists(dbType)) {
            ddl.append("IF NOT EXISTS ");
        }
        appendSequenceName(ddl, sequence);
        if (!isPostgresql(dbType) && !isOracle(dbType) && !isSqlServer(dbType) && dbType != DbType.DB2) {
            ddl.append("\n    START WITH 1\n    INCREMENT BY 1");
        }
        ddl.append(";");
        return ddl.toString();
    }

    /**
     * 为实体索引生成 CREATE INDEX SQL 列表。
     */
    @Override
    public List<String> createIndexSqlList(IDbType dbType, TableInfo tableInfo, List<IndexInfo> indexes) {
        return createIndexSqlList(dbType, tableInfo, indexes, tableInfo.getTableName());
    }

    @Override
    public List<IndexInfo> resolveIndexes(IDbType dbType, TableInfo tableInfo, List<IndexInfo> indexes, String tableName) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(tableInfo, "tableInfo");
        Objects.requireNonNull(indexes, "indexes");
        Objects.requireNonNull(tableName, "tableName");
        if (indexes.isEmpty()) {
            return Collections.emptyList();
        }
        List<IndexInfo> resolvedIndexes = new ArrayList<>(indexes.size());
        for (IndexInfo index : indexes) {
            if (index.isExplicitName()) {
                resolvedIndexes.add(index);
            } else {
                resolvedIndexes.add(new IndexInfo(
                        buildIndexName(tableName, index.getFields(), index.isUnique()),
                        index.isUnique(),
                        index.getFields(),
                        false
                ));
            }
        }
        return resolvedIndexes;
    }

    /**
     * 为指定物理表索引生成 CREATE INDEX SQL 列表。
     */
    @Override
    public List<String> createIndexSqlList(IDbType dbType, TableInfo tableInfo, List<IndexInfo> indexes, String tableName) {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(tableInfo, "tableInfo");
        Objects.requireNonNull(indexes, "indexes");
        Objects.requireNonNull(tableName, "tableName");
        validateDbType(dbType);
        if (indexes.isEmpty()) {
            return Collections.emptyList();
        }
        if (!supportsCreateIndex(dbType)) {
            throw new UnsupportedOperationException(dbType.getName() + " does not support CREATE INDEX");
        }
        DDLContext context = createContext(dbType, tableInfo, Collections.<ColumnInfo>emptyList(), tableName);
        List<IndexInfo> resolvedIndexes = resolveIndexes(dbType, tableInfo, indexes, tableName);
        List<String> sqlList = new ArrayList<>(indexes.size());
        for (IndexInfo index : resolvedIndexes) {
            sqlList.add(buildCreateIndexSql(context, index));
        }
        return sqlList;
    }

    /**
     * 从实体列元数据中查找指定列。
     */
    protected ColumnInfo findColumn(Class<?> entityClass, List<ColumnInfo> columns, String columnName) {
        for (ColumnInfo column : columns) {
            if (column.getName().equals(columnName)) {
                return column;
            }
        }
        throw new IllegalArgumentException("Entity " + entityClass.getName() + " has no column " + columnName);
    }

    /**
     * 构建列名索引，供批量新增字段时复用。
     */
    protected Map<String, ColumnInfo> buildColumnMap(List<ColumnInfo> columns) {
        Map<String, ColumnInfo> columnMap = new LinkedHashMap<>(columns.size() * 2);
        for (ColumnInfo column : columns) {
            columnMap.put(column.getName(), column);
        }
        return columnMap;
    }

    /**
     * 将类级 @Index 注解转换为索引元数据。
     */
    protected IndexInfo toIndexInfo(TableInfo tableInfo, Index indexAnnotation) {
        IndexField[] indexFields = indexAnnotation.fields();
        if (indexFields.length == 0) {
            throw new IllegalArgumentException("Index on entity " + tableInfo.getType().getName() + " has no fields");
        }
        List<IndexInfo.Field> fields = new ArrayList<>(indexFields.length);
        for (IndexField indexField : indexFields) {
            fields.add(toIndexField(tableInfo, indexField));
        }
        String indexName = indexAnnotation.name();
        boolean explicitName = !isBlank(indexName);
        if (isBlank(indexName)) {
            indexName = buildIndexName(tableInfo, fields, indexAnnotation.unique());
        }
        return new IndexInfo(indexName, indexAnnotation.unique(), fields, explicitName);
    }

    /**
     * 多物理表时不允许使用显式索引名，避免不同物理表上的同名索引冲突。
     */
    protected void validateExplicitIndexNames(TableInfo tableInfo, List<IndexInfo> indexes, int physicalTableCount) {
        if (physicalTableCount <= 1 || indexes.isEmpty()) {
            return;
        }
        for (IndexInfo index : indexes) {
            if (index.isExplicitName()) {
                throw new IllegalArgumentException("Entity " + tableInfo.getType().getName()
                        + " resolves to multiple physical tables, explicit @Index name is not allowed: "
                        + index.getName());
            }
        }
    }

    /**
     * 读取类级 @Index 和 @Indexs。
     */
    protected List<Index> getIndexAnnotations(Class<?> entityClass) {
        return indexAnnotationsCache.computeIfAbsent(entityClass, this::readIndexAnnotations);
    }

    private List<Index> readIndexAnnotations(Class<?> entityClass) {
        Index[] indexes = entityClass.getAnnotationsByType(Index.class);
        return indexes.length == 0 ? Collections.<Index>emptyList() : Collections.unmodifiableList(Arrays.asList(indexes));
    }

    /**
     * 将 @IndexField 的实体字段名解析为真实列名。
     */
    protected IndexInfo.Field toIndexField(TableInfo tableInfo, IndexField indexField) {
        String fieldName = indexField.name();
        if (isBlank(fieldName)) {
            throw new IllegalArgumentException("Index on entity " + tableInfo.getType().getName() + " has blank field name");
        }
        TableFieldInfo tableFieldInfo = tableInfo.getFieldInfo(fieldName);
        if (tableFieldInfo == null) {
            tableFieldInfo = tableInfo.getFieldInfoByColumnName(fieldName);
        }
        if (tableFieldInfo == null || !tableFieldInfo.isExists()) {
            throw new IllegalArgumentException("Entity " + tableInfo.getType().getName() + " has no index field " + fieldName);
        }
        return new IndexInfo.Field(tableFieldInfo.getColumnName(), indexField.direction());
    }

    /**
     * 生成 CREATE INDEX SQL。
     */
    protected String buildCreateIndexSql(DDLContext context, IndexInfo index) {
        if (index.getFields().isEmpty()) {
            throw new IllegalArgumentException("Index " + index.getName() + " has no fields");
        }
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE ");
        if (index.isUnique()) {
            ddl.append("UNIQUE ");
        }
        ddl.append("INDEX ");
        ddl.append(context.dbType.wrap(index.getName()));
        ddl.append(" ON ");
        appendTableName(ddl, context);
        ddl.append(" (");
        for (int i = 0; i < index.getFields().size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            appendIndexField(ddl, context.dbType, index.getFields().get(i));
        }
        ddl.append(");");
        return ddl.toString();
    }

    /**
     * 从字段注解中提取序列元数据。
     */
    protected SequenceInfo getSequence(IDbType dbType, ColumnInfo column) {
        if (!isSqlSequenceId(column)) {
            return null;
        }
        String sequenceName = getSequenceName(dbType, column.getTableId().sql());
        if (isBlank(sequenceName)) {
            return null;
        }
        return toSequenceInfo(sequenceName);
    }

    /**
     * 判断主键是否通过数据库 SQL 获取序列值。
     */
    protected boolean isSqlSequenceId(ColumnInfo column) {
        return column.isId()
                && column.getTableId() != null
                && column.getIdAutoType() == IdAutoType.SQL
                && !isBlank(column.getTableId().sql());
    }

    /**
     * 从 @TableId(SQL) 的 SQL 中提取序列名。
     */
    protected String getSequenceName(IDbType dbType, String sql) {
        if (isBlank(sql)) {
            return null;
        }
        if (isPostgresql(dbType)) {
            return getPostgresqlSequenceName(sql);
        }
        if (isOracle(dbType)) {
            return getOracleSequenceName(sql);
        }
        if (isSqlServer(dbType) || dbType == DbType.DB2) {
            return getNextValueForSequenceName(sql);
        }
        return getDefaultSequenceName(sql);
    }

    /**
     * 解析 PostgreSQL nextval('sequence') 形式。
     */
    protected String getPostgresqlSequenceName(String sql) {
        java.util.regex.Matcher matcher = POSTGRESQL_NEXTVAL_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return normalizeQualifiedName(matcher.group(1));
    }

    /**
     * 解析 Oracle sequence.NEXTVAL 形式。
     */
    protected String getOracleSequenceName(String sql) {
        java.util.regex.Matcher matcher = ORACLE_NEXTVAL_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return normalizeQualifiedName(matcher.group(1));
    }

    /**
     * 解析 NEXT VALUE FOR sequence 形式，适用于 SQL Server、DB2 等数据库。
     */
    protected String getNextValueForSequenceName(String sql) {
        java.util.regex.Matcher matcher = SQL_SERVER_NEXT_VALUE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return normalizeQualifiedName(matcher.group(1));
    }

    /**
     * 其他数据库兜底解析常见序列取值 SQL。
     */
    protected String getDefaultSequenceName(String sql) {
        String sequenceName = getPostgresqlSequenceName(sql);
        if (!isBlank(sequenceName)) {
            return sequenceName;
        }
        sequenceName = getOracleSequenceName(sql);
        if (!isBlank(sequenceName)) {
            return sequenceName;
        }
        return getNextValueForSequenceName(sql);
    }

    /**
     * 将解析出的序列名转换为结构化元数据。
     */
    protected SequenceInfo toSequenceInfo(String sequenceName) {
        String normalizedName = normalizeQualifiedName(sequenceName);
        int schemaSeparatorIndex = lastUnquotedDotIndex(normalizedName);
        if (schemaSeparatorIndex < 0) {
            return new SequenceInfo(normalizedName);
        }
        return new SequenceInfo(
                normalizedName.substring(0, schemaSeparatorIndex),
                normalizedName.substring(schemaSeparatorIndex + 1)
        );
    }

    /**
     * 追加序列名。
     */
    protected void appendSequenceName(StringBuilder ddl, SequenceInfo sequence) {
        ddl.append(sequence.getQualifiedName());
    }

    private String sequenceKey(SequenceInfo sequence) {
        return normalizeQualifiedName(sequence.getQualifiedName()).toLowerCase(Locale.ROOT);
    }

    private String normalizeQualifiedName(String name) {
        return name == null ? null : QUALIFIED_NAME_DOT_SPACING_PATTERN.matcher(name.trim()).replaceAll(".");
    }

    private int lastUnquotedDotIndex(String value) {
        boolean quoted = false;
        int lastDotIndex = -1;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == '.' && !quoted) {
                lastDotIndex = i;
            }
        }
        return lastDotIndex;
    }

    /**
     * 追加索引字段和排序。
     */
    protected void appendIndexField(StringBuilder ddl, IDbType dbType, IndexInfo.Field field) {
        ddl.append(dbType.wrap(field.getColumnName()));
        if (field.getDirection() == IndexDirection.ASC) {
            ddl.append(" ASC");
        } else if (field.getDirection() == IndexDirection.DESC) {
            ddl.append(" DESC");
        }
    }

    /**
     * 从列名索引中查找指定列。
     */
    protected ColumnInfo findColumn(Class<?> entityClass, Map<String, ColumnInfo> columnMap, String columnName) {
        ColumnInfo column = columnMap.get(columnName);
        if (column == null) {
            throw new IllegalArgumentException("Entity " + entityClass.getName() + " has no column " + columnName);
        }
        return column;
    }

    /**
     * 追加 schema 和表名。
     */
    public void appendTableName(StringBuilder ddl, IDbType dbType, TableInfo tableInfo) {
        appendTableName(ddl, dbType, tableInfo, tableInfo.getTableName());
    }

    /**
     * 追加 schema 和指定物理表名。
     */
    public void appendTableName(StringBuilder ddl, IDbType dbType, TableInfo tableInfo, String tableName) {
        if (tableInfo.getSchema() != null && !tableInfo.getSchema().isEmpty()) {
            ddl.append(dbType.wrap(tableInfo.getSchema())).append(".");
        }
        ddl.append(dbType.wrap(tableName));
    }

    /**
     * 追加 schema、表名和列名。
     */
    public void appendColumnName(StringBuilder ddl, IDbType dbType, TableInfo tableInfo, ColumnInfo column) {
        appendTableName(ddl, dbType, tableInfo);
        ddl.append(".").append(dbType.wrap(column.getName()));
    }

    /**
     * 追加 schema、表名和列名。
     */
    protected void appendColumnName(StringBuilder ddl, DDLContext context, ColumnInfo column) {
        appendTableName(ddl, context);
        ddl.append(".").append(context.dbType.wrap(column.getName()));
    }

    /**
     * 生成单个字段的列定义 SQL。
     */
    protected String buildColumnSql(DDLContext context, ColumnInfo column, boolean includePrimaryKey, boolean includeUnique) {
        return buildColumnSql(context.dbType, column, context.columns, context.idColumnCount, includePrimaryKey, includeUnique);
    }

    /**
     * 生成单个字段的列定义 SQL。
     */
    protected String buildColumnSql(IDbType dbType, ColumnInfo column, List<ColumnInfo> columns) {
        return buildColumnSql(dbType, column, columns, true);
    }

    /**
     * 生成单个字段的列定义 SQL。
     */
    protected String buildColumnSql(IDbType dbType, ColumnInfo column, List<ColumnInfo> columns, boolean includePrimaryKey) {
        return buildColumnSql(dbType, column, columns, includePrimaryKey, true);
    }

    /**
     * 生成单个字段的列定义 SQL。
     */
    protected String buildColumnSql(IDbType dbType, ColumnInfo column, List<ColumnInfo> columns, boolean includePrimaryKey, boolean includeUnique) {
        return buildColumnSql(dbType, column, columns, getIdColumnCount(columns), includePrimaryKey, includeUnique);
    }

    /**
     * 多主键字段时生成表级联合主键。
     */
    protected void appendPrimaryKeySql(IDbType dbType, List<ColumnInfo> columns, List<String> ddlItems) {
        int idColumnCount = getIdColumnCount(columns);
        appendPrimaryKeySql(dbType, columns, idColumnCount, ddlItems);
    }

    /**
     * 生成单个字段的列定义 SQL。
     */
    protected String buildColumnSql(IDbType dbType, ColumnInfo column, List<ColumnInfo> columns, int idColumnCount, boolean includePrimaryKey, boolean includeUnique) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("  ").append(dbType.wrap(column.getName()));

        ColumnDefinition columnDefinition = column.getDefinition();
        if (columnDefinition.unique() && !supportsUniqueConstraint(dbType)) {
            throw new UnsupportedOperationException(dbType.getName() + " does not support UNIQUE constraint");
        }
        boolean autoIncrement = isAutoIncrement(column, idColumnCount);
        // SQLite 自增主键要求字段类型必须是 INTEGER PRIMARY KEY AUTOINCREMENT。
        if (autoIncrement && isSqlite(dbType)) {
            ddl.append(" ").append(getSqliteAutoIncrementType(column));
        } else if (!isBlank(columnDefinition.definition())) {
            ddl.append(" ").append(getColumnDefinitionType(columnDefinition));
        } else {
            ddl.append(" ").append(getColumnType(dbType, column, autoIncrement));
        }

        if (autoIncrement && includePrimaryKey) {
            ddl.append(getAutoIncrementSql(dbType));
        }

        if (includeUnique && columnDefinition.unique()) {
            ddl.append(" UNIQUE");
        }

        if ((!columnDefinition.nullable() || column.isId()) && !isInlineSqliteAutoIncrementPrimaryKey(dbType, autoIncrement)) {
            ddl.append(" NOT NULL");
        }

        if (!isBlank(columnDefinition.defaultValue())) {
            ddl.append(" DEFAULT ").append(columnDefinition.defaultValue());
        }

        if (includePrimaryKey && idColumnCount == 1 && column.isId() && !isInlineSqliteAutoIncrementPrimaryKey(dbType, autoIncrement)) {
            ddl.append(" PRIMARY KEY");
        }

        if (!isBlank(columnDefinition.comment()) && isMysql(dbType)) {
            ddl.append(" COMMENT '").append(escapeSqlString(columnDefinition.comment())).append("'");
        }

        return ddl.toString();
    }

    /**
     * 使用注解指定的列类型，并在未内联参数时补齐长度或精度。
     */
    protected String getColumnDefinitionType(ColumnDefinition columnDefinition) {
        String definition = columnDefinition.definition();
        if (definition.indexOf('(') >= 0) {
            return definition;
        }
        if (columnDefinition.precision() > 0) {
            if (columnDefinition.scale() > 0) {
                return definition + "(" + columnDefinition.precision() + "," + columnDefinition.scale() + ")";
            }
            return definition + "(" + columnDefinition.precision() + ")";
        }
        if (columnDefinition.length() > 0) {
            return definition + "(" + columnDefinition.length() + ")";
        }
        return definition;
    }

    /**
     * SQLite 自增主键只能使用 INTEGER 类型。
     */
    protected String getSqliteAutoIncrementType(ColumnInfo column) {
        String definition = column.getDefinition().definition();
        if (isBlank(definition)) {
            return "INTEGER";
        }
        if ("INTEGER".equalsIgnoreCase(definition.trim())) {
            return definition;
        }
        throw new IllegalArgumentException("SQLite auto increment column " + column.getName()
                + " must use INTEGER definition");
    }

    /**
     * 统计主键字段数量。
     */
    protected int getIdColumnCount(List<ColumnInfo> columns) {
        int count = 0;
        for (ColumnInfo column : columns) {
            if (column.isId()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 多主键字段时生成表级联合主键。
     */
    protected void appendPrimaryKeySql(DDLContext context, List<String> ddlItems) {
        appendPrimaryKeySql(context.dbType, context.columns, context.idColumnCount, ddlItems);
    }

    /**
     * 多主键字段时生成表级联合主键。
     */
    protected void appendPrimaryKeySql(IDbType dbType, List<ColumnInfo> columns, int idColumnCount, List<String> ddlItems) {
        if (idColumnCount <= 1) {
            return;
        }
        StringBuilder primaryKeyColumns = new StringBuilder();
        for (ColumnInfo column : columns) {
            if (column.isId()) {
                if (primaryKeyColumns.length() > 0) {
                    primaryKeyColumns.append(", ");
                }
                primaryKeyColumns.append(dbType.wrap(column.getName()));
            }
        }
        ddlItems.add("  PRIMARY KEY (" + primaryKeyColumns + ")");
    }

    /**
     * 生成 ADD COLUMN 后需要单独补充的唯一约束 SQL。
     */
    protected String buildAddUniqueSql(IDbType dbType, TableInfo tableInfo, ColumnInfo column) {
        return buildAddUniqueSql(new DDLContext(dbType, null, tableInfo, Collections.singletonList(column)), column);
    }

    /**
     * 生成 ADD COLUMN 后需要单独补充的唯一约束 SQL。
     */
    protected String buildAddUniqueSql(DDLContext context, ColumnInfo column) {
        if (!column.getDefinition().unique() || supportsInlineUniqueInAddColumn(context.dbType)) {
            return null;
        }
        if (isSqlite(context.dbType)) {
            StringBuilder ddl = new StringBuilder();
            ddl.append("CREATE UNIQUE INDEX ");
            ddl.append(context.dbType.wrap(buildUniqueName(context.tableName, column)));
            ddl.append(" ON ");
            appendTableName(ddl, context);
            ddl.append(" (").append(context.dbType.wrap(column.getName())).append(");");
            return ddl.toString();
        }
        return null;
    }

    /**
     * 追加 CREATE TABLE 后面的表级 DDL 片段。
     */
    protected void appendTableDefinitionSql(StringBuilder ddl, DDLContext context) {
        TableDefinition tableDefinition = getTableDefinition(context);
        if (tableDefinition == null) {
            return;
        }
        String definition = trimStatementTerminator(tableDefinition.definition());
        if (!isBlank(definition)) {
            ddl.append(" ").append(definition);
        }
        if (!isBlank(tableDefinition.comment()) && isMysql(context.dbType)) {
            ddl.append(" COMMENT='").append(escapeSqlString(tableDefinition.comment())).append("'");
        }
    }

    /**
     * 获取实体上的表定义注解。
     */
    protected TableDefinition getTableDefinition(DDLContext context) {
        TableDefinition[] definitions = context.tableInfo.getType().getAnnotationsByType(TableDefinition.class);
        TableDefinition definition = null;
        if (definitions.length == 1) {
            definition = definitions[0];
        } else if (definitions.length > 1) {
            TableDefinition unknown = null;
            for (TableDefinition df : definitions) {
                if (context.dbType.getName().equals(df.dbType())) {
                    definition = df;
                    break;
                }
                if (DbType.Name.UNKNOWN.equals(df.dbType())) {
                    unknown = df;
                }
            }
            if (definition == null) {
                definition = unknown;
            }
            if (definition == null) {
                definition = definitions[0];
            }
        }
        return definition;
    }

    /**
     * 生成表注释 SQL。
     */
    protected String buildTableCommentSql(IDbType dbType, Class<?> entityClass) {
        return buildTableCommentSql(createContext(dbType, entityClass));
    }

    /**
     * 生成表注释 SQL。
     */
    protected String buildTableCommentSql(IDbType dbType, TableInfo tableInfo) {
        return buildTableCommentSql(createContext(dbType, tableInfo, Collections.<ColumnInfo>emptyList()));
    }

    /**
     * 生成表注释 SQL。
     */
    protected String buildTableCommentSql(DDLContext context) {
        TableDefinition tableDefinition = getTableDefinition(context);
        if (tableDefinition == null || isBlank(tableDefinition.comment()) || !supportsTableCommentStatement(context.dbType)) {
            return null;
        }
        StringBuilder ddl = new StringBuilder();
        if (isPostgresql(context.dbType) || isOracle(context.dbType) || context.dbType == DbType.DB2 || context.dbType == DbType.H2) {
            ddl.append("COMMENT ON TABLE ");
            appendTableName(ddl, context);
            ddl.append(" IS '").append(escapeSqlString(tableDefinition.comment())).append("';");
            return ddl.toString();
        }
        if (isSqlServer(context.dbType)) {
            return buildSqlServerTableCommentSql(context.tableInfo, context.tableName, tableDefinition.comment());
        }
        return null;
    }

    /**
     * 生成字段注释 SQL。
     */
    protected List<String> buildColumnCommentSqlList(IDbType dbType, Class<?> entityClass) {
        return buildColumnCommentSqlList(createContext(dbType, entityClass));
    }

    /**
     * 生成字段注释 SQL。
     */
    protected List<String> buildColumnCommentSqlList(IDbType dbType, TableInfo tableInfo, List<ColumnInfo> columns) {
        return buildColumnCommentSqlList(new DDLContext(dbType, null, tableInfo, columns));
    }

    /**
     * 生成字段注释 SQL。
     */
    protected List<String> buildColumnCommentSqlList(DDLContext context) {
        if (!supportsColumnCommentStatement(context.dbType)) {
            return Collections.emptyList();
        }
        List<String> sqlList = new ArrayList<>();
        for (ColumnInfo column : context.columns) {
            String sql = buildColumnCommentSql(context, column);
            if (!isBlank(sql)) {
                sqlList.add(sql);
            }
        }
        return sqlList;
    }

    /**
     * 生成单个字段注释 SQL。
     */
    protected String buildColumnCommentSql(IDbType dbType, TableInfo tableInfo, ColumnInfo column) {
        return buildColumnCommentSql(new DDLContext(dbType, null, tableInfo, Collections.singletonList(column)), column);
    }

    /**
     * 生成单个字段注释 SQL。
     */
    protected String buildColumnCommentSql(DDLContext context, ColumnInfo column) {
        String comment = column.getDefinition().comment();
        if (isBlank(comment) || !supportsColumnCommentStatement(context.dbType)) {
            return null;
        }
        StringBuilder ddl = new StringBuilder();
        if (isPostgresql(context.dbType) || isOracle(context.dbType)) {
            ddl.append("COMMENT ON COLUMN ");
            appendColumnName(ddl, context, column);
            ddl.append(" IS '").append(escapeSqlString(comment)).append("';");
            return ddl.toString();
        }
        if (isSqlServer(context.dbType)) {
            return buildSqlServerColumnCommentSql(context.tableInfo, context.tableName, column, comment);
        }
        return null;
    }

    /**
     * 单个实体 DDL 构建上下文，集中保存已解析元数据和派生索引。
     */
    protected class DDLContext {

        private final IDbType dbType;

        private final Class<?> entityClass;

        private final TableInfo tableInfo;

        private final String tableName;

        private final List<ColumnInfo> columns;

        private final int idColumnCount;

        private Map<String, ColumnInfo> columnMap;

        DDLContext(IDbType dbType, Class<?> entityClass, TableInfo tableInfo, List<ColumnInfo> columns) {
            this(dbType, entityClass, tableInfo, columns, tableInfo.getTableName());
        }

        DDLContext(IDbType dbType, Class<?> entityClass, TableInfo tableInfo, List<ColumnInfo> columns, String tableName) {
            this.dbType = Objects.requireNonNull(dbType, "dbType");
            this.entityClass = entityClass;
            this.tableInfo = Objects.requireNonNull(tableInfo, "tableInfo");
            this.tableName = Objects.requireNonNull(tableName, "tableName");
            this.columns = Objects.requireNonNull(columns, "columns");
            for (ColumnInfo column : columns) {
                Objects.requireNonNull(column, "column");
            }
            this.idColumnCount = getIdColumnCount(columns);
        }

        ColumnInfo column(String columnName) {
            Objects.requireNonNull(columnName, "columnName");
            return findColumn(entityClass, columnMap(), columnName);
        }

        Map<String, ColumnInfo> columnMap() {
            if (columnMap == null) {
                columnMap = buildColumnMap(columns);
            }
            return columnMap;
        }
    }

    /**
     * 生成 SQL Server 字段注释扩展属性语句。
     */
    protected String buildSqlServerColumnCommentSql(TableInfo tableInfo, ColumnInfo column, String comment) {
        return buildSqlServerColumnCommentSql(tableInfo, tableInfo.getTableName(), column, comment);
    }

    /**
     * 生成 SQL Server 字段注释扩展属性语句。
     */
    protected String buildSqlServerColumnCommentSql(TableInfo tableInfo, String tableName, ColumnInfo column, String comment) {
        String schema = tableInfo.getSchema();
        StringBuilder ddl = new StringBuilder();
        if (isBlank(schema)) {
            ddl.append("DECLARE @schema sysname = SCHEMA_NAME(); ");
        }
        ddl.append("EXEC sys.sp_addextendedproperty ")
                .append("@name=N'MS_Description', ")
                .append("@value=N'").append(escapeSqlString(comment)).append("', ")
                .append("@level0type=N'SCHEMA', ");
        if (isBlank(schema)) {
            ddl.append("@level0name=@schema, ");
        } else {
            ddl.append("@level0name=N'").append(escapeSqlString(schema)).append("', ");
        }
        ddl.append("@level1type=N'TABLE', ")
                .append("@level1name=N'").append(escapeSqlString(tableName)).append("', ")
                .append("@level2type=N'COLUMN', ")
                .append("@level2name=N'").append(escapeSqlString(column.getName())).append("';");
        return ddl.toString();
    }

    /**
     * 生成 SQL Server 表注释扩展属性语句。
     */
    protected String buildSqlServerTableCommentSql(TableInfo tableInfo, String comment) {
        return buildSqlServerTableCommentSql(tableInfo, tableInfo.getTableName(), comment);
    }

    /**
     * 生成 SQL Server 表注释扩展属性语句。
     */
    protected String buildSqlServerTableCommentSql(TableInfo tableInfo, String tableName, String comment) {
        String schema = tableInfo.getSchema();
        StringBuilder ddl = new StringBuilder();
        if (isBlank(schema)) {
            ddl.append("DECLARE @schema sysname = SCHEMA_NAME(); ");
        }
        ddl.append("EXEC sys.sp_addextendedproperty ")
                .append("@name=N'MS_Description', ")
                .append("@value=N'").append(escapeSqlString(comment)).append("', ")
                .append("@level0type=N'SCHEMA', ");
        if (isBlank(schema)) {
            ddl.append("@level0name=@schema, ");
        } else {
            ddl.append("@level0name=N'").append(escapeSqlString(schema)).append("', ");
        }
        ddl.append("@level1type=N'TABLE', ")
                .append("@level1name=N'").append(escapeSqlString(tableName)).append("';");
        return ddl.toString();
    }

    /**
     * 生成不同数据库的自增片段。
     */
    protected String getAutoIncrementSql(IDbType dbType) {
        return dialect.getAutoIncrementSql(dbType);
    }

    /**
     * 将 Java 字段类型映射为数据库列类型。
     */
    protected String getColumnType(IDbType dbType, ColumnInfo column, boolean autoIncrement) {
        Class<?> type = column.getJavaType();
        ColumnDefinition definition = column.getDefinition();

        if (type.isEnum()) {
            Class<?> enumCodeType = getEnumSupportCodeType(type);
            if (enumCodeType != null) {
                return getColumnType(dbType, enumCodeType, definition, false);
            }
            return getStringType(dbType, getLength(definition, 64));
        }
        return getColumnType(dbType, type, definition, autoIncrement);
    }

    /**
     * 将普通 Java 类型映射为数据库列类型。
     */
    protected String getColumnType(IDbType dbType, Class<?> type, ColumnDefinition definition, boolean autoIncrement) {
        return columnTypeMapper.getColumnType(dbType, type, definition, autoIncrement);
    }

    /**
     * 获取 xbatis 持久化枚举的 code 类型。
     */
    protected Class<?> getEnumSupportCodeType(Class<?> enumType) {
        Optional<Class<?>> cachedCodeType = enumSupportCodeTypeCache.get(enumType);
        if (cachedCodeType != null) {
            return cachedCodeType.orElse(null);
        }
        Class<?> codeType = resolveEnumSupportCodeType(enumType);
        Optional<Class<?>> cachedValue = Optional.ofNullable(codeType);
        Optional<Class<?>> existingValue = enumSupportCodeTypeCache.putIfAbsent(enumType, cachedValue);
        return (existingValue == null ? cachedValue : existingValue).orElse(null);
    }

    /**
     * UNKNOWN 只能作为解析失败的占位类型，不能用于生成具体 DDL。
     */
    protected void validateDbType(IDbType dbType) {
        if (dbType == DbType.UNKNOWN) {
            throw new IllegalArgumentException("dbType must be a concrete database type");
        }
    }

    private Class<?> resolveEnumSupportCodeType(Class<?> enumType) {
        for (Type genericInterface : enumType.getGenericInterfaces()) {
            Class<?> codeType = getEnumSupportCodeType(genericInterface);
            if (codeType != null) {
                return codeType;
            }
        }
        return null;
    }

    private Class<?> getEnumSupportCodeType(Type type) {
        if (type instanceof Class) {
            Class<?> rawClass = (Class<?>) type;
            for (Type genericInterface : rawClass.getGenericInterfaces()) {
                Class<?> codeType = getEnumSupportCodeType(genericInterface);
                if (codeType != null) {
                    return codeType;
                }
            }
            return null;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type rawType = parameterizedType.getRawType();
            if (rawType instanceof Class && EnumSupport.class.isAssignableFrom((Class<?>) rawType)) {
                Type codeType = parameterizedType.getActualTypeArguments()[0];
                return codeType instanceof Class ? (Class<?>) codeType : null;
            }
        }
        return null;
    }

    /**
     * 生成字符串列类型，按数据库方言处理大字段。
     */
    protected String getStringType(IDbType dbType, int length) {
        return columnTypeMapper.getStringType(dbType, length);
    }

    /**
     * 生成整数列类型。
     */
    protected String getIntegerType(IDbType dbType) {
        return columnTypeMapper.getIntegerType(dbType);
    }

    /**
     * 生成长整数列类型。
     */
    protected String getBigIntType(IDbType dbType) {
        return columnTypeMapper.getBigIntType(dbType);
    }

    /**
     * 生成短整数列类型。
     */
    protected String getSmallIntType(IDbType dbType) {
        return columnTypeMapper.getSmallIntType(dbType);
    }

    /**
     * 生成字节列类型。
     */
    protected String getByteType(IDbType dbType) {
        return columnTypeMapper.getByteType(dbType);
    }

    /**
     * 生成布尔列类型。
     */
    protected String getBooleanType(IDbType dbType) {
        return columnTypeMapper.getBooleanType(dbType);
    }

    /**
     * 生成定点数列类型。
     */
    protected String getDecimalType(IDbType dbType, int precision, int scale) {
        return columnTypeMapper.getDecimalType(dbType, precision, scale);
    }

    /**
     * 生成二进制大字段列类型。
     */
    protected String getBlobType(IDbType dbType) {
        return columnTypeMapper.getBlobType(dbType);
    }

    /**
     * 生成时间列类型。
     */
    protected String getTimeType(IDbType dbType) {
        return columnTypeMapper.getTimeType(dbType);
    }

    /**
     * 生成日期时间列类型。
     */
    protected String getDateTimeType(IDbType dbType) {
        return columnTypeMapper.getDateTimeType(dbType);
    }

    /**
     * 获取注解配置的长度；未配置时返回默认长度。
     */
    protected int getLength(ColumnDefinition columnDefinition, int defaultLength) {
        return columnTypeMapper.getLength(columnDefinition, defaultLength);
    }

    /**
     * 判断字段是否是数据库自增主键。
     */
    protected boolean isAutoIncrement(ColumnInfo column) {
        return column.isId() && column.getIdAutoType() == IdAutoType.AUTO;
    }

    /**
     * 联合主键不能为每个主键字段都生成自增片段。
     */
    protected boolean isAutoIncrement(ColumnInfo column, int idColumnCount) {
        return idColumnCount == 1 && isAutoIncrement(column);
    }

    /**
     * SQLite 自增主键已经内联 PRIMARY KEY，不再追加 NOT NULL 或额外主键片段。
     */
    protected boolean isInlineSqliteAutoIncrementPrimaryKey(IDbType dbType, boolean autoIncrement) {
        return autoIncrement && isSqlite(dbType);
    }

    protected boolean isMysql(IDbType dbType) {
        return dialect.isMysql(dbType);
    }

    protected boolean isPostgresql(IDbType dbType) {
        return dialect.isPostgresql(dbType);
    }

    protected boolean isOracle(IDbType dbType) {
        return dialect.isOracle(dbType);
    }

    protected boolean isSqlServer(IDbType dbType) {
        return dialect.isSqlServer(dbType);
    }

    protected boolean isSqlite(IDbType dbType) {
        return dialect.isSqlite(dbType);
    }

    /**
     * 判断数据库是否支持 CREATE TABLE IF NOT EXISTS 语法。
     */
    protected boolean supportsCreateTableIfNotExists(IDbType dbType) {
        return dialect.supportsCreateTableIfNotExists(dbType);
    }

    /**
     * 判断数据库是否支持 CREATE SEQUENCE IF NOT EXISTS 语法。
     */
    protected boolean supportsCreateSequenceIfNotExists(IDbType dbType) {
        return dialect.supportsCreateSequenceIfNotExists(dbType);
    }

    /**
     * 判断 ADD COLUMN 语法中是否需要 COLUMN 关键字。
     */
    protected boolean supportsAddColumnKeyword(IDbType dbType) {
        return dialect.supportsAddColumnKeyword(dbType);
    }

    /**
     * 判断数据库是否支持传统唯一约束。
     */
    protected boolean supportsUniqueConstraint(IDbType dbType) {
        return dialect.supportsUniqueConstraint(dbType);
    }

    /**
     * 判断 ADD COLUMN 语句是否支持内联 UNIQUE。
     */
    protected boolean supportsInlineUniqueInAddColumn(IDbType dbType) {
        return dialect.supportsInlineUniqueInAddColumn(dbType);
    }

    /**
     * 判断数据库是否需要使用独立语句创建字段注释。
     */
    protected boolean supportsColumnCommentStatement(IDbType dbType) {
        return dialect.supportsColumnCommentStatement(dbType);
    }

    /**
     * 判断数据库是否需要使用独立语句创建表注释。
     */
    protected boolean supportsTableCommentStatement(IDbType dbType) {
        return dialect.supportsTableCommentStatement(dbType);
    }

    /**
     * 判断数据库是否支持传统 CREATE INDEX 语法。
     */
    protected boolean supportsCreateIndex(IDbType dbType) {
        return dialect.supportsCreateIndex(dbType);
    }

    /**
     * 生成稳定的唯一索引名，避免表名和字段名过长导致部分数据库超过标识符长度。
     */
    protected String buildUniqueName(TableInfo tableInfo, ColumnInfo column) {
        return buildUniqueName(tableInfo.getTableName(), column);
    }

    /**
     * 生成指定物理表上的稳定唯一索引名。
     */
    protected String buildUniqueName(String tableName, ColumnInfo column) {
        return DDLIndexNameGenerator.uniqueName(tableName, column.getName());
    }

    /**
     * 生成稳定的索引名，避免表名和字段名过长导致部分数据库超过标识符长度。
     */
    protected String buildIndexName(TableInfo tableInfo, List<IndexInfo.Field> fields, boolean unique) {
        return buildIndexName(tableInfo.getTableName(), fields, unique);
    }

    /**
     * 生成指定物理表上的稳定索引名，避免表名和字段名过长导致部分数据库超过标识符长度。
     */
    protected String buildIndexName(String tableName, List<IndexInfo.Field> fields, boolean unique) {
        return DDLIndexNameGenerator.indexName(tableName, fields, unique);
    }

    /**
     * 判断字符串是否为空白。
     */
    protected boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 去掉表级 DDL 片段结尾的语句分号。
     */
    protected String trimStatementTerminator(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    /**
     * 转义 SQL 字符串字面量中的单引号。
     */
    protected String escapeSqlString(String value) {
        return value.replace("'", "''");
    }
}
