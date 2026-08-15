package cn.xbatis.ddl.auto;

import cn.xbatis.core.db.reflect.TableInfo;
import cn.xbatis.core.db.reflect.Tables;
import cn.xbatis.core.mybatis.typeHandler.EnumSupport;
import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.annotations.ColumnDefinition;
import db.sql.api.DbType;
import db.sql.api.IDbType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认自动建表执行器。
 * <p>
 * 执行前会先通过 JDBC DatabaseMetaData 判断表是否已经存在，避免重复执行建表 SQL。
 */
public class DefaultDDLAutoExecutor implements DDLAutoExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDDLAutoExecutor.class);

    private static final String TABLE_TYPE = "TABLE";

    private static final String VIEW_TYPE = "VIEW";

    private static final String SEQUENCE_TYPE = "SEQUENCE";

    private static final String[] TABLE_TYPES = new String[]{TABLE_TYPE};

    private static final String[] TABLE_AND_VIEW_TYPES = new String[]{TABLE_TYPE, VIEW_TYPE};

    private static final String[] SEQUENCE_TYPES = new String[]{SEQUENCE_TYPE};

    private static final int OBJECT_NOT_EXISTS = -1;

    private static final int OBJECT_VIEW = 0;

    private static final int OBJECT_TABLE = 1;

    private static final int SCHEMA_BATCH_METADATA_TABLE_THRESHOLD = 16;

    private static final String COLUMN_TYPE_PROBE_TABLE_NAME = "xbatis_ddl_auto_type_probe";

    private static final String COLUMN_TYPE_PROBE_MARKER_COLUMN_NAME = "xbatis_ddl_auto_probe_marker";

    private final DDLBuilder ddlBuilder;

    private final DDLExecutionListener executionListener;

    private final MetadataNameMatcher metadataNameMatcher = new MetadataNameMatcher();

    private final DDLDialect dialect = new DDLDialect();

    private final ColumnTypeMapper columnTypeMapper = new ColumnTypeMapper(dialect);

    private final ConcurrentMap<Class<?>, Optional<Class<?>>> enumSupportCodeTypeCache = new ConcurrentHashMap<>();

    private final List<String> executedSqlList = new ArrayList<>();

    public DefaultDDLAutoExecutor() {
        this(new DefaultDDLBuilder());
    }

    public DefaultDDLAutoExecutor(DDLBuilder ddlBuilder) {
        this(ddlBuilder, DDLExecutionListener.NONE);
    }

    public DefaultDDLAutoExecutor(DDLBuilder ddlBuilder, DDLExecutionListener executionListener) {
        this.ddlBuilder = Objects.requireNonNull(ddlBuilder, "ddlBuilder");
        this.executionListener = executionListener == null ? DDLExecutionListener.NONE : executionListener;
    }

    /**
     * 只生成单个实体的建表 SQL，不执行。
     *
     * @param dbType      数据库类型
     * @param entityClass xbatis 实体类
     * @return 建表 SQL
     */
    public String createTableSql(IDbType dbType, Class<?> entityClass) {
        return ddlBuilder.createTableSql(dbType, entityClass);
    }

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
    @Override
    public List<String> sqlList(IDbType dbType, DataSource dataSource, Mode mode, Collection<Class<?>> entityClasses) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            return sqlList(dbType, connection, mode, entityClasses);
        }
    }

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
    @Override
    public List<String> sqlList(IDbType dbType, Connection connection, Mode mode, Collection<Class<?>> entityClasses) throws SQLException {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(entityClasses, "entityClasses");

        if (entityClasses.isEmpty()) {
            return Collections.emptyList();
        }
        List<EntityDDLMetadata> entityMetadataList = entityMetadataList(dbType, tableInfos(entityClasses));
        if (entityMetadataList.isEmpty()) {
            return Collections.emptyList();
        }
        boolean includeColumns = mode == Mode.UPDATE || mode == Mode.SYNC;
        boolean includeIndexes = mode == Mode.SYNC || shouldReadIndexesForEntities(dbType, entityMetadataList);
        DatabaseMetadata databaseMetadata = loadDatabaseMetadataForEntities(dbType, connection, entityMetadataList,
                includeColumns, includeIndexes, mode == Mode.SYNC);
        prepareColumnTypeProbeIfNecessary(dbType, connection, mode, entityMetadataList, databaseMetadata);
        List<String> sqlList = new ArrayList<>();
        for (EntityDDLMetadata entityMetadata : entityMetadataList) {
            sqlList.addAll(createSqlList(dbType, mode, entityMetadata, databaseMetadata));
        }
        return sqlList;
    }

    /**
     * 获取最近一次自动建表执行中已成功执行的 SQL。
     *
     * @return 不可变 SQL 列表
     */
    @Override
    public List<String> getExecutedSqlList() {
        return Collections.unmodifiableList(new ArrayList<>(executedSqlList));
    }

    /**
     * 从数据源获取连接并按 CREATE 模式批量执行自动建表。
     *
     * @deprecated 请使用 {@link #execute(IDbType, DataSource, Mode, Collection)} 显式传入模式。
     */
    @Deprecated
    public void execute(IDbType dbType, DataSource dataSource, Collection<Class<?>> entityClasses) throws SQLException {
        execute(dbType, dataSource, Mode.CREATE, entityClasses);
    }

    /**
     * 从数据源获取连接并按 CREATE 模式批量执行自动建表。
     *
     * @deprecated 请使用 {@link #execute(IDbType, DataSource, Mode, Collection)} 显式传入模式。
     */
    @Deprecated
    public void execute(IDbType dbType, DataSource dataSource, Class<?>... entityClasses) throws SQLException {
        Objects.requireNonNull(entityClasses, "entityClasses");
        execute(dbType, dataSource, Mode.CREATE, Arrays.asList(entityClasses));
    }

    /**
     * 从数据源获取连接并按指定模式批量执行自动建表。
     *
     * @deprecated 请使用 {@link #execute(IDbType, DataSource, Mode, Collection)}。
     */
    @Deprecated
    public void execute(IDbType dbType, DataSource dataSource, Mode mode, Class<?>... entityClasses) throws SQLException {
        Objects.requireNonNull(entityClasses, "entityClasses");
        execute(dbType, dataSource, mode, Arrays.asList(entityClasses));
    }

    /**
     * 使用已有连接按 CREATE 模式批量执行自动建表。
     *
     * @deprecated 请使用 {@link #execute(IDbType, Connection, Mode, Collection)} 显式传入模式。
     */
    @Deprecated
    public void execute(IDbType dbType, Connection connection, Collection<Class<?>> entityClasses) throws SQLException {
        execute(dbType, connection, Mode.CREATE, entityClasses);
    }

    /**
     * 使用已有连接按 CREATE 模式批量执行自动建表。
     *
     * @deprecated 请使用 {@link #execute(IDbType, Connection, Mode, Collection)} 显式传入模式。
     */
    @Deprecated
    public void execute(IDbType dbType, Connection connection, Class<?>... entityClasses) throws SQLException {
        Objects.requireNonNull(entityClasses, "entityClasses");
        execute(dbType, connection, Mode.CREATE, Arrays.asList(entityClasses));
    }

    /**
     * 使用已有连接按指定模式批量执行自动建表。
     *
     * @deprecated 请使用 {@link #execute(IDbType, Connection, Mode, Collection)}。
     */
    @Deprecated
    public void execute(IDbType dbType, Connection connection, Mode mode, Class<?>... entityClasses) throws SQLException {
        Objects.requireNonNull(entityClasses, "entityClasses");
        execute(dbType, connection, mode, Arrays.asList(entityClasses));
    }

    /**
     * 从数据源获取连接并按指定模式批量执行自动建表。
     *
     * @param dbType        数据库类型
     * @param dataSource    数据源
     * @param mode          自动建表模式
     * @param entityClasses xbatis 实体类集合
     * @throws SQLException 执行失败时抛出
     */
    @Override
    public void execute(IDbType dbType, DataSource dataSource, Mode mode, Collection<Class<?>> entityClasses) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            execute(dbType, connection, mode, entityClasses);
        }
    }

    /**
     * 使用已有连接按指定模式批量执行自动建表。
     *
     * @param dbType        数据库类型
     * @param connection    数据库连接
     * @param mode          自动建表模式
     * @param entityClasses xbatis 实体类集合
     * @throws SQLException 执行失败时抛出
     */
    @Override
    public void execute(IDbType dbType, Connection connection, Mode mode, Collection<Class<?>> entityClasses) throws SQLException {
        Objects.requireNonNull(dbType, "dbType");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(entityClasses, "entityClasses");
        executedSqlList.clear();
        if (entityClasses.isEmpty()) {
            return;
        }
        List<EntityDDLMetadata> entityMetadataList = entityMetadataList(dbType, tableInfos(entityClasses));
        if (entityMetadataList.isEmpty()) {
            return;
        }
        long startNanos = System.nanoTime();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Starting DDL execution, dbType={}, mode={}, tableCount={}", dbType.getName(), mode, entityMetadataList.size());
        }
        try {
            boolean includeColumns = mode == Mode.UPDATE || mode == Mode.SYNC;
            boolean includeIndexes = mode == Mode.SYNC || shouldReadIndexesForEntities(dbType, entityMetadataList);
            DatabaseMetadata databaseMetadata = loadDatabaseMetadataForEntities(dbType, connection, entityMetadataList,
                    includeColumns, includeIndexes, mode == Mode.SYNC);
            prepareColumnTypeProbeIfNecessary(dbType, connection, mode, entityMetadataList, databaseMetadata);
            try (Statement statement = connection.createStatement()) {
                for (EntityDDLMetadata entityMetadata : entityMetadataList) {
                    executeSql(dbType, statement, createSqlList(dbType, mode, entityMetadata, databaseMetadata));
                }
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Finished DDL execution, dbType={}, mode={}, tableCount={}, sqlCount={}, elapsed={} ms",
                        dbType.getName(), mode, entityMetadataList.size(), executedSqlList.size(), elapsedMillis(startNanos));
            }
        } catch (SQLException exception) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed DDL execution, dbType={}, mode={}, tableCount={}, sqlCount={}, elapsed={} ms, message={}",
                        dbType.getName(), mode, entityMetadataList.size(), executedSqlList.size(), elapsedMillis(startNanos), exception.getMessage());
            }
            throw exception;
        }
    }

    /**
     * 计算从指定起点开始的毫秒耗时。
     */
    protected long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 解析实体表元数据。
     */
    protected List<TableInfo> tableInfos(Collection<Class<?>> entityClasses) {
        Objects.requireNonNull(entityClasses, "entityClasses");
        List<TableInfo> tableInfos = new ArrayList<>(entityClasses.size());
        for (Class<?> entityClass : entityClasses) {
            tableInfos.add(Tables.get(Objects.requireNonNull(entityClass, "entityClass")));
        }
        return tableInfos;
    }

    /**
     * 解析实体对应的物理表名列表。
     */
    protected List<String> resolveTableNames(TableInfo tableInfo) {
        return DDLTableNameResolverUtil.resolve(tableInfo);
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
     * 解析实体 DDL 元数据，供一次执行内复用。
     */
    protected List<EntityDDLMetadata> entityMetadataList(IDbType dbType, Collection<TableInfo> tableInfos) {
        Objects.requireNonNull(tableInfos, "tableInfos");
        List<EntityDDLMetadata> entityMetadataList = new ArrayList<>(tableInfos.size());
        for (TableInfo tableInfo : tableInfos) {
            if (!DDLTableNameResolverUtil.isTable(tableInfo)) {
                continue;
            }
            entityMetadataList.add(entityMetadata(dbType, tableInfo));
        }
        return entityMetadataList;
    }

    /**
     * 解析单个实体 DDL 元数据。
     */
    protected EntityDDLMetadata entityMetadata(IDbType dbType, TableInfo tableInfo) {
        return ddlBuilder.getEntityDDLMetadata(dbType, Objects.requireNonNull(tableInfo, "tableInfo"));
    }

    /**
     * 从实体 DDL 元数据列表中提取表元数据。
     */
    protected List<TableInfo> tableInfosFromEntityMetadata(Collection<EntityDDLMetadata> entityMetadataList) {
        Objects.requireNonNull(entityMetadataList, "entityMetadataList");
        List<TableInfo> tableInfos = new ArrayList<>(entityMetadataList.size());
        for (EntityDDLMetadata entityMetadata : entityMetadataList) {
            tableInfos.add(Objects.requireNonNull(entityMetadata, "entityMetadata").getTableInfo());
        }
        return tableInfos;
    }

    /**
     * 根据当前数据库状态生成单个实体将要执行的 DDL SQL。
     */
    protected List<String> createSqlList(IDbType dbType, Connection connection, Mode mode, Class<?> entityClass) throws SQLException {
        return createSqlList(dbType, connection, mode, Tables.get(entityClass));
    }

    /**
     * 根据当前数据库状态生成单个实体将要执行的 DDL SQL。
     */
    protected List<String> createSqlList(IDbType dbType, Connection connection, Mode mode, TableInfo tableInfo) throws SQLException {
        if (!DDLTableNameResolverUtil.isTable(tableInfo)) {
            return Collections.emptyList();
        }
        EntityDDLMetadata entityMetadata = entityMetadata(dbType, tableInfo);
        boolean includeColumns = mode == Mode.UPDATE || mode == Mode.SYNC;
        boolean includeIndexes = mode == Mode.SYNC || shouldReadIndexesForEntities(dbType, Collections.singletonList(entityMetadata));
        DatabaseMetadata databaseMetadata = loadDatabaseMetadataForEntities(dbType, connection, Collections.singletonList(entityMetadata),
                includeColumns, includeIndexes, mode == Mode.SYNC);
        prepareColumnTypeProbeIfNecessary(dbType, connection, mode, Collections.singletonList(entityMetadata), databaseMetadata);
        return createSqlList(dbType, mode, entityMetadata, databaseMetadata);
    }

    /**
     * 根据当前数据库元数据快照生成单个实体将要执行的 DDL SQL。
     */
    protected List<String> createSqlList(IDbType dbType, Mode mode, TableInfo tableInfo, DatabaseMetadata databaseMetadata) {
        return createSqlList(dbType, mode, entityMetadata(dbType, tableInfo), databaseMetadata);
    }

    /**
     * 根据当前数据库元数据快照生成单个实体将要执行的 DDL SQL。
     */
    protected List<String> createSqlList(IDbType dbType, Mode mode, EntityDDLMetadata entityMetadata, DatabaseMetadata databaseMetadata) {
        TableInfo tableInfo = entityMetadata.getTableInfo();
        List<String> tableNames = resolveTableNames(tableInfo);
        if (tableNames.isEmpty()) {
            return Collections.emptyList();
        }
        validateExplicitIndexNames(tableInfo, entityMetadata.getIndexes(), tableNames.size());

        List<String> sqlList = new ArrayList<>();
        for (String tableName : tableNames) {
            int objectType = databaseMetadata.objectType(tableInfo, tableName);
            if (objectType == OBJECT_VIEW) {
                continue;
            }
            if (objectType == OBJECT_NOT_EXISTS) {
                sqlList.addAll(createAddSequenceSqlList(dbType, entityMetadata, databaseMetadata));
                List<String> createTableSqlList = ddlBuilder.createTableSqlList(dbType, entityMetadata, tableName);
                removeSequenceSqlList(dbType, entityMetadata, createTableSqlList);
                sqlList.addAll(createTableSqlList);
                databaseMetadata.addTable(tableInfo, tableName);
                if (mode == Mode.UPDATE || mode == Mode.SYNC) {
                    databaseMetadata.addColumns(tableInfo, tableName, entityMetadata.getColumns());
                    databaseMetadata.addIndexes(tableInfo, tableName,
                            ddlBuilder.resolveIndexes(dbType, tableInfo, entityMetadata.getIndexes(), tableName));
                }
                continue;
            }
            if (mode == Mode.UPDATE || mode == Mode.SYNC) {
                sqlList.addAll(createAddSequenceSqlList(dbType, entityMetadata, databaseMetadata));
                if (mode == Mode.SYNC) {
                    sqlList.addAll(createDropIndexSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                    if (dbType == DbType.DB2) {
                        // DB2 的 DROP COLUMN 会把表置为 REORG PENDING，期间不允许 CREATE INDEX，
                        // 因此 DB2 需要先建索引，最后再删列
                        sqlList.addAll(createModifyColumnSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                        sqlList.addAll(createAddColumnSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                        sqlList.addAll(createAddIndexSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                        sqlList.addAll(createDropColumnSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                    } else {
                        sqlList.addAll(createDropColumnSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                        sqlList.addAll(createModifyColumnSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                        sqlList.addAll(createAddColumnSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                        sqlList.addAll(createAddIndexSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                    }
                } else {
                    sqlList.addAll(createAddColumnSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                    sqlList.addAll(createAddIndexSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                }
            }
        }
        return sqlList;
    }

    /**
     * 按实体 schema 分组加载当前连接可见的表和列元数据，供批量实体复用。
     */
    protected DatabaseMetadata loadDatabaseMetadata(Connection connection, Collection<TableInfo> tableInfos, boolean includeColumns) throws SQLException {
        return loadDatabaseMetadata(null, connection, tableInfos, includeColumns);
    }

    /**
     * 按实体 schema 分组加载当前连接可见的表和列元数据，供批量实体复用。
     */
    protected DatabaseMetadata loadDatabaseMetadata(IDbType dbType, Connection connection, Collection<TableInfo> tableInfos, boolean includeColumns) throws SQLException {
        if (dbType == null) {
            return loadDatabaseMetadataWithoutEntityMetadata(connection, tableInfos, includeColumns);
        }
        return loadDatabaseMetadataForEntities(dbType, connection, entityMetadataList(dbType, tableInfos), includeColumns);
    }

    /**
     * 按实体 schema 分组加载当前连接可见的表、列和索引元数据，供批量实体复用。
     */
    protected DatabaseMetadata loadDatabaseMetadataForEntities(IDbType dbType, Connection connection, Collection<EntityDDLMetadata> entityMetadataList, boolean includeColumns) throws SQLException {
        return loadDatabaseMetadataForEntities(dbType, connection, entityMetadataList, includeColumns,
                shouldReadIndexesForEntities(dbType, entityMetadataList), false);
    }

    /**
     * 兼容未传数据库类型的 protected API，不解析实体 DDL 元数据。
     */
    protected DatabaseMetadata loadDatabaseMetadataWithoutEntityMetadata(Connection connection, Collection<TableInfo> tableInfos, boolean includeColumns) throws SQLException {
        DatabaseMetadata databaseMetadata = new DatabaseMetadata(connection.getCatalog(), getSchema(connection));
        DatabaseMetaData metaData = connection.getMetaData();
        readEntityTables(metaData, databaseMetadata, tableInfos);
        Set<String> sequenceSchemas = schemas(tableInfos, databaseMetadata.defaultSchema);
        if (!sequenceSchemas.isEmpty()) {
            readSequences(null, metaData, databaseMetadata, sequenceSchemas);
        }
        if (includeColumns) {
            readColumns(metaData, databaseMetadata, tableInfos);
            readIndexes(metaData, databaseMetadata, tableInfos);
        }
        return databaseMetadata;
    }

    /**
     * 按实体 schema 分组加载当前连接可见的表和列元数据，供批量实体复用。
     */
    protected DatabaseMetadata loadDatabaseMetadataForEntities(IDbType dbType, Connection connection, Collection<EntityDDLMetadata> entityMetadataList,
                                                               boolean includeColumns, boolean includeIndexes, boolean includePrimaryKeys) throws SQLException {
        List<TableInfo> tableInfos = tableInfosFromEntityMetadata(entityMetadataList);
        DatabaseMetadata databaseMetadata = new DatabaseMetadata(connection.getCatalog(), getSchema(connection));
        DatabaseMetaData metaData = connection.getMetaData();
        readEntityTables(metaData, databaseMetadata, tableInfos);
        Set<String> sequenceSchemas = sequenceSchemasForEntities(dbType, entityMetadataList, databaseMetadata.defaultSchema);
        if (!sequenceSchemas.isEmpty()) {
            readSequences(dbType, metaData, databaseMetadata, sequenceSchemas);
        }
        if (includeColumns) {
            readColumns(metaData, databaseMetadata, tableInfos);
            if (includeIndexes) {
                readIndexes(metaData, databaseMetadata, tableInfos);
            }
        }
        if (includePrimaryKeys) {
            readPrimaryKeys(metaData, databaseMetadata, tableInfos);
        }
        return databaseMetadata;
    }

    /**
     * 只有实体声明了序列时才需要读取数据库序列元数据，避免普通实体额外查询系统表。
     */
    protected boolean shouldReadSequences(IDbType dbType, Collection<TableInfo> tableInfos) {
        if (dbType == null) {
            return true;
        }
        return shouldReadSequencesForEntities(dbType, entityMetadataList(dbType, tableInfos));
    }

    /**
     * 只有实体声明了序列时才需要读取数据库序列元数据，避免普通实体额外查询系统表。
     */
    protected boolean shouldReadSequencesForEntities(IDbType dbType, Collection<EntityDDLMetadata> entityMetadataList) {
        if (dbType == null) {
            return true;
        }
        return !sequenceSchemasForEntities(dbType, entityMetadataList, null).isEmpty();
    }

    /**
     * 收集实体序列所在 schema；实体未声明序列时返回空集合。
     */
    protected Set<String> sequenceSchemas(IDbType dbType, Collection<TableInfo> tableInfos, String defaultSchema) {
        if (dbType == null) {
            return schemas(tableInfos, defaultSchema);
        }
        return sequenceSchemasForEntities(dbType, entityMetadataList(dbType, tableInfos), defaultSchema);
    }

    /**
     * 收集实体序列所在 schema；实体未声明序列时返回空集合。
     */
    protected Set<String> sequenceSchemasForEntities(IDbType dbType, Collection<EntityDDLMetadata> entityMetadataList, String defaultSchema) {
        if (dbType == null) {
            return schemas(tableInfosFromEntityMetadata(entityMetadataList), defaultSchema);
        }
        Set<String> schemas = new LinkedHashSet<>();
        for (EntityDDLMetadata entityMetadata : entityMetadataList) {
            TableInfo tableInfo = entityMetadata.getTableInfo();
            if (resolveTableNames(tableInfo).isEmpty()) {
                continue;
            }
            for (SequenceInfo sequence : entityMetadata.getSequences()) {
                String schema = sequence.getSchema() == null ? tableInfo.getSchema() : sequence.getSchema();
                schemas.add(resolveSchema(schema, defaultSchema));
            }
        }
        return schemas;
    }

    /**
     * 只有实体声明了索引时才需要读取数据库索引元数据。
     */
    protected boolean shouldReadIndexes(IDbType dbType, Collection<TableInfo> tableInfos) {
        if (dbType == null) {
            return true;
        }
        return shouldReadIndexesForEntities(dbType, entityMetadataList(dbType, tableInfos));
    }

    /**
     * 只有实体声明了索引时才需要读取数据库索引元数据。
     */
    protected boolean shouldReadIndexesForEntities(IDbType dbType, Collection<EntityDDLMetadata> entityMetadataList) {
        if (dbType == null) {
            return true;
        }
        for (EntityDDLMetadata entityMetadata : entityMetadataList) {
            if (!entityMetadata.getIndexes().isEmpty() && !resolveTableNames(entityMetadata.getTableInfo()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * SYNC 模式下创建固定探测表，使用当前数据库 JDBC 元数据口径判断类型族。
     */
    protected void prepareColumnTypeProbeIfNecessary(IDbType dbType,
                                                     Connection connection,
                                                     Mode mode,
                                                     Collection<EntityDDLMetadata> entityMetadataList,
                                                     DatabaseMetadata databaseMetadata) throws SQLException {
        if (mode != Mode.SYNC || !shouldProbeColumnTypes(dbType, entityMetadataList)) {
            return;
        }
        databaseMetadata.setColumnTypeProbeResult(probeColumnTypes(dbType, connection, entityMetadataList));
    }

    /**
     * 只有需要比较列类型的数据库才创建探测表。
     */
    protected boolean shouldProbeColumnTypes(IDbType dbType, Collection<EntityDDLMetadata> entityMetadataList) {
        if (!dialect.supportsModifyColumn(dbType)) {
            return false;
        }
        for (EntityDDLMetadata entityMetadata : entityMetadataList) {
            if (!entityMetadata.getColumns().isEmpty() && !resolveTableNames(entityMetadata.getTableInfo()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建固定探测表，读取当前数据库对实体列类型族的真实元数据返回值，并在 finally 中清理探测表。
     */
    protected ColumnTypeProbeResult probeColumnTypes(IDbType dbType,
                                                     Connection connection,
                                                     Collection<EntityDDLMetadata> entityMetadataList) throws SQLException {
        Map<String, ColumnTypeProbeSpec> probeSpecs = columnTypeProbeSpecs(dbType, entityMetadataList);
        if (probeSpecs.isEmpty()) {
            return new ColumnTypeProbeResult(Collections.<String, ColumnMetadata>emptyMap());
        }
        String catalog = connection.getCatalog();
        String schema = getSchema(connection);
        SQLException failure = null;
        try (Statement statement = connection.createStatement()) {
            dropColumnTypeProbeTableIfExists(dbType, connection, statement, catalog, schema);
            statement.execute(executableSql(dbType, buildCreateColumnTypeProbeTableSql(dbType, probeSpecs.values())));
            ColumnTypeProbeResult probeResult = readColumnTypeProbeResult(connection, catalog, schema, probeSpecs);
            return probeResult;
        } catch (SQLException exception) {
            failure = exception;
            throw exception;
        } finally {
            try (Statement statement = connection.createStatement()) {
                dropColumnTypeProbeTableIfExists(dbType, connection, statement, catalog, schema);
            } catch (SQLException exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    throw exception;
                }
            }
        }
    }

    /**
     * 收集本轮实体涉及的类型族；同一类型族只探测一次，长度和精度仍由业务字段自身比较。
     */
    protected Map<String, ColumnTypeProbeSpec> columnTypeProbeSpecs(IDbType dbType, Collection<EntityDDLMetadata> entityMetadataList) {
        Map<String, ColumnTypeProbeSpec> probeSpecs = new LinkedHashMap<>();
        for (EntityDDLMetadata entityMetadata : entityMetadataList) {
            for (ColumnInfo column : entityMetadata.getColumns()) {
                ColumnTypeProbeSpec probeSpec = columnTypeProbeSpec(dbType, column);
                if (!probeSpecs.containsKey(probeSpec.typeFamilyKey)) {
                    probeSpecs.put(probeSpec.typeFamilyKey, probeSpec);
                }
            }
        }
        return probeSpecs;
    }

    /**
     * 将实体列转换为探测表列定义。
     */
    protected ColumnTypeProbeSpec columnTypeProbeSpec(IDbType dbType, ColumnInfo column) {
        String typeSql = buildExpectedColumnTypeSignature(dbType, column, false);
        String typeFamilyKey = columnTypeFamilyKey(dbType, typeSql);
        return new ColumnTypeProbeSpec(typeFamilyKey, columnTypeProbeColumnName(typeFamilyKey), typeSql);
    }

    /**
     * 固定探测表中的列名，保持短且确定，避免 Oracle 等数据库标识符长度限制。
     */
    protected String columnTypeProbeColumnName(String typeFamilyKey) {
        String normalized = typeFamilyKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        normalized = normalized.replaceAll("^_+", "").replaceAll("_+$", "");
        if (normalized.isEmpty()) {
            normalized = "type";
        }
        String columnName = "c_" + normalized;
        if (columnName.length() <= 30) {
            return columnName;
        }
        return columnName.substring(0, 22) + "_" + Integer.toHexString(typeFamilyKey.hashCode());
    }

    /**
     * 创建探测表 SQL。探测表使用固定名称，列只表达类型族，不表达业务约束。
     */
    protected String buildCreateColumnTypeProbeTableSql(IDbType dbType, Collection<ColumnTypeProbeSpec> probeSpecs) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ");
        appendColumnTypeProbeTableName(ddl, dbType);
        ddl.append(" (");
        ddl.append(dbType.wrap(COLUMN_TYPE_PROBE_MARKER_COLUMN_NAME)).append(" INTEGER");
        for (ColumnTypeProbeSpec probeSpec : probeSpecs) {
            ddl.append(", ");
            ddl.append(dbType.wrap(probeSpec.columnName)).append(" ").append(probeSpec.typeSql);
        }
        ddl.append(")");
        return ddl.toString();
    }

    /**
     * 删除固定探测表 SQL。
     */
    protected String buildDropColumnTypeProbeTableSql(IDbType dbType) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("DROP TABLE ");
        appendColumnTypeProbeTableName(ddl, dbType);
        return ddl.toString();
    }

    protected void appendColumnTypeProbeTableName(StringBuilder ddl, IDbType dbType) {
        ddl.append(dbType.wrap(COLUMN_TYPE_PROBE_TABLE_NAME));
    }

    /**
     * 通过 JDBC 元数据确认固定探测表存在后再删除，避免 DROP TABLE IF EXISTS 方言差异。
     */
    protected void dropColumnTypeProbeTableIfExists(IDbType dbType,
                                                    Connection connection,
                                                    Statement statement,
                                                    String catalog,
                                                    String schema) throws SQLException {
        if (columnTypeProbeTableExists(connection, catalog, schema)) {
            statement.execute(executableSql(dbType, buildDropColumnTypeProbeTableSql(dbType)));
        }
    }

    protected boolean columnTypeProbeTableExists(Connection connection, String catalog, String schema) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        Set<String> schemaCandidates = candidates(schema);
        if (schema == null) {
            schemaCandidates.add(null);
        }
        Set<String> tableCandidates = candidates(COLUMN_TYPE_PROBE_TABLE_NAME);
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        for (String schemaCandidate : schemaCandidates) {
            for (String tableName : tableCandidates) {
                if (tableExists(metaData, catalog, schemaCandidate, tableName, TABLE_TYPES)) {
                    return true;
                }
                if (schemaCandidate != null && schemaAsCatalogFallback
                        && tableExists(metaData, schemaCandidate, null, tableName, TABLE_TYPES)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 读取探测表列元数据。所有探测列都必须被当前 JDBC 驱动返回。
     */
    protected ColumnTypeProbeResult readColumnTypeProbeResult(Connection connection,
                                                             String catalog,
                                                             String schema,
                                                             Map<String, ColumnTypeProbeSpec> probeSpecs) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        DatabaseMetadata probeMetadata = new DatabaseMetadata(catalog, schema);
        Set<String> readKeys = new LinkedHashSet<>();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        Set<String> schemaCandidates = candidates(schema);
        if (schema == null) {
            schemaCandidates.add(null);
        }
        Set<String> tableCandidates = candidates(COLUMN_TYPE_PROBE_TABLE_NAME);
        for (String schemaCandidate : schemaCandidates) {
            for (String tableName : tableCandidates) {
                readColumnMetadataIfNecessary(metaData, catalog, schemaCandidate, tableName, probeMetadata, readKeys);
                if (schemaCandidate != null && schemaAsCatalogFallback) {
                    readColumnMetadataIfNecessary(metaData, schemaCandidate, null, tableName, probeMetadata, readKeys);
                }
            }
        }

        Map<String, ColumnMetadata> columnMetadataByTypeFamily = new LinkedHashMap<>();
        for (ColumnTypeProbeSpec probeSpec : probeSpecs.values()) {
            ColumnMetadata columnMetadata = probeMetadata.getColumnMetadata(catalog, schema,
                    COLUMN_TYPE_PROBE_TABLE_NAME, probeSpec.columnName);
            if (columnMetadata == null) {
                throw new SQLException("Failed to read DDL type probe column metadata: "
                        + COLUMN_TYPE_PROBE_TABLE_NAME + "." + probeSpec.columnName);
            }
            columnMetadataByTypeFamily.put(probeSpec.typeFamilyKey, columnMetadata);
        }
        return new ColumnTypeProbeResult(columnMetadataByTypeFamily);
    }

    /**
     * 读取当前连接默认 schema；驱动不支持时返回 null。
     */
    protected String getSchema(Connection connection) throws SQLException {
        try {
            return connection.getSchema();
        } catch (SQLFeatureNotSupportedException ignored) {
            return null;
        } catch (AbstractMethodError ignored) {
            return null;
        }
    }

    /**
     * 收集实体声明的 schema。
     */
    protected Set<String> schemas(Collection<TableInfo> tableInfos) {
        return schemas(tableInfos, null);
    }

    /**
     * 收集实体声明的 schema；实体未声明 schema 时使用连接默认 schema。
     */
    protected Set<String> schemas(Collection<TableInfo> tableInfos, String defaultSchema) {
        Set<String> schemas = new LinkedHashSet<>();
        for (TableInfo tableInfo : tableInfos) {
            if (!DDLTableNameResolverUtil.isTable(tableInfo)) {
                continue;
            }
            schemas.add(resolveSchema(tableInfo.getSchema(), defaultSchema));
        }
        return schemas;
    }

    /**
     * 实体数量较多时改按 schema 批量读取元数据，减少 JDBC 往返次数。
     */
    protected boolean shouldBatchReadSchemaMetadata(Collection<TableInfo> tableInfos) {
        return physicalTableCount(tableInfos) > SCHEMA_BATCH_METADATA_TABLE_THRESHOLD;
    }

    /**
     * 部分数据库的 schema 级列/索引元数据查询会扫描大量系统表，实体较多时也优先按目标表精确读取。
     */
    protected boolean shouldBatchReadSchemaMetadata(DatabaseMetaData metaData, Collection<TableInfo> tableInfos) throws SQLException {
        return shouldBatchReadSchemaMetadata(tableInfos) && supportsSchemaBatchMetadata(metaData);
    }

    /**
     * 判断当前数据库是否适合按 schema 批量读取表、列和索引元数据。
     */
    protected boolean supportsSchemaBatchMetadata(DatabaseMetaData metaData) throws SQLException {
        if (metaData == null) {
            return true;
        }
        String productName = metaData.getDatabaseProductName();
        if (productName == null) {
            return true;
        }
        String normalizedProductName = productName.toLowerCase(Locale.ROOT);
        return !normalizedProductName.contains("postgresql")
                && !normalizedProductName.contains("opengauss")
                && !normalizedProductName.contains("kingbase")
                && !normalizedProductName.contains("highgo");
    }

    /**
     * 统计实体解析后的物理表数量。
     */
    protected int physicalTableCount(Collection<TableInfo> tableInfos) {
        int count = 0;
        for (TableInfo tableInfo : tableInfos) {
            count += resolveTableNames(tableInfo).size();
        }
        return count;
    }

    /**
     * 解析实体 schema；未声明时落到连接默认 schema，避免扫描全库。
     */
    protected String resolveSchema(String schema, String defaultSchema) {
        return isBlank(schema) ? (isBlank(defaultSchema) ? null : defaultSchema) : schema;
    }

    /**
     * 按 schema 分组读取数据表。
     */
    protected void readTables(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<String> schemas) throws SQLException {
        for (String schema : schemas) {
            readTables(metaData, databaseMetadata, schema);
        }
    }

    /**
     * 按实体表名读取数据表和视图，避免扫描整个 schema。
     */
    protected void readEntityTables(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        if (shouldBatchReadSchemaMetadata(metaData, tableInfos)) {
            readTables(metaData, databaseMetadata, schemas(tableInfos, databaseMetadata.defaultSchema));
            return;
        }
        Set<String> readKeys = new LinkedHashSet<>();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        for (TableInfo tableInfo : tableInfos) {
            String schemaValue = resolveSchema(tableInfo.getSchema(), databaseMetadata.defaultSchema);
            Set<String> schemaCandidates = candidates(schemaValue);
            if (schemaValue == null) {
                schemaCandidates.add(null);
            }
            for (String physicalTableName : resolveTableNames(tableInfo)) {
                if (databaseMetadata.objectType(tableInfo, physicalTableName) != OBJECT_NOT_EXISTS) {
                    continue;
                }
                Set<String> tableCandidates = candidates(physicalTableName);
                boolean tableResolved = false;
                for (String schemaCandidate : schemaCandidates) {
                    for (String tableName : tableCandidates) {
                        readTableMetadataIfNecessary(metaData, databaseMetadata.catalog, schemaCandidate, tableName, databaseMetadata, readKeys);
                        if (databaseMetadata.objectType(tableInfo, physicalTableName) != OBJECT_NOT_EXISTS) {
                            tableResolved = true;
                            break;
                        }
                        if (schemaCandidate != null && schemaAsCatalogFallback) {
                            readTableMetadataIfNecessary(metaData, schemaCandidate, null, tableName, databaseMetadata, readKeys);
                            if (databaseMetadata.objectType(tableInfo, physicalTableName) != OBJECT_NOT_EXISTS) {
                                tableResolved = true;
                                break;
                            }
                        }
                    }
                    if (tableResolved) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * 读取单个 schema 下的数据表和视图。
     */
    protected void readTables(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, String schema) throws SQLException {
        Set<String> schemaCandidates = candidates(schema);
        if (isBlank(schema)) {
            schemaCandidates.add(null);
        }
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        for (String schemaCandidate : schemaCandidates) {
            readTableMetadata(metaData, databaseMetadata.catalog, schemaCandidate, databaseMetadata);
            if (schemaCandidate != null && schemaAsCatalogFallback) {
                readTableMetadata(metaData, schemaCandidate, null, databaseMetadata);
            }
        }
    }

    /**
     * 去重后读取数据表和视图。
     */
    protected void readTableMetadataIfNecessary(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata, Set<String> readKeys) throws SQLException {
        String readKey = metadataReadKey(catalog, schema, tableName);
        if (readKeys.add(readKey)) {
            readTableMetadata(metaData, catalog, schema, tableName, databaseMetadata);
        }
    }

    /**
     * 从 JDBC 元数据结果集中读取数据表和视图。
     */
    protected void readTableMetadata(DatabaseMetaData metaData, String catalog, String schema, DatabaseMetadata databaseMetadata) throws SQLException {
        readTableMetadata(metaData, catalog, schema, null, databaseMetadata);
    }

    /**
     * 从 JDBC 元数据结果集中读取数据表和视图。
     */
    protected void readTableMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) throws SQLException {
        try (ResultSet resultSet = metaData.getTables(catalog, schema, tableName, TABLE_AND_VIEW_TYPES)) {
            while (resultSet.next()) {
                String actualCatalog = getString(resultSet, "TABLE_CAT");
                String actualSchema = getString(resultSet, "TABLE_SCHEM");
                String actualTableName = getString(resultSet, "TABLE_NAME");
                databaseMetadata.addTable(
                        actualCatalog,
                        actualSchema,
                        actualTableName,
                        getString(resultSet, "TABLE_TYPE"),
                        metadataReadValue(actualCatalog, catalog),
                        metadataReadValue(actualSchema, schema),
                        metadataReadValue(actualTableName, tableName)
                );
            }
        }
    }

    /**
     * JDBC 元数据行缺少 catalog/schema/table 时，继续使用本次成功查询的入参。
     */
    protected String metadataReadValue(String actualValue, String queryValue) {
        return isBlank(actualValue) ? queryValue : actualValue;
    }

    /**
     * 按 schema 分组读取数据库序列。
     */
    protected void readSequences(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<String> schemas) throws SQLException {
        readSequences(null, metaData, databaseMetadata, schemas);
    }

    /**
     * 按 schema 分组读取数据库序列。
     */
    protected void readSequences(IDbType dbType, DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<String> schemas) throws SQLException {
        for (String schema : schemas) {
            readSequences(dbType, metaData, databaseMetadata, schema);
        }
    }

    /**
     * 读取单个 schema 下的数据库序列。
     */
    protected void readSequences(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, String schema) throws SQLException {
        readSequences(null, metaData, databaseMetadata, schema);
    }

    /**
     * 读取单个 schema 下的数据库序列。
     */
    protected void readSequences(IDbType dbType, DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, String schema) throws SQLException {
        Set<String> schemaCandidates = candidates(schema);
        if (isBlank(schema)) {
            schemaCandidates.add(null);
        }
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        for (String schemaCandidate : schemaCandidates) {
            readSequenceMetadata(dbType, metaData, databaseMetadata.catalog, schemaCandidate, databaseMetadata);
            if (schemaCandidate != null && schemaAsCatalogFallback) {
                readSequenceMetadata(dbType, metaData, schemaCandidate, null, databaseMetadata);
            }
        }
    }

    /**
     * 从 JDBC 元数据结果集中读取数据库序列。
     */
    protected void readSequenceMetadata(DatabaseMetaData metaData, String catalog, String schema, DatabaseMetadata databaseMetadata) throws SQLException {
        readSequenceMetadata(null, metaData, catalog, schema, databaseMetadata);
    }

    /**
     * 从 JDBC 元数据结果集中读取数据库序列。
     */
    protected void readSequenceMetadata(IDbType dbType, DatabaseMetaData metaData, String catalog, String schema, DatabaseMetadata databaseMetadata) throws SQLException {
        boolean hasSequenceMetadata = false;
        try (ResultSet resultSet = metaData.getTables(catalog, schema, null, SEQUENCE_TYPES)) {
            while (resultSet.next()) {
                hasSequenceMetadata = true;
                databaseMetadata.addSequence(
                        getString(resultSet, "TABLE_CAT"),
                        getString(resultSet, "TABLE_SCHEM"),
                        getString(resultSet, "TABLE_NAME")
                );
            }
        }
        if (usesOracleSequenceMetadata(dbType) && readOracleSequenceMetadata(metaData, schema, databaseMetadata)) {
            return;
        }
        if (!hasSequenceMetadata) {
            if (dbType == DbType.DB2) {
                readDb2SequenceMetadata(metaData, schema, databaseMetadata);
            } else {
                readInformationSchemaSequenceMetadata(metaData, schema, databaseMetadata);
            }
        }
    }

    /**
     * DB2 JDBC 驱动不一定通过 getTables 暴露 SEQUENCE，补充读取 SYSCAT.SEQUENCES。
     */
    protected void readDb2SequenceMetadata(DatabaseMetaData metaData, String schema, DatabaseMetadata databaseMetadata) throws SQLException {
        Connection connection = metaData.getConnection();
        String sql = "SELECT CAST(NULL AS VARCHAR(128)) AS SEQUENCE_CATALOG, "
                + "SEQSCHEMA AS SEQUENCE_SCHEMA, SEQNAME AS SEQUENCE_NAME FROM SYSCAT.SEQUENCES";
        boolean hasSchema = !isBlank(schema);
        if (hasSchema) {
            sql += " WHERE SEQSCHEMA = ?";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (hasSchema) {
                statement.setString(1, unquoteIdentifier(schema));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    databaseMetadata.addSequence(
                            getString(resultSet, "SEQUENCE_CATALOG"),
                            getString(resultSet, "SEQUENCE_SCHEMA"),
                            getString(resultSet, "SEQUENCE_NAME")
                    );
                }
            }
        }
    }

    /**
     * Oracle/DM JDBC 驱动不一定通过 getTables 暴露 SEQUENCE，补充读取 ALL_SEQUENCES。
     */
    protected boolean readOracleSequenceMetadata(DatabaseMetaData metaData, String schema, DatabaseMetadata databaseMetadata) throws SQLException {
        Connection connection = metaData.getConnection();
        String sql = "SELECT NULL AS SEQUENCE_CATALOG, SEQUENCE_OWNER AS SEQUENCE_SCHEMA, SEQUENCE_NAME FROM ALL_SEQUENCES";
        boolean hasSchema = !isBlank(schema);
        if (hasSchema) {
            sql += " WHERE SEQUENCE_OWNER = ? OR SEQUENCE_OWNER = UPPER(?)";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (hasSchema) {
                String unquotedSchema = unquoteIdentifier(schema);
                statement.setString(1, unquotedSchema);
                statement.setString(2, unquotedSchema);
            }
            boolean hasSequenceMetadata = false;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    hasSequenceMetadata = true;
                    databaseMetadata.addSequence(
                            getString(resultSet, "SEQUENCE_CATALOG"),
                            getString(resultSet, "SEQUENCE_SCHEMA"),
                            getString(resultSet, "SEQUENCE_NAME")
                    );
                }
            }
            return hasSequenceMetadata;
        } catch (SQLException ignored) {
            return false;
        }
    }

    /**
     * Oracle 兼容数据库需要通过 ALL_SEQUENCES 补充序列元数据。
     */
    protected boolean usesOracleSequenceMetadata(IDbType dbType) {
        return dbType == DbType.ORACLE || dbType == DbType.DM;
    }

    /**
     * 部分 JDBC 驱动不会通过 getTables 暴露 SEQUENCE，尝试标准 INFORMATION_SCHEMA.SEQUENCES。
     */
    protected void readInformationSchemaSequenceMetadata(DatabaseMetaData metaData, String schema, DatabaseMetadata databaseMetadata) throws SQLException {
        Connection connection = metaData.getConnection();
        String sql = "SELECT SEQUENCE_CATALOG, SEQUENCE_SCHEMA, SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES";
        boolean hasSchema = !isBlank(schema);
        if (hasSchema) {
            sql += " WHERE SEQUENCE_SCHEMA = ?";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (hasSchema) {
                statement.setString(1, schema);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    databaseMetadata.addSequence(
                            getString(resultSet, "SEQUENCE_CATALOG"),
                            getString(resultSet, "SEQUENCE_SCHEMA"),
                            getString(resultSet, "SEQUENCE_NAME")
                    );
                }
            }
        } catch (SQLException ignored) {
            // 非 INFORMATION_SCHEMA 数据库继续使用 getTables 结果。
        }
    }

    /**
     * 按 schema 分组读取数据列。
     */
    protected void readColumns(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        if (shouldBatchReadSchemaMetadata(metaData, tableInfos) && readColumnsBySchema(metaData, databaseMetadata, tableInfos)) {
            return;
        }
        readColumnsByEntity(metaData, databaseMetadata, tableInfos);
    }

    /**
     * 按实体表名读取数据列。
     */
    protected void readColumnsByEntity(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        Set<String> readKeys = new LinkedHashSet<>();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        for (TableInfo tableInfo : tableInfos) {
            for (String tableName : resolveTableNames(tableInfo)) {
                if (readColumnsByResolvedTable(metaData, databaseMetadata, tableInfo, tableName, readKeys)) {
                    continue;
                }
                if (databaseMetadata.objectType(tableInfo, tableName) == OBJECT_TABLE) {
                    readColumns(metaData, databaseMetadata, tableInfo, tableName, readKeys, schemaAsCatalogFallback);
                }
            }
        }
    }

    /**
     * 复用表元数据命中的真实读取目标，避免列元数据再次按 schema/table 大小写候选重复探测。
     */
    protected boolean readColumnsByResolvedTable(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata,
                                                 TableInfo tableInfo, String tableName, Set<String> readKeys) throws SQLException {
        TableMetadata tableMetadata = databaseMetadata.getTableMetadata(tableInfo, tableName);
        if (tableMetadata == null || !TABLE_TYPE.equals(tableMetadata.tableType)) {
            return false;
        }
        readColumnMetadataIfNecessary(metaData, tableMetadata.metadataCatalog, tableMetadata.metadataSchema,
                tableMetadata.metadataTableName, databaseMetadata, readKeys);
        return true;
    }

    /**
     * 按 schema 批量读取数据列。部分驱动不支持 tableName 为 null，失败时返回 false 让调用方回退到逐表读取。
     */
    protected boolean readColumnsBySchema(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        Set<String> readKeys = new LinkedHashSet<>();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        try {
            for (String schema : schemas(tableInfos, databaseMetadata.defaultSchema)) {
                Set<String> schemaCandidates = candidates(schema);
                if (schema == null) {
                    schemaCandidates.add(null);
                }
                for (String schemaCandidate : schemaCandidates) {
                    readColumnMetadataIfNecessary(metaData, databaseMetadata.catalog, schemaCandidate, null, databaseMetadata, readKeys);
                    if (schemaCandidate != null && schemaAsCatalogFallback) {
                        readColumnMetadataIfNecessary(metaData, schemaCandidate, null, null, databaseMetadata, readKeys);
                    }
                }
            }
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    /**
     * 读取单个实体表的数据列。
     */
    protected void readColumns(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, TableInfo tableInfo, Set<String> readKeys) throws SQLException {
        readColumns(metaData, databaseMetadata, tableInfo, readKeys, supportsSchemaAsCatalogFallback(metaData));
    }

    /**
     * 读取单个实体表的数据列。
     */
    protected void readColumns(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, TableInfo tableInfo, Set<String> readKeys, boolean schemaAsCatalogFallback) throws SQLException {
        readColumns(metaData, databaseMetadata, tableInfo, tableInfo.getTableName(), readKeys, schemaAsCatalogFallback);
    }

    /**
     * 读取单个物理表的数据列。
     */
    protected void readColumns(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, TableInfo tableInfo, String tableName, Set<String> readKeys, boolean schemaAsCatalogFallback) throws SQLException {
        String schema = resolveSchema(tableInfo.getSchema(), databaseMetadata.defaultSchema);
        Set<String> schemaCandidates = candidates(schema);
        if (schema == null) {
            schemaCandidates.add(null);
        }
        Set<String> tableCandidates = candidates(tableName);
        for (String schemaCandidate : schemaCandidates) {
            for (String tableCandidate : tableCandidates) {
                readColumnMetadataIfNecessary(metaData, databaseMetadata.catalog, schemaCandidate, tableCandidate, databaseMetadata, readKeys);
                if (schemaCandidate != null && schemaAsCatalogFallback) {
                    readColumnMetadataIfNecessary(metaData, schemaCandidate, null, tableCandidate, databaseMetadata, readKeys);
                }
            }
        }
    }

    /**
     * 去重后读取数据列。
     */
    protected void readColumnMetadataIfNecessary(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata, Set<String> readKeys) throws SQLException {
        String readKey = metadataReadKey(catalog, schema, tableName);
        if (readKeys.add(readKey)) {
            readColumnMetadata(metaData, catalog, schema, tableName, databaseMetadata);
        }
    }

    /**
     * 从 JDBC 元数据结果集中读取数据列。
     */
    protected void readColumnMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet resultSet = metaData.getColumns(catalog, schema, tableName, null)) {
            while (resultSet.next()) {
                columns.add(new ColumnMetadata(
                        getString(resultSet, "TABLE_CAT"),
                        getString(resultSet, "TABLE_SCHEM"),
                        getString(resultSet, "TABLE_NAME"),
                        getString(resultSet, "COLUMN_NAME"),
                        getInt(resultSet, "DATA_TYPE"),
                        getString(resultSet, "TYPE_NAME"),
                        getInt(resultSet, "COLUMN_SIZE"),
                        getInt(resultSet, "DECIMAL_DIGITS"),
                        getInt(resultSet, "NULLABLE"),
                        getString(resultSet, "COLUMN_DEF"),
                        getString(resultSet, "IS_AUTOINCREMENT"),
                        getString(resultSet, "IS_GENERATEDCOLUMN"),
                        getString(resultSet, "REMARKS")
                ));
            }
        }
        Map<String, Map<String, String>> sqlServerColumnRemarksByTable = supportsSqlServerColumnRemarks(metaData)
                ? new LinkedHashMap<>()
                : null;
        Map<String, Map<String, String>> oracleIdentityColumnsByTable = supportsOracleIdentityColumns(metaData)
                ? new LinkedHashMap<>()
                : null;
        for (ColumnMetadata column : columns) {
            String remarks = column.remarks;
            if (sqlServerColumnRemarksByTable != null) {
                String remarksCatalog = isBlank(column.catalog) ? catalog : column.catalog;
                String remarksSchema = isBlank(column.schema) ? schema : column.schema;
                String remarksTableName = isBlank(column.tableName) ? tableName : column.tableName;
                remarks = resolveSqlServerColumnRemarks(metaData, sqlServerColumnRemarksByTable,
                        remarksCatalog, remarksSchema, remarksTableName, column.columnName, remarks);
            }
            String isAutoIncrement = column.isAutoIncrement;
            if (oracleIdentityColumnsByTable != null) {
                isAutoIncrement = resolveOracleIdentityColumn(metaData, oracleIdentityColumnsByTable,
                        catalog, schema, tableName, column.columnName, isAutoIncrement);
            }
            databaseMetadata.addColumn(
                    column.catalog,
                    column.schema,
                    column.tableName,
                    column.columnName,
                    column.dataType,
                    column.typeName,
                    column.columnSize,
                    column.decimalDigits,
                    column.nullable,
                    column.columnDefault,
                    isAutoIncrement,
                    column.isGeneratedColumn,
                    remarks
            );
        }
    }

    /**
     * Oracle JDBC getColumns 的 IS_AUTOINCREMENT 恒为 'NO'，identity 列需要从数据字典补齐。
     */
    protected boolean supportsOracleIdentityColumns(DatabaseMetaData metaData) {
        if (metaData == null) {
            return false;
        }
        try {
            String productName = metaData.getDatabaseProductName();
            // identity_column 从 12c 起才有，旧版 Oracle 无法用数据字典补齐
            return productName != null
                    && productName.toLowerCase(Locale.ROOT).contains("oracle")
                    && metaData.getDatabaseMajorVersion() >= 12;
        } catch (SQLException ignored) {
            return false;
        }
    }

    /**
     * 读取 Oracle identity 列标记，非 identity 列保留 JDBC IS_AUTOINCREMENT 原值。
     */
    protected String resolveOracleIdentityColumn(DatabaseMetaData metaData,
                                                Map<String, Map<String, String>> identityColumnsByTable,
                                                String catalog,
                                                String schema,
                                                String tableName,
                                                String columnName,
                                                String fallbackIsAutoIncrement) throws SQLException {
        if (isBlank(tableName) || isBlank(columnName)) {
            return fallbackIsAutoIncrement;
        }
        String tableKey = metadataReadKey(catalog, schema, tableName);
        Map<String, String> identityColumns = identityColumnsByTable.get(tableKey);
        if (identityColumns == null) {
            identityColumns = readOracleIdentityColumns(metaData, schema, tableName);
            identityColumnsByTable.put(tableKey, identityColumns);
        }
        String columnKey = normalize(columnName);
        return identityColumns.containsKey(columnKey) ? identityColumns.get(columnKey) : fallbackIsAutoIncrement;
    }

    /**
     * 从 Oracle 数据字典读取指定表的 identity 列。
     */
    protected Map<String, String> readOracleIdentityColumns(DatabaseMetaData metaData, String schema, String tableName) throws SQLException {
        String schemaName = unquoteIdentifier(schema);
        String actualTableName = unquoteIdentifier(tableName);
        if (metaData == null || isBlank(actualTableName)) {
            return Collections.emptyMap();
        }
        Map<String, String> identityColumns = new LinkedHashMap<>();
        boolean useCurrentSchema = isBlank(schemaName);
        String sql = useCurrentSchema
                ? "SELECT column_name, identity_column FROM user_tab_columns WHERE table_name = ?"
                : "SELECT column_name, identity_column FROM all_tab_columns WHERE owner = ? AND table_name = ?";
        try (PreparedStatement statement = metaData.getConnection().prepareStatement(sql)) {
            if (useCurrentSchema) {
                statement.setString(1, actualTableName);
            } else {
                statement.setString(1, schemaName);
                statement.setString(2, actualTableName);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if ("YES".equalsIgnoreCase(getString(resultSet, "identity_column"))) {
                        identityColumns.put(normalize(getString(resultSet, "column_name")), "YES");
                    }
                }
            }
        }
        return identityColumns;
    }

    /**
     * SQL Server JDBC REMARKS 经常不返回扩展属性，这里用系统目录补齐列备注。
     */
    protected boolean supportsSqlServerColumnRemarks(DatabaseMetaData metaData) {
        if (metaData == null) {
            return false;
        }
        try {
            String productName = metaData.getDatabaseProductName();
            return productName != null && productName.toLowerCase(Locale.ROOT).contains("sql server");
        } catch (SQLException ignored) {
            return false;
        }
    }

    /**
     * 读取 SQL Server 列备注，扩展属性未命中时保留 JDBC REMARKS 原值。
     */
    protected String resolveSqlServerColumnRemarks(DatabaseMetaData metaData,
                                                  Map<String, Map<String, String>> columnRemarksByTable,
                                                  String catalog,
                                                  String schema,
                                                  String tableName,
                                                  String columnName,
                                                  String fallbackRemarks) throws SQLException {
        if (isBlank(tableName) || isBlank(columnName)) {
            return fallbackRemarks;
        }
        String tableKey = metadataReadKey(catalog, schema, tableName);
        Map<String, String> columnRemarks = columnRemarksByTable.get(tableKey);
        if (columnRemarks == null) {
            columnRemarks = readSqlServerColumnRemarks(metaData, schema, tableName);
            columnRemarksByTable.put(tableKey, columnRemarks);
        }
        String columnKey = normalize(columnName);
        return columnRemarks.containsKey(columnKey) ? columnRemarks.get(columnKey) : fallbackRemarks;
    }

    /**
     * 从 SQL Server 扩展属性目录读取指定表的列备注。
     */
    protected Map<String, String> readSqlServerColumnRemarks(DatabaseMetaData metaData, String schema, String tableName) throws SQLException {
        String schemaName = unquoteIdentifier(schema);
        String actualTableName = unquoteIdentifier(tableName);
        if (metaData == null || isBlank(schemaName) || isBlank(actualTableName)) {
            return Collections.emptyMap();
        }
        String sql = "SELECT c.name AS column_name, CAST(ep.value AS NVARCHAR(4000)) AS remarks "
                + "FROM sys.tables t "
                + "JOIN sys.schemas s ON s.schema_id = t.schema_id "
                + "JOIN sys.columns c ON c.object_id = t.object_id "
                + "LEFT JOIN sys.extended_properties ep ON ep.class = 1 "
                + "AND ep.major_id = t.object_id "
                + "AND ep.minor_id = c.column_id "
                + "AND ep.name = N'MS_Description' "
                + "WHERE s.name = ? AND t.name = ?";
        Map<String, String> columnRemarks = new LinkedHashMap<>();
        try (PreparedStatement statement = metaData.getConnection().prepareStatement(sql)) {
            statement.setString(1, schemaName);
            statement.setString(2, actualTableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columnRemarks.put(normalize(getString(resultSet, "column_name")),
                            getString(resultSet, "remarks"));
                }
            }
        }
        return columnRemarks;
    }

    /**
     * 按 schema 分组读取数据索引。
     */
    protected void readIndexes(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        if (shouldBatchReadSchemaMetadata(metaData, tableInfos) && readIndexesBySchema(metaData, databaseMetadata, tableInfos)) {
            return;
        }
        readIndexesByEntity(metaData, databaseMetadata, tableInfos);
    }

    /**
     * 按实体表名读取数据索引。
     */
    protected void readIndexesByEntity(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        Set<String> readKeys = new LinkedHashSet<>();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        for (TableInfo tableInfo : tableInfos) {
            for (String tableName : resolveTableNames(tableInfo)) {
                if (readIndexesByResolvedTable(metaData, databaseMetadata, tableInfo, tableName, readKeys)) {
                    continue;
                }
                if (databaseMetadata.objectType(tableInfo, tableName) == OBJECT_TABLE) {
                    readIndexes(metaData, databaseMetadata, tableInfo, tableName, readKeys, schemaAsCatalogFallback);
                }
            }
        }
    }

    /**
     * 复用表元数据命中的真实读取目标，避免索引元数据再次按 schema/table 大小写候选重复探测。
     */
    protected boolean readIndexesByResolvedTable(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata,
                                                 TableInfo tableInfo, String tableName, Set<String> readKeys) throws SQLException {
        TableMetadata tableMetadata = databaseMetadata.getTableMetadata(tableInfo, tableName);
        if (tableMetadata == null || !TABLE_TYPE.equals(tableMetadata.tableType)) {
            return false;
        }
        readIndexMetadataIfNecessary(metaData, tableMetadata.metadataCatalog, tableMetadata.metadataSchema,
                tableMetadata.metadataTableName, databaseMetadata, readKeys);
        return true;
    }

    /**
     * 按 schema 批量读取数据索引。部分驱动不支持 tableName 为 null，失败时返回 false 让调用方回退到逐表读取。
     */
    protected boolean readIndexesBySchema(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        Set<String> readKeys = new LinkedHashSet<>();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        try {
            for (String schema : schemas(tableInfos, databaseMetadata.defaultSchema)) {
                Set<String> schemaCandidates = candidates(schema);
                if (schema == null) {
                    schemaCandidates.add(null);
                }
                for (String schemaCandidate : schemaCandidates) {
                    readIndexMetadataIfNecessary(metaData, databaseMetadata.catalog, schemaCandidate, null, databaseMetadata, readKeys);
                    if (schemaCandidate != null && schemaAsCatalogFallback) {
                        readIndexMetadataIfNecessary(metaData, schemaCandidate, null, null, databaseMetadata, readKeys);
                    }
                }
            }
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    /**
     * 读取单个实体表的数据索引。
     */
    protected void readIndexes(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, TableInfo tableInfo, Set<String> readKeys) throws SQLException {
        readIndexes(metaData, databaseMetadata, tableInfo, readKeys, supportsSchemaAsCatalogFallback(metaData));
    }

    /**
     * 读取单个实体表的数据索引。
     */
    protected void readIndexes(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, TableInfo tableInfo, Set<String> readKeys, boolean schemaAsCatalogFallback) throws SQLException {
        readIndexes(metaData, databaseMetadata, tableInfo, tableInfo.getTableName(), readKeys, schemaAsCatalogFallback);
    }

    /**
     * 读取单个物理表的数据索引。
     */
    protected void readIndexes(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, TableInfo tableInfo, String tableName, Set<String> readKeys, boolean schemaAsCatalogFallback) throws SQLException {
        String schema = resolveSchema(tableInfo.getSchema(), databaseMetadata.defaultSchema);
        Set<String> schemaCandidates = candidates(schema);
        if (schema == null) {
            schemaCandidates.add(null);
        }
        Set<String> tableCandidates = candidates(tableName);
        for (String schemaCandidate : schemaCandidates) {
            for (String tableCandidate : tableCandidates) {
                readIndexMetadataIfNecessary(metaData, databaseMetadata.catalog, schemaCandidate, tableCandidate, databaseMetadata, readKeys);
                if (schemaCandidate != null && schemaAsCatalogFallback) {
                    readIndexMetadataIfNecessary(metaData, schemaCandidate, null, tableCandidate, databaseMetadata, readKeys);
                }
            }
        }
    }

    /**
     * 去重后读取数据索引。
     */
    protected void readIndexMetadataIfNecessary(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata, Set<String> readKeys) throws SQLException {
        String readKey = metadataReadKey(catalog, schema, tableName);
        if (readKeys.add(readKey)) {
            readIndexMetadata(metaData, catalog, schema, tableName, databaseMetadata);
        }
    }

    /**
     * 从 JDBC 元数据结果集中读取索引名。
     */
    protected void readIndexMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName, DatabaseMetadata databaseMetadata) throws SQLException {
        try (ResultSet resultSet = metaData.getIndexInfo(catalog, schema, tableName, false, false)) {
            while (resultSet.next()) {
                String indexName = getString(resultSet, "INDEX_NAME");
                if (!isBlank(indexName)) {
                    databaseMetadata.addIndex(
                            getString(resultSet, "TABLE_CAT"),
                            getString(resultSet, "TABLE_SCHEM"),
                            getString(resultSet, "TABLE_NAME"),
                            indexName,
                            resultSet.getBoolean("NON_UNIQUE"),
                            getString(resultSet, "COLUMN_NAME")
                    );
                }
            }
        }
    }

    /**
     * 按实体表名读取主键元数据。
     */
    protected void readPrimaryKeys(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        Set<String> readKeys = new LinkedHashSet<>();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);
        for (TableInfo tableInfo : tableInfos) {
            for (String tableName : resolveTableNames(tableInfo)) {
                if (readPrimaryKeysByResolvedTable(metaData, databaseMetadata, tableInfo, tableName, readKeys)) {
                    continue;
                }
                if (databaseMetadata.objectType(tableInfo, tableName) == OBJECT_TABLE) {
                    readPrimaryKeys(metaData, databaseMetadata, tableInfo, tableName, readKeys, schemaAsCatalogFallback);
                }
            }
        }
    }

    /**
     * 复用表元数据命中的真实读取目标，避免主键元数据再次按 schema/table 大小写候选重复探测。
     */
    protected boolean readPrimaryKeysByResolvedTable(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata,
                                                     TableInfo tableInfo, String tableName, Set<String> readKeys) throws SQLException {
        TableMetadata tableMetadata = databaseMetadata.getTableMetadata(tableInfo, tableName);
        if (tableMetadata == null || !TABLE_TYPE.equals(tableMetadata.tableType)) {
            return false;
        }
        readPrimaryKeyMetadataIfNecessary(metaData, tableMetadata.metadataCatalog, tableMetadata.metadataSchema,
                tableMetadata.metadataTableName, databaseMetadata, readKeys);
        return true;
    }

    /**
     * 读取单个实体表的主键元数据。
     */
    protected void readPrimaryKeys(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, TableInfo tableInfo, String tableName,
                                   Set<String> readKeys, boolean schemaAsCatalogFallback) throws SQLException {
        String schema = resolveSchema(tableInfo.getSchema(), databaseMetadata.defaultSchema);
        Set<String> schemaCandidates = candidates(schema);
        if (schema == null) {
            schemaCandidates.add(null);
        }
        Set<String> tableCandidates = candidates(tableName);
        for (String schemaCandidate : schemaCandidates) {
            for (String tableCandidate : tableCandidates) {
                readPrimaryKeyMetadataIfNecessary(metaData, databaseMetadata.catalog, schemaCandidate, tableCandidate, databaseMetadata, readKeys);
                if (schemaCandidate != null && schemaAsCatalogFallback) {
                    readPrimaryKeyMetadataIfNecessary(metaData, schemaCandidate, null, tableCandidate, databaseMetadata, readKeys);
                }
            }
        }
    }

    /**
     * 去重后读取主键元数据。
     */
    protected void readPrimaryKeyMetadataIfNecessary(DatabaseMetaData metaData, String catalog, String schema, String tableName,
                                                     DatabaseMetadata databaseMetadata, Set<String> readKeys) throws SQLException {
        String readKey = metadataReadKey(catalog, schema, tableName);
        if (readKeys.add(readKey)) {
            readPrimaryKeyMetadata(metaData, catalog, schema, tableName, databaseMetadata);
        }
    }

    /**
     * 从 JDBC 元数据结果集中读取主键。
     */
    protected void readPrimaryKeyMetadata(DatabaseMetaData metaData, String catalog, String schema, String tableName,
                                          DatabaseMetadata databaseMetadata) throws SQLException {
        try (ResultSet resultSet = metaData.getPrimaryKeys(catalog, schema, tableName)) {
            while (resultSet.next()) {
                databaseMetadata.addPrimaryKey(
                        getString(resultSet, "TABLE_CAT"),
                        getString(resultSet, "TABLE_SCHEM"),
                        getString(resultSet, "TABLE_NAME"),
                        getString(resultSet, "PK_NAME"),
                        getString(resultSet, "COLUMN_NAME")
                );
            }
        }
    }

    /**
     * 元数据读取去重 key。
     */
    protected String metadataReadKey(String catalog, String schema, String tableName) {
        return valueKey(catalog) + "|" + valueKey(schema) + "|" + valueKey(tableName);
    }

    private String valueKey(String value) {
        return value == null ? "<null>" : value;
    }

    /**
     * 判断实体对应的数据表是否已经存在。
     * <p>
     * 不同 JDBC 驱动对 catalog、schema、大小写的处理差异较大，因此这里会尝试多组候选值。
     *
     * @param connection  数据库连接
     * @param entityClass xbatis 实体类
     * @return 表存在返回 true
     * @throws SQLException 元数据读取失败时抛出
     */
    protected boolean tableExists(Connection connection, Class<?> entityClass) throws SQLException {
        return tableExists(connection, Tables.get(entityClass));
    }

    /**
     * 判断实体对应的数据表是否已经存在。
     */
    protected boolean tableExists(Connection connection, TableInfo tableInfo) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String schemaValue = resolveSchema(tableInfo.getSchema(), getSchema(connection));
        Set<String> schemaCandidates = candidates(schemaValue);
        if (schemaValue == null) {
            schemaCandidates.add(null);
        }
        String catalog = connection.getCatalog();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);

        for (String physicalTableName : resolveTableNames(tableInfo)) {
            Set<String> tableCandidates = candidates(physicalTableName);
            for (String schema : schemaCandidates) {
                for (String tableName : tableCandidates) {
                    if (tableExists(metaData, catalog, schema, tableName, TABLE_TYPES)) {
                        return true;
                    }
                    if (schema != null && schemaAsCatalogFallback && tableExists(metaData, schema, null, tableName, TABLE_TYPES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 对连接感知的 CREATE TABLE SQL 列表过滤已存在序列。
     */
    protected void replaceSequenceSqlList(IDbType dbType, TableInfo tableInfo, DatabaseMetadata databaseMetadata, List<String> sqlList) {
        replaceSequenceSqlList(dbType, entityMetadata(dbType, tableInfo), databaseMetadata, sqlList);
    }

    /**
     * 对连接感知的 CREATE TABLE SQL 列表过滤已存在序列。
     */
    protected void replaceSequenceSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, DatabaseMetadata databaseMetadata, List<String> sqlList) {
        List<SequenceInfo> sequences = entityMetadata.getSequences();
        if (sequences.isEmpty()) {
            return;
        }
        removeSequenceSqlList(dbType, entityMetadata, sqlList);
        sqlList.addAll(0, createAddSequenceSqlList(dbType, entityMetadata.getTableInfo(), sequences, databaseMetadata));
    }

    /**
     * 移除建表 SQL 列表中由构建器生成的实体级序列 SQL。
     */
    protected void removeSequenceSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, List<String> sqlList) {
        List<SequenceInfo> sequences = entityMetadata.getSequences();
        if (sequences.isEmpty()) {
            return;
        }
        List<String> sequenceSqlList = ddlBuilder.createSequenceSqlList(dbType, sequences);
        if (!sequenceSqlList.isEmpty()) {
            sqlList.removeAll(sequenceSqlList);
        }
    }

    /**
     * 为数据库中不存在的实体序列生成 CREATE SEQUENCE SQL。
     */
    protected List<String> createAddSequenceSqlList(IDbType dbType, TableInfo tableInfo, DatabaseMetadata databaseMetadata) {
        return createAddSequenceSqlList(dbType, tableInfo, ddlBuilder.getSequences(dbType, tableInfo), databaseMetadata);
    }

    /**
     * 为数据库中不存在的实体序列生成 CREATE SEQUENCE SQL。
     */
    protected List<String> createAddSequenceSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, DatabaseMetadata databaseMetadata) {
        return createAddSequenceSqlList(dbType, entityMetadata.getTableInfo(), entityMetadata.getSequences(), databaseMetadata);
    }

    /**
     * 为数据库中不存在的实体序列生成 CREATE SEQUENCE SQL。
     */
    protected List<String> createAddSequenceSqlList(IDbType dbType, TableInfo tableInfo, Collection<SequenceInfo> sequences, DatabaseMetadata databaseMetadata) {
        if (sequences.isEmpty()) {
            return Collections.emptyList();
        }
        List<SequenceInfo> missingSequences = new ArrayList<>();
        for (SequenceInfo sequence : sequences) {
            if (databaseMetadata == null || !databaseMetadata.sequenceExists(tableInfo, sequence)) {
                missingSequences.add(sequence);
            }
        }
        if (missingSequences.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sqlList = ddlBuilder.createSequenceSqlList(dbType, missingSequences);
        if (databaseMetadata != null) {
            databaseMetadata.addSequences(tableInfo, missingSequences);
        }
        return sqlList;
    }

    /**
     * 为数据库中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, Connection connection, Class<?> entityClass) throws SQLException {
        return createAddColumnSqlList(dbType, connection, Tables.get(entityClass));
    }

    /**
     * 为数据库中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, Connection connection, TableInfo tableInfo) throws SQLException {
        Set<String> existsColumnNames = getExistsColumnNames(connection, tableInfo);
        return createAddColumnSqlList(dbType, tableInfo, existsColumnNames);
    }

    /**
     * 为数据库中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, TableInfo tableInfo, DatabaseMetadata databaseMetadata) {
        return createAddColumnSqlList(dbType, entityMetadata(dbType, tableInfo), databaseMetadata);
    }

    /**
     * 为数据库中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, DatabaseMetadata databaseMetadata) {
        return createAddColumnSqlList(dbType, entityMetadata, entityMetadata.getTableInfo().getTableName(), databaseMetadata);
    }

    /**
     * 为指定物理表中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, String tableName, DatabaseMetadata databaseMetadata) {
        return createAddColumnSqlList(dbType, entityMetadata, tableName,
                databaseMetadata.getColumnNames(entityMetadata.getTableInfo(), tableName), databaseMetadata);
    }

    /**
     * 为数据库中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, TableInfo tableInfo, Set<String> existsColumnNames) {
        return createAddColumnSqlList(dbType, tableInfo, existsColumnNames, null);
    }

    /**
     * 为数据库中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, TableInfo tableInfo, Set<String> existsColumnNames, DatabaseMetadata databaseMetadata) {
        return createAddColumnSqlList(dbType, entityMetadata(dbType, tableInfo), existsColumnNames, databaseMetadata);
    }

    /**
     * 为数据库中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, Set<String> existsColumnNames, DatabaseMetadata databaseMetadata) {
        return createAddColumnSqlList(dbType, entityMetadata, entityMetadata.getTableInfo().getTableName(), existsColumnNames, databaseMetadata);
    }

    /**
     * 为指定物理表中不存在的实体字段生成 ADD COLUMN SQL。
     */
    protected List<String> createAddColumnSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, String tableName, Set<String> existsColumnNames, DatabaseMetadata databaseMetadata) {
        TableInfo tableInfo = entityMetadata.getTableInfo();
        List<ColumnInfo> columns = entityMetadata.getColumns();
        if (columns.isEmpty()) {
            return Collections.emptyList();
        }
        MetadataNameIndex existsColumnNameIndex = metadataNameIndex(existsColumnNames);
        List<ColumnInfo> missingColumns = new ArrayList<>();
        for (ColumnInfo column : columns) {
            if (!existsColumnNameIndex.contains(column.getName())) {
                missingColumns.add(column);
            }
        }
        if (missingColumns.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sqlList = ddlBuilder.addColumnSqlList(dbType, tableInfo, missingColumns, tableName, existsColumnNames);
        if (databaseMetadata != null) {
            databaseMetadata.addColumns(tableInfo, tableName, missingColumns);
        }
        return sqlList;
    }

    /**
     * 为数据库中多余的实体字段生成 DROP COLUMN SQL。
     */
    protected List<String> createDropColumnSqlList(IDbType dbType, TableInfo tableInfo, DatabaseMetadata databaseMetadata) {
        return createDropColumnSqlList(dbType, entityMetadata(dbType, tableInfo), tableInfo.getTableName(), databaseMetadata);
    }

    /**
     * 为指定物理表中多余的实体字段生成 DROP COLUMN SQL。
     */
    protected List<String> createDropColumnSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, String tableName, DatabaseMetadata databaseMetadata) {
        TableInfo tableInfo = entityMetadata.getTableInfo();
        List<ColumnInfo> columns = entityMetadata.getColumns();
        if (columns.isEmpty() || databaseMetadata == null) {
            return Collections.emptyList();
        }
        MetadataNameIndex entityColumnNameIndex = metadataNameIndex(columnNames(columns));
        MetadataNameIndex primaryKeyColumnNameIndex = metadataNameIndex(databaseMetadata.getPrimaryKeyColumnNames(tableInfo, tableName));
        List<String> existingColumnNames = new ArrayList<>(databaseMetadata.getColumnNames(tableInfo, tableName));
        List<String> missingColumnNames = new ArrayList<>();
        for (String columnName : existingColumnNames) {
            if (primaryKeyColumnNameIndex.contains(columnName)) {
                continue;
            }
            if (!entityColumnNameIndex.contains(columnName)) {
                missingColumnNames.add(columnName);
            }
        }
        if (missingColumnNames.isEmpty()) {
            return Collections.emptyList();
        }
        for (int i = 0; i < missingColumnNames.size(); i++) {
            missingColumnNames.set(i, normalize(missingColumnNames.get(i)));
        }
        return ddlBuilder.dropColumnSqlList(dbType, tableInfo, missingColumnNames, tableName);
    }

    /**
     * 为数据库中定义发生变化的实体字段生成 MODIFY COLUMN SQL。
     */
    protected List<String> createModifyColumnSqlList(IDbType dbType, TableInfo tableInfo, DatabaseMetadata databaseMetadata) {
        return createModifyColumnSqlList(dbType, entityMetadata(dbType, tableInfo), tableInfo.getTableName(), databaseMetadata);
    }

    /**
     * 为指定物理表中定义发生变化的实体字段生成 MODIFY COLUMN SQL。
     */
    protected List<String> createModifyColumnSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, String tableName, DatabaseMetadata databaseMetadata) {
        TableInfo tableInfo = entityMetadata.getTableInfo();
        List<ColumnInfo> columns = entityMetadata.getColumns();
        if (columns.isEmpty() || databaseMetadata == null) {
            return Collections.emptyList();
        }
        int idColumnCount = entityIdColumnCount(columns);
        if (!dialect.supportsModifyColumn(dbType)) {
            List<ColumnInfo> autoIncrementModifiedColumns = createAutoIncrementModifiedColumnList(dbType,
                    tableInfo, columns, tableName, databaseMetadata, idColumnCount);
            if (autoIncrementModifiedColumns.isEmpty()) {
                return Collections.emptyList();
            }
            if (dialect.supportsSeparatedModifyAutoIncrement(dbType)) {
                return ddlBuilder.modifyColumnAutoIncrementSqlList(dbType, tableInfo, autoIncrementModifiedColumns, tableName);
            }
            throw new UnsupportedOperationException(dbType.getName() + " does not support MODIFY AUTO_INCREMENT");
        }
        List<ColumnInfo> modifiedColumns = new ArrayList<>();
        List<ColumnInfo> defaultModifiedColumns = new ArrayList<>();
        List<ColumnInfo> commentModifiedColumns = new ArrayList<>();
        List<ColumnInfo> autoIncrementModifiedColumns = new ArrayList<>();
        for (ColumnInfo column : columns) {
            ColumnMetadata columnMetadata = databaseMetadata.getColumnMetadata(tableInfo, tableName, column.getName());
            if (columnMetadata == null) {
                continue;
            }
            boolean typeChanged = columnTypeChanged(dbType, column, columnMetadata, databaseMetadata);
            boolean defaultChanged = columnDefaultChanged(column, columnMetadata);
            boolean commentChanged = columnCommentChanged(column, columnMetadata);
            boolean autoIncrementChanged = columnAutoIncrementChanged(column, columnMetadata, idColumnCount);
            if (autoIncrementChanged && !dialect.supportsModifyAutoIncrement(dbType)) {
                throw new UnsupportedOperationException(dbType.getName() + " does not support MODIFY AUTO_INCREMENT");
            }
            boolean inlineAutoIncrementChanged = autoIncrementChanged && dialect.supportsInlineModifyAutoIncrement(dbType);
            if (typeChanged || inlineAutoIncrementChanged || (dialect.isMysql(dbType) && commentChanged)) {
                modifiedColumns.add(column);
            }
            if (defaultChanged) {
                defaultModifiedColumns.add(column);
            }
            if (autoIncrementChanged && dialect.supportsSeparatedModifyAutoIncrement(dbType)) {
                autoIncrementModifiedColumns.add(column);
            }
            if (commentChanged && !dialect.isMysql(dbType)) {
                commentModifiedColumns.add(column);
            }
        }
        List<String> sqlList = new ArrayList<>();
        if (!modifiedColumns.isEmpty()) {
            sqlList.addAll(ddlBuilder.modifyColumnSqlList(dbType, tableInfo, modifiedColumns, tableName));
        }
        if (!defaultModifiedColumns.isEmpty()) {
            sqlList.addAll(ddlBuilder.modifyColumnDefaultSqlList(dbType, tableInfo, defaultModifiedColumns, tableName));
        }
        if (!autoIncrementModifiedColumns.isEmpty()) {
            sqlList.addAll(ddlBuilder.modifyColumnAutoIncrementSqlList(dbType, tableInfo, autoIncrementModifiedColumns, tableName));
        }
        if (!commentModifiedColumns.isEmpty()) {
            sqlList.addAll(createModifyColumnCommentSqlList(dbType, entityMetadata, tableName, databaseMetadata, commentModifiedColumns));
        }
        if (databaseMetadata != null) {
            databaseMetadata.addColumns(tableInfo, tableName, modifiedColumns);
        }
        return sqlList;
    }

    /**
     * 找出 JDBC 元数据中自增策略和实体定义不一致的列。
     */
    protected List<ColumnInfo> createAutoIncrementModifiedColumnList(IDbType dbType,
                                                                     TableInfo tableInfo,
                                                                     Collection<ColumnInfo> columns,
                                                                     String tableName,
                                                                     DatabaseMetadata databaseMetadata,
                                                                     int idColumnCount) {
        List<ColumnInfo> autoIncrementModifiedColumns = new ArrayList<>();
        for (ColumnInfo column : columns) {
            ColumnMetadata columnMetadata = databaseMetadata.getColumnMetadata(tableInfo, tableName, column.getName());
            if (columnMetadata == null || !columnAutoIncrementChanged(column, columnMetadata, idColumnCount)) {
                continue;
            }
            if (!dialect.supportsModifyAutoIncrement(dbType)) {
                throw new UnsupportedOperationException(dbType.getName() + " does not support MODIFY AUTO_INCREMENT");
            }
            autoIncrementModifiedColumns.add(column);
        }
        return autoIncrementModifiedColumns;
    }

    /**
     * 为数据库中备注发生变化的实体字段生成 COMMENT SQL。
     */
    protected List<String> createModifyColumnCommentSqlList(IDbType dbType,
                                                            EntityDDLMetadata entityMetadata,
                                                            String tableName,
                                                            DatabaseMetadata databaseMetadata,
                                                            Collection<ColumnInfo> columns) {
        if (columns.isEmpty() || databaseMetadata == null || !dialect.supportsColumnCommentStatement(dbType) || dialect.isMysql(dbType)) {
            return Collections.emptyList();
        }
        TableInfo tableInfo = entityMetadata.getTableInfo();
        if (dbType == DbType.SQL_SERVER) {
            return createSqlServerModifyColumnCommentSqlList(tableInfo, tableName, columns, databaseMetadata);
        }
        return ddlBuilder.columnCommentSqlList(dbType, tableInfo, columns, tableName);
    }

    /**
     * 为 SQL Server 备注修改生成 SQL。
     */
    protected List<String> createSqlServerModifyColumnCommentSqlList(TableInfo tableInfo,
                                                                    String tableName,
                                                                    Collection<ColumnInfo> columns,
                                                                    DatabaseMetadata databaseMetadata) {
        List<String> sqlList = new ArrayList<>();
        for (ColumnInfo column : columns) {
            ColumnMetadata columnMetadata = databaseMetadata.getColumnMetadata(tableInfo, tableName, column.getName());
            if (columnMetadata == null) {
                continue;
            }
            String comment = normalizeComment(column.getDefinition().comment());
            if (isBlank(comment)) {
                continue;
            }
            sqlList.add(buildSqlServerColumnCommentSql(tableInfo, tableName, column, comment, !isBlank(columnMetadata.getRemarks())));
        }
        return sqlList;
    }

    /**
     * 判断列的类型定义是否发生变化。
     */
    protected boolean columnTypeChanged(IDbType dbType, ColumnInfo column, ColumnMetadata columnMetadata, DatabaseMetadata databaseMetadata) {
        ColumnTypeProbeResult probeResult = databaseMetadata == null ? null : databaseMetadata.getColumnTypeProbeResult();
        if (probeResult == null) {
            throw new IllegalStateException("SYNC column type comparison requires column type probe metadata");
        }
        String expectedTypeSignature = buildExpectedColumnTypeSignature(dbType, column);
        String expectedTypeFamilyKey = columnTypeFamilyKey(dbType, expectedTypeSignature);
        ColumnMetadata probeColumnMetadata = probeResult.getColumnMetadata(expectedTypeFamilyKey);
        if (probeColumnMetadata == null) {
            throw new IllegalStateException("Missing SYNC column type probe metadata for type family: " + expectedTypeFamilyKey);
        }
        String expectedActualTypeFamilyKey = columnTypeFamilyKey(dbType, probeColumnMetadata);
        String actualTypeFamilyKey = columnTypeFamilyKey(dbType, columnMetadata);
        if (!expectedActualTypeFamilyKey.equals(actualTypeFamilyKey)) {
            return true;
        }
        return columnTypeParametersChanged(dbType, expectedTypeSignature, columnMetadata);
    }

    /**
     * 判断列的长度、精度或小数位是否发生变化。类型族本身由探测表结果判断。
     */
    protected boolean columnTypeParametersChanged(IDbType dbType, String expectedTypeSignature, ColumnMetadata columnMetadata) {
        String expectedType = normalizeColumnTypeSignature(dbType, expectedTypeSignature);
        String actualType = normalizeColumnTypeSignature(dbType, buildActualColumnTypeSignature(dbType, columnMetadata));
        String expectedTypeFamilyKey = columnTypeFamilyKey(dbType, expectedType);
        if (isPrecisionScaleType(expectedTypeFamilyKey)) {
            return !columnTypeParameters(expectedType).equals(columnTypeParameters(actualType));
        }
        if (isLengthType(expectedTypeFamilyKey)) {
            return !columnTypeParameters(expectedType).equals(columnTypeParameters(actualType));
        }
        return false;
    }

    /**
     * 判断列的自增策略是否发生变化。
     */
    protected boolean columnAutoIncrementChanged(ColumnInfo column, ColumnMetadata columnMetadata, int idColumnCount) {
        Boolean actualAutoIncrement = actualAutoIncrement(columnMetadata);
        if (actualAutoIncrement == null) {
            return false;
        }
        return expectedAutoIncrement(column, idColumnCount) != actualAutoIncrement;
    }

    /**
     * 判断实体列是否预期为数据库自增主键。
     */
    protected boolean expectedAutoIncrement(ColumnInfo column, int idColumnCount) {
        return idColumnCount == 1 && column.isId() && column.getIdAutoType() == IdAutoType.AUTO;
    }

    /**
     * 读取 JDBC 元数据中的自增标识，未知值不参与对比。
     */
    protected Boolean actualAutoIncrement(ColumnMetadata columnMetadata) {
        if (columnMetadata == null || isBlank(columnMetadata.getIsAutoIncrement())) {
            return null;
        }
        String value = columnMetadata.getIsAutoIncrement().trim();
        if ("YES".equalsIgnoreCase(value) || "TRUE".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("NO".equalsIgnoreCase(value) || "FALSE".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 统计实体主键字段数量。
     */
    protected int entityIdColumnCount(Collection<ColumnInfo> columns) {
        int count = 0;
        for (ColumnInfo column : columns) {
            if (column.isId()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断列备注是否发生变化。
     */
    protected boolean columnCommentChanged(ColumnInfo column, ColumnMetadata columnMetadata) {
        String expectedComment = normalizeComment(column.getDefinition().comment());
        if (isBlank(expectedComment)) {
            return false;
        }
        String actualComment = normalizeComment(columnMetadata.getRemarks());
        return !expectedComment.equals(actualComment);
    }

    /**
     * 判断列默认值是否发生变化。自增主键列的默认值由数据库自增机制管理，不参与默认值对比。
     */
    protected boolean columnDefaultChanged(ColumnInfo column, ColumnMetadata columnMetadata) {
        if (column.isId()) {
            return false;
        }
        String expectedDefault = normalizeDefaultValue(column.getDefinition().defaultValue());
        String actualDefault = normalizeDefaultValue(columnMetadata.getColumnDefault());
        if (expectedDefault.equals(actualDefault)) {
            return false;
        }
        if (expectedDefault.equals(actualDefault.replaceAll("[\\(|\\)]", ""))) {
            return false;
        }
        //兼容浮点默认值
        if (Number.class.isAssignableFrom(column.getJavaType())) {
            try {
                if (new BigDecimal(expectedDefault).compareTo(new BigDecimal(actualDefault.replaceAll("[\\(|\\)]", ""))) == 0) {
                    return false;
                }
            } catch (Exception e) {

            }
        }
        return !isEquivalentDynamicDefault(column, column.getDefinition().defaultValue(), columnMetadata.getColumnDefault());
    }

    /**
     * 当前时间类动态默认值（CURRENT_DATE/CURRENT_TIME/CURRENT_TIMESTAMP）语义等价兜底比较。
     * <p>
     * 各数据库（尤其 openGauss/PostgreSQL）对同一动态默认值的存储文本差异很大，
     * 例如 CURRENT_DATE 可能存储为 ('now'::text)::date、'now'::date、now()::date、CURRENT_DATE 等，
     * 无法穷举所有文本形态。此处对“双方都是动态当前时间表达式”的情况按语义类别比较，
     * 避免把同一个默认值误判为变更而反复生成 DDL。
     */
    private boolean isEquivalentDynamicDefault(ColumnInfo column, String expectedRaw, String actualRaw) {
        if (isBlank(expectedRaw) || isBlank(actualRaw)) {
            return false;
        }
        String expected = expectedRaw.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String actual = actualRaw.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (!isDynamicNowDefault(expected) || !isDynamicNowDefault(actual)) {
            return false;
        }
        String expectedKind = dynamicNowDefaultKind(expected);
        String actualKind = dynamicNowDefaultKind(actual);
        if ("OTHER".equals(expectedKind) || "OTHER".equals(actualKind)) {
            return false;
        }
        if (expectedKind.equals(actualKind)) {
            return true;
        }
        Class<?> javaType = column.getJavaType();
        if (javaType == Boolean.class || javaType == boolean.class) {
            if (expectedRaw.equals("1") && actualRaw.equalsIgnoreCase("TRUE")) {
                return false;
            } else if (expectedRaw.equals("0") && actualRaw.equalsIgnoreCase("FALSE")) {
                return false;
            }
        }

        // 时间戳表达式在 date/time 类型列上会被隐式转换为当前日期/时间，与 CURRENT_DATE/CURRENT_TIME 语义一致

        boolean dateOnlyColumn = javaType == LocalDate.class || javaType == java.sql.Date.class;
        boolean timeOnlyColumn = javaType == LocalTime.class || javaType == Time.class;
        if (dateOnlyColumn || timeOnlyColumn){
            if (expectedRaw.equals("{NOW}") && (actualRaw.contains("TIME") || actualRaw.contains("NOW") || actualRaw.contains("DATE"))){
                return false;
            }
        }
        return ("DATE".equals(expectedKind) && "TIMESTAMP".equals(actualKind) && dateOnlyColumn)
                || ("TIME".equals(expectedKind) && "TIMESTAMP".equals(actualKind) && timeOnlyColumn);
    }

    private boolean isDynamicNowDefault(String raw) {
        return raw.contains("TEXT_DATE") || raw.contains("TEXT_TIME") || raw.contains("TEXT_TIMESTAMP")
                || raw.contains("'NOW'") || raw.contains("NOW()")
                || raw.contains("PG_SYSTIMESTAMP") || raw.contains("SYSTIMESTAMP")
                || raw.contains("CURRENT_TIMESTAMP") || raw.contains("CURRENT TIMESTAMP")
                || raw.contains("CURRENT_DATE") || raw.contains("CURRENT DATE")
                || raw.contains("CURRENT_TIME") || raw.contains("CURRENT TIME")
                || raw.contains("LOCALTIMESTAMP") || raw.contains("LOCALTIME")
                || raw.contains("SYSDATE") || raw.contains("CURDATE") || raw.contains("CURTIME")
                || raw.contains("GETDATE") || raw.contains("SYSDATETIME") || raw.contains("TODAY()");
    }

    private String dynamicNowDefaultKind(String raw) {
        if (raw.matches(".*::\\s*TIMESTAMP\\b.*") || raw.contains("AS TIMESTAMP")
                || raw.contains("TEXT_TIMESTAMPTZ") || raw.contains("TEXT_TIMESTAMP")) {
            return "TIMESTAMP";
        }
        if (raw.matches(".*::\\s*DATE\\b.*") || raw.contains("AS DATE")
                || raw.contains("CURRENT_DATE") || raw.contains("CURRENT DATE")
                || raw.contains("CURDATE") || raw.contains("TODAY()") || raw.contains("TEXT_DATE")) {
            return "DATE";
        }
        if (raw.matches(".*::\\s*TIME\\b.*") || raw.contains("AS TIME")) {
            return "TIME";
        }
        // 注意 CURRENT_TIMESTAMP 包含 CURRENT_TIME 前缀、LOCALTIMESTAMP 包含 LOCALTIME 前缀，必须先判断时间戳关键字
        if (raw.contains("CURRENT_TIMESTAMP") || raw.contains("CURRENT TIMESTAMP")
                || raw.contains("LOCALTIMESTAMP") || raw.contains("NOW()")
                || raw.contains("PG_SYSTIMESTAMP") || raw.contains("SYSTIMESTAMP")
                || raw.contains("SYSDATE") || raw.contains("GETDATE") || raw.contains("SYSDATETIME")) {
            return "TIMESTAMP";
        }
        if (raw.contains("CURRENT_TIME") || raw.contains("CURRENT TIME")
                || raw.contains("CURTIME") || raw.contains("LOCALTIME") || raw.contains("TEXT_TIME")) {
            return "TIME";
        }
        return "OTHER";
    }

    /**
     * 统一实体和数据库列默认值格式，便于比较。
     */
    protected String normalizeDefaultValue(String defaultValue) {
        if (isBlank(defaultValue)) {
            return "";
        }
        String normalized = defaultValue.trim()
                .toUpperCase(Locale.ROOT)
                // MySQL/SQL Server 默认表达式带外层括号
                .replaceAll("^\\((.*)\\)$", "$1")
                // SQL Server 字符串默认值带 N 前缀
                .replaceAll("^N'", "'")
                // openGauss/PostgreSQL 将 CURRENT_TIMESTAMP(n)/LOCALTIMESTAMP 存储为 ('now'::text)::timestamp[(n)] [with|without] time zone，
                // 也存在 now()/pg_systimestamp()/'now'::text 带 ::timestamp 强转的变体，统一为 CURRENT_TIMESTAMP
                .replaceAll("(?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"]::TEXT|\\(['\"]NOW['\"]::TEXT\\)|['\"]NOW['\"]|\\(['\"]NOW['\"]\\))\\s*::\\s*TIMESTAMP(?:\\(\\d+\\))?(?:\\s+(?:WITH|WITHOUT)\\s+TIME\\s+ZONE)?", "CURRENT_TIMESTAMP")
                // CURRENT_DATE 存储为 ('now'::text)::date / now()::date / pg_systimestamp()::date / 'now'::date 等
                .replaceAll("(?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"]::TEXT|\\(['\"]NOW['\"]::TEXT\\)|['\"]NOW['\"]|\\(['\"]NOW['\"]\\))\\s*::\\s*DATE", "CURRENT_DATE")
                // CURRENT_TIME(n) 存储为 ('now'::text)::time[(n)] [with|without] time zone / now()::time / 'now'::time 等
                .replaceAll("(?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"]::TEXT|\\(['\"]NOW['\"]::TEXT\\)|['\"]NOW['\"]|\\(['\"]NOW['\"]\\))\\s*::\\s*TIME(?:\\(\\d+\\))?(?:\\s+(?:WITH|WITHOUT)\\s+TIME\\s+ZONE)?", "CURRENT_TIME")
                // CAST 强转形态：CAST('now' AS date) / CAST(now() AS timestamp) 等
                .replaceAll("CAST\\s*\\((?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"])\\s*AS\\s*TIMESTAMP(?:\\(\\d+\\))?(?:\\s+(?:WITH|WITHOUT)\\s+TIME\\s+ZONE)?\\)", "CURRENT_TIMESTAMP")
                .replaceAll("CAST\\s*\\((?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"])\\s*AS\\s*DATE\\)", "CURRENT_DATE")
                .replaceAll("CAST\\s*\\((?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"])\\s*AS\\s*TIME(?:\\(\\d+\\))?(?:\\s+(?:WITH|WITHOUT)\\s+TIME\\s+ZONE)?\\)", "CURRENT_TIME")
                // openGauss 将 CURRENT_DATE/CURRENT_TIME/CURRENT_TIMESTAMP 存储为 TEXT_DATE/TEXT_TIME/TEXT_TIMESTAMP('now'::text) 函数形态
                .replaceAll("TEXT_DATE\\s*\\((?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"]::TEXT|['\"]NOW['\"])\\s*\\)", "CURRENT_DATE")
                .replaceAll("TEXT_TIME\\s*\\((?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"]::TEXT|['\"]NOW['\"])\\s*\\)", "CURRENT_TIME")
                .replaceAll("TEXT_TIMESTAMPTZ\\s*\\((?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"]::TEXT|['\"]NOW['\"])\\s*\\)", "CURRENT_TIMESTAMP")
                .replaceAll("TEXT_TIMESTAMP\\s*\\((?:NOW\\(\\)|PG_SYSTIMESTAMP\\(\\)|['\"]NOW['\"]::TEXT|['\"]NOW['\"])\\s*\\)", "CURRENT_TIMESTAMP")
                // PostgreSQL 系默认值带 ::类型 转换后缀
                .replaceAll("::[\\w\\s.]+$", "")
                .replaceAll("^'(.*)'$", "$1")
                // 各数据库对时间函数默认值的表达形式不同：MySQL 返回 current_timestamp()/curdate()，
                // PostgreSQL 返回 now()，openGauss 返回 pg_systimestamp() 或 ('now'::text)::timestamp(n) with/without time zone，
                // 统一为 CURRENT_TIMESTAMP/CURRENT_DATE/CURRENT_TIME 语义
                .replaceAll("(?i)(CURRENT_TIMESTAMP|NOW|LOCALTIMESTAMP)\\(\\)", "CURRENT_TIMESTAMP")
                .replaceAll("(?i)(CURRENT_TIMESTAMP|LOCALTIMESTAMP)\\(\\d+\\)", "CURRENT_TIMESTAMP")
                .replaceAll("(?i)PG_SYSTIMESTAMP\\(\\)", "CURRENT_TIMESTAMP")
                .replaceAll("(?i)(CURRENT_DATE|CURDATE|TODAY)(?:\\(\\d*\\))?", "CURRENT_DATE")
                .replaceAll("(?i)(CURRENT_TIME|CURTIME)(?:\\(\\d*\\))?", "CURRENT_TIME")
                .replaceAll("(?i)(CURRENT_TIMESTAMP|CURRENT_DATE|CURRENT_TIME)(?:\\s+(?:WITH|WITHOUT)\\s+TIME\\s+ZONE)?", "$1")
                .replaceAll("\\s+", " ")
                .trim();
        // Oracle 移除默认值后 COLUMN_DEF 返回字符串 NULL，视为无默认值
        return "NULL".equals(normalized) ? "" : normalized;
    }

    /**
     * 生成实体列对应的预期类型签名。
     */
    protected String buildExpectedColumnTypeSignature(IDbType dbType, ColumnInfo column) {
        return buildExpectedColumnTypeSignature(dbType, column, true);
    }

    /**
     * 生成实体列对应的预期类型签名。
     */
    protected String buildExpectedColumnTypeSignature(IDbType dbType, ColumnInfo column, boolean includeAutoIncrement) {
        ColumnDefinition columnDefinition = column.getDefinition();
        if (!isBlank(columnDefinition.definition())) {
            return buildColumnDefinitionType(columnDefinition);
        }
        Class<?> type = column.getJavaType();
        if (type.isEnum()) {
            Class<?> enumCodeType = getEnumSupportCodeType(type);
            if (enumCodeType != null) {
                return columnTypeMapper.getColumnType(dbType, enumCodeType, columnDefinition, false);
            }
            return columnTypeMapper.getStringType(dbType, columnDefinition.length() > 0 ? columnDefinition.length() : 64);
        }
        boolean autoIncrement = includeAutoIncrement && column.isId() && column.getIdAutoType() == IdAutoType.AUTO;
        return columnTypeMapper.getColumnType(dbType, type, columnDefinition, autoIncrement);
    }

    /**
     * 获取 xbatis 枚举列的 code 类型。
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
     * 生成数据库列对应的实际类型签名。
     */
    protected String buildActualColumnTypeSignature(IDbType dbType, ColumnMetadata columnMetadata) {
        if (columnMetadata == null) {
            return "";
        }
        String typeName = normalizeMetadataTypeName(columnMetadata.getTypeName());
        if (isBlank(typeName)) {
            return "";
        }
        if (dialect.isMysql(dbType) && "BOOLEAN".equals(typeName)) {
            return "TINYINT(1)";
        }
        if (dialect.isMysql(dbType) && "BIT".equals(typeName) && columnMetadata.getColumnSize() == 1) {
            return "TINYINT(1)";
        }
        StringBuilder signature = new StringBuilder(typeName);
        if (isPrecisionScaleType(typeName)) {
            int precision = columnMetadata.getColumnSize();
            if (precision > 0) {
                signature.append("(").append(precision);
                int scale = columnMetadata.getDecimalDigits();
                if (scale > 0) {
                    signature.append(",").append(scale);
                }
                signature.append(")");
            }
            return signature.toString();
        }
        if (isLengthType(typeName)) {
            int size = columnMetadata.getColumnSize();
            if (size > 0 && !typeName.endsWith("(MAX)")) {
                if (isSqlServerMaxLengthMetadata(dbType, typeName, size)) {
                    signature.append("(MAX)");
                } else {
                    signature.append("(").append(size).append(")");
                }
            }
        }
        if (dialect.isMysql(dbType) && "TINYINT".equals(typeName) && columnMetadata.getColumnSize() == 1) {
            return "TINYINT(1)";
        }
        return signature.toString();
    }

    protected boolean isSqlServerMaxLengthMetadata(IDbType dbType, String typeName, int size) {
        if (dbType != DbType.SQL_SERVER || size <= 0 || isBlank(typeName)) {
            return false;
        }
        String typeFamily = columnTypeFamilyKey(dbType, typeName);
        if ("NVARCHAR".equals(typeFamily)) {
            return size > 4000;
        }
        if ("VARCHAR".equals(typeFamily) || "VARBINARY".equals(typeFamily)) {
            return size > 8000;
        }
        return false;
    }

    /**
     * 统一列备注内容格式。
     */
    protected String normalizeComment(String comment) {
        if (isBlank(comment)) {
            return "";
        }
        return comment.trim();
    }

    /**
     * 生成 SQL Server 字段注释扩展属性语句。
     */
    protected String buildSqlServerColumnCommentSql(TableInfo tableInfo, String tableName, ColumnInfo column, String comment, boolean update) {
        String schema = tableInfo.getSchema();
        StringBuilder ddl = new StringBuilder();
        if (isBlank(schema)) {
            ddl.append("DECLARE @schema sysname = SCHEMA_NAME(); ");
        }
        ddl.append("EXEC sys.sp_")
                .append(update ? "update" : "add")
                .append("extendedproperty ")
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
     * 统一实体和数据库列类型签名，便于比较。
     */
    protected String normalizeColumnTypeSignature(IDbType dbType, String typeSignature) {
        if (isBlank(typeSignature)) {
            return "";
        }
        String normalized = typeSignature.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                // Oracle JDBC 会把 TIMESTAMP 的秒精度写进类型名（如 TIMESTAMP(6)），与实体侧 TIMESTAMP 语义等价，
                // 统一后比较，避免误判类型变更；同时覆盖 TIMESTAMP(6) WITH/LOCAL/WITHOUT TIME ZONE。
                // 需在括号折叠前处理，避免 ) 后的空格被折叠导致 WITH/WITHOUT 分支失配。
                .replaceAll("^TIMESTAMP\\s*\\(\\s*\\d+\\s*\\)", "TIMESTAMP")
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\)\\s*", ")");
        if (normalized.startsWith("CHARACTER LARGE OBJECT")) {
            normalized = "CLOB" + normalized.substring("CHARACTER LARGE OBJECT".length());
        } else if (normalized.startsWith("NATIONAL CHARACTER VARYING")) {
            normalized = "NVARCHAR" + normalized.substring("NATIONAL CHARACTER VARYING".length());
        } else if (normalized.startsWith("CHARACTER VARYING")) {
            normalized = "VARCHAR" + normalized.substring("CHARACTER VARYING".length());
        } else if (normalized.startsWith("NATIONAL CHARACTER")) {
            normalized = "NCHAR" + normalized.substring("NATIONAL CHARACTER".length());
        } else if (normalized.startsWith("CHARACTER")) {
            normalized = "CHAR" + normalized.substring("CHARACTER".length());
        } else if (normalized.startsWith("NUMERIC")) {
            normalized = (dialect.isOracle(dbType) ? "NUMBER" : "DECIMAL") + normalized.substring("NUMERIC".length());
        } else if (normalized.startsWith("DECIMAL")) {
            normalized = (dialect.isOracle(dbType) ? "NUMBER" : "DECIMAL") + normalized.substring("DECIMAL".length());
        } else if (normalized.startsWith("INT8")) {
            normalized = "BIGINT" + normalized.substring("INT8".length());
        } else if (normalized.startsWith("BIGSERIAL")) {
            normalized = "BIGINT" + normalized.substring("BIGSERIAL".length());
        } else if (normalized.startsWith("INT4")) {
            normalized = "INTEGER" + normalized.substring("INT4".length());
        } else if (normalized.startsWith("SERIAL")) {
            normalized = "INTEGER" + normalized.substring("SERIAL".length());
        } else if (normalized.startsWith("INT2")) {
            normalized = "SMALLINT" + normalized.substring("INT2".length());
        } else if (normalized.startsWith("BOOLEAN")) {
            normalized = "BOOLEAN" + normalized.substring("BOOLEAN".length());
        } else if (normalized.startsWith("BOOL")) {
            normalized = "BOOLEAN" + normalized.substring("BOOL".length());
        } else if (normalized.startsWith("TIMESTAMP WITHOUT TIME ZONE")) {
            normalized = "TIMESTAMP" + normalized.substring("TIMESTAMP WITHOUT TIME ZONE".length());
        } else if (normalized.startsWith("TIMESTAMP WITH TIME ZONE")) {
            normalized = "TIMESTAMP WITH TIME ZONE" + normalized.substring("TIMESTAMP WITH TIME ZONE".length());
        } else if (normalized.startsWith("TIMESTAMPTZ")) {
            // Kingbase/PostgreSQL 系 JDBC 对 timestamptz 返回 TIMESTAMPTZ，与实体侧 TIMESTAMP WITH TIME ZONE 语义等价
            normalized = "TIMESTAMP WITH TIME ZONE" + normalized.substring("TIMESTAMPTZ".length());
        }
        return normalized;
    }

    /**
     * 将列类型签名折叠为只表达类型族的 key，长度、精度和小数位由独立逻辑继续比较。
     */
    protected String columnTypeFamilyKey(IDbType dbType, ColumnMetadata columnMetadata) {
        return columnTypeFamilyKey(dbType, buildActualColumnTypeSignature(dbType, columnMetadata));
    }

    /**
     * 将列类型签名折叠为只表达类型族的 key。
     */
    protected String columnTypeFamilyKey(IDbType dbType, String typeSignature) {
        String normalized = normalizeColumnTypeSignature(dbType, typeSignature);
        if (normalized.isEmpty()) {
            return "";
        }
        return stripColumnTypeParameters(normalized).trim();
    }

    protected String stripColumnTypeParameters(String typeSignature) {
        if (isBlank(typeSignature)) {
            return "";
        }
        int parameterStart = typeSignature.indexOf('(');
        if (parameterStart < 0) {
            return typeSignature;
        }
        int parameterEnd = typeSignature.indexOf(')', parameterStart);
        if (parameterEnd < 0) {
            return typeSignature.substring(0, parameterStart);
        }
        return typeSignature.substring(0, parameterStart) + typeSignature.substring(parameterEnd + 1);
    }

    protected String columnTypeParameters(String typeSignature) {
        if (isBlank(typeSignature)) {
            return "";
        }
        int parameterStart = typeSignature.indexOf('(');
        if (parameterStart < 0) {
            return "";
        }
        int parameterEnd = typeSignature.indexOf(')', parameterStart);
        if (parameterEnd < 0) {
            return typeSignature.substring(parameterStart);
        }
        return typeSignature.substring(parameterStart, parameterEnd + 1);
    }

    /**
     * 统一数据库类型名格式。
     */
    protected String normalizeMetadataTypeName(String typeName) {
        if (isBlank(typeName)) {
            return "";
        }
        String normalized = typeName.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.startsWith("CHARACTER LARGE OBJECT")) {
            return "CLOB";
        }
        if (normalized.startsWith("NATIONAL CHARACTER VARYING")) {
            return "NVARCHAR";
        }
        if (normalized.startsWith("CHARACTER VARYING")) {
            return "VARCHAR";
        }
        if (normalized.startsWith("NATIONAL CHARACTER")) {
            return "NCHAR";
        }
        if (normalized.startsWith("CHARACTER")) {
            return "CHAR";
        }
        if (normalized.startsWith("INT8")) {
            return "BIGINT";
        }
        if (normalized.startsWith("BIGSERIAL")) {
            return "BIGINT";
        }
        if (normalized.startsWith("INT4")) {
            return "INTEGER";
        }
        if (normalized.startsWith("SERIAL")) {
            return "INTEGER";
        }
        if (normalized.startsWith("INT2")) {
            return "SMALLINT";
        }
        if (normalized.startsWith("BOOLEAN")) {
            return "BOOLEAN";
        }
        if (normalized.startsWith("BOOL")) {
            return "BOOLEAN";
        }
        if (normalized.startsWith("TIMESTAMP WITHOUT TIME ZONE")) {
            return "TIMESTAMP";
        }
        if (normalized.startsWith("TIMESTAMPTZ")) {
            return "TIMESTAMP WITH TIME ZONE";
        }
        return normalized;
    }

    /**
     * 判断类型是否为需要长度的字符/二进制类型。
     */
    protected boolean isLengthType(String typeName) {
        String normalized = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("BINARY_FLOAT") || normalized.startsWith("BINARY_DOUBLE")) {
            return false;
        }
        return normalized.startsWith("CHAR")
                || normalized.startsWith("NCHAR")
                || normalized.startsWith("VARCHAR")
                || normalized.startsWith("NVARCHAR")
                || normalized.startsWith("VARBINARY")
                || normalized.startsWith("BINARY")
                || normalized.startsWith("LONGVARCHAR")
                || normalized.startsWith("LONGVARBINARY");
    }

    /**
     * 判断类型是否为需要精度/小数位的数值类型。
     */
    protected boolean isPrecisionScaleType(String typeName) {
        String normalized = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        return normalized.startsWith("DECIMAL")
                || normalized.startsWith("NUMBER")
                || normalized.startsWith("NUMERIC");
    }

    /**
     * 构建列定义中的类型片段。
     */
    protected String buildColumnDefinitionType(ColumnDefinition columnDefinition) {
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
     * 为数据库中不存在的实体索引生成 CREATE INDEX SQL。
     */
    protected List<String> createAddIndexSqlList(IDbType dbType, TableInfo tableInfo, DatabaseMetadata databaseMetadata) {
        return createAddIndexSqlList(dbType, entityMetadata(dbType, tableInfo), databaseMetadata);
    }

    /**
     * 为数据库中不存在的实体索引生成 CREATE INDEX SQL。
     */
    protected List<String> createAddIndexSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, DatabaseMetadata databaseMetadata) {
        return createAddIndexSqlList(dbType, entityMetadata, entityMetadata.getTableInfo().getTableName(), databaseMetadata);
    }

    /**
     * 为指定物理表中不存在的实体索引生成 CREATE INDEX SQL。
     */
    protected List<String> createAddIndexSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, String tableName, DatabaseMetadata databaseMetadata) {
        return createAddIndexSqlList(dbType, entityMetadata, tableName,
                databaseMetadata.getIndexNames(entityMetadata.getTableInfo(), tableName), databaseMetadata);
    }

    /**
     * 为数据库中不存在的实体索引生成 CREATE INDEX SQL。
     */
    protected List<String> createAddIndexSqlList(IDbType dbType, TableInfo tableInfo, Set<String> existsIndexNames, DatabaseMetadata databaseMetadata) {
        return createAddIndexSqlList(dbType, entityMetadata(dbType, tableInfo), existsIndexNames, databaseMetadata);
    }

    /**
     * 为数据库中不存在的实体索引生成 CREATE INDEX SQL。
     */
    protected List<String> createAddIndexSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, Set<String> existsIndexNames, DatabaseMetadata databaseMetadata) {
        return createAddIndexSqlList(dbType, entityMetadata, entityMetadata.getTableInfo().getTableName(), existsIndexNames, databaseMetadata);
    }

    /**
     * 为指定物理表中不存在的实体索引生成 CREATE INDEX SQL。
     */
    protected List<String> createAddIndexSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, String tableName, Set<String> existsIndexNames, DatabaseMetadata databaseMetadata) {
        TableInfo tableInfo = entityMetadata.getTableInfo();
        List<IndexInfo> indexes = ddlBuilder.resolveIndexes(dbType, tableInfo, entityMetadata.getIndexes(), tableName);
        if (indexes.isEmpty()) {
            return Collections.emptyList();
        }
        MetadataNameIndex existsIndexNameIndex = metadataNameIndex(existsIndexNames);
        List<IndexInfo> missingIndexes = new ArrayList<>();
        for (IndexInfo index : indexes) {
            if (!existsIndexNameIndex.contains(index.getName())) {
                missingIndexes.add(index);
            }
        }
        if (missingIndexes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sqlList = ddlBuilder.createIndexSqlList(dbType, tableInfo, missingIndexes, tableName);
        if (databaseMetadata != null) {
            databaseMetadata.addIndexes(tableInfo, tableName, missingIndexes);
        }
        return sqlList;
    }

    /**
     * 为数据库中多余的实体索引生成 DROP INDEX SQL。
     */
    protected List<String> createDropIndexSqlList(IDbType dbType, TableInfo tableInfo, DatabaseMetadata databaseMetadata) {
        return createDropIndexSqlList(dbType, entityMetadata(dbType, tableInfo), tableInfo.getTableName(), databaseMetadata);
    }

    /**
     * 为指定物理表中多余的实体索引生成 DROP INDEX SQL。
     */
    protected List<String> createDropIndexSqlList(IDbType dbType, EntityDDLMetadata entityMetadata, String tableName, DatabaseMetadata databaseMetadata) {
        TableInfo tableInfo = entityMetadata.getTableInfo();
        if (databaseMetadata == null) {
            return Collections.emptyList();
        }
        List<IndexInfo> indexes = ddlBuilder.resolveIndexes(dbType, tableInfo, entityMetadata.getIndexes(), tableName);
        MetadataNameIndex entityIndexNameIndex = metadataNameIndex(indexNames(indexes));
        Set<String> constraintIndexNames = databaseMetadata.getPrimaryKeyIndexNames(tableInfo, tableName);
        constraintIndexNames.addAll(databaseMetadata.getUniqueIndexNames(tableInfo, tableName));
        MetadataNameIndex constraintIndexNameIndex = metadataNameIndex(constraintIndexNames);
        List<String> existingIndexNames = new ArrayList<>(databaseMetadata.getIndexNames(tableInfo, tableName));
        List<String> missingIndexNames = new ArrayList<>();
        for (String indexName : existingIndexNames) {
            if (constraintIndexNameIndex.contains(indexName)) {
                continue;
            }
            if (!entityIndexNameIndex.contains(indexName)) {
                missingIndexNames.add(indexName);
            }
        }
        if (missingIndexNames.isEmpty()) {
            return Collections.emptyList();
        }
        for (int i = 0; i < missingIndexNames.size(); i++) {
            missingIndexNames.set(i, normalize(missingIndexNames.get(i)));
        }
        return ddlBuilder.dropIndexSqlList(dbType, tableInfo, missingIndexNames, tableName);
    }

    /**
     * 读取数据库表中已经存在的列名。
     */
    protected Set<String> getExistsColumnNames(Connection connection, Class<?> entityClass) throws SQLException {
        return getExistsColumnNames(connection, Tables.get(entityClass));
    }

    /**
     * 读取数据库表中已经存在的列名。
     */
    protected Set<String> getExistsColumnNames(Connection connection, TableInfo tableInfo) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String schemaValue = resolveSchema(tableInfo.getSchema(), getSchema(connection));
        Set<String> schemaCandidates = candidates(schemaValue);
        if (schemaValue == null) {
            schemaCandidates.add(null);
        }
        Set<String> columnNames = new LinkedHashSet<>();
        String catalog = connection.getCatalog();
        boolean schemaAsCatalogFallback = supportsSchemaAsCatalogFallback(metaData);

        for (String physicalTableName : resolveTableNames(tableInfo)) {
            Set<String> tableCandidates = candidates(physicalTableName);
            for (String schema : schemaCandidates) {
                for (String tableName : tableCandidates) {
                    readColumnNames(metaData, catalog, schema, tableName, columnNames);
                    if (schema != null && schemaAsCatalogFallback) {
                        readColumnNames(metaData, schema, null, tableName, columnNames);
                    }
                }
            }
        }
        return columnNames;
    }

    /**
     * 从 JDBC 元数据结果集中读取列名。
     */
    protected void readColumnNames(DatabaseMetaData metaData, String catalog, String schema, String tableName, Set<String> columnNames) throws SQLException {
        try (ResultSet resultSet = metaData.getColumns(catalog, schema, tableName, null)) {
            while (resultSet.next()) {
                if (matchesMetadataRow(catalog, schema, tableName, resultSet)) {
                    columnNames.add(resultSet.getString("COLUMN_NAME"));
                }
            }
        }
    }

    /**
     * 使用 JDBC 元数据查询表是否存在。
     */
    protected boolean tableExists(DatabaseMetaData metaData, String catalog, String schema, String tableName, String[] types) throws SQLException {
        try (ResultSet resultSet = metaData.getTables(catalog, schema, tableName, types)) {
            while (resultSet.next()) {
                if (matchesMetadataRow(catalog, schema, tableName, resultSet)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 部分数据库驱动把 schema 放在 catalog 位置返回；明确使用 schema 的数据库不需要额外按 catalog 重查。
     */
    protected boolean supportsSchemaAsCatalogFallback(DatabaseMetaData metaData) throws SQLException {
        if (metaData == null) {
            return true;
        }
        String productName = metaData.getDatabaseProductName();
        if (productName == null) {
            return true;
        }
        String normalizedProductName = productName.toLowerCase(Locale.ROOT);
        return !normalizedProductName.contains("sql server")
                && !normalizedProductName.contains("postgresql")
                && !normalizedProductName.contains("opengauss")
                && !normalizedProductName.contains("kingbase")
                && !normalizedProductName.contains("highgo");
    }

    /**
     * 批量执行非空 SQL。
     */
    protected void executeSql(Statement statement, Collection<String> sqlList) throws SQLException {
        executeSql(null, statement, sqlList);
    }

    /**
     * 按数据库方言批量执行非空 SQL。
     */
    protected void executeSql(IDbType dbType, Statement statement, Collection<String> sqlList) throws SQLException {
        for (String sql : sqlList) {
            if (sql == null) {
                continue;
            }
            String trimmedSql = sql.trim();
            if (!trimmedSql.isEmpty()) {
                executeSql(dbType, statement, trimmedSql);
            }
        }
    }

    /**
     * 执行单条 SQL 并记录执行结果。
     */
    protected void executeSql(Statement statement, String sql) throws SQLException {
        executeSql(null, statement, sql);
    }

    /**
     * 按数据库方言执行单条 SQL 并记录执行结果。
     */
    protected void executeSql(IDbType dbType, Statement statement, String sql) throws SQLException {
        boolean hasExecutionListener = hasExecutionListener();
        if (hasExecutionListener) {
            executionListener.beforeExecute(sql, getExecutedSqlList());
        }
        String executableSql = executableSql(dbType, sql);
        try {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Executing DDL SQL: {}", executableSql);
            }
            statement.execute(executableSql);
            executedSqlList.add(sql);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Executed DDL SQL: {}", executableSql);
            }
            if (hasExecutionListener) {
                executionListener.afterExecute(sql, getExecutedSqlList());
            }
        } catch (SQLException exception) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed to execute DDL SQL: {}", executableSql, exception);
            }
            if (hasExecutionListener) {
                notifyExecuteError(sql, exception);
            }
            throw enrichSqlException(sql, exception);
        }
    }

    /**
     * 默认空监听器不需要执行回调，也不需要创建执行列表快照。
     */
    protected boolean hasExecutionListener() {
        return executionListener != DDLExecutionListener.NONE;
    }

    /**
     * JDBC Statement 执行时 DB2 不接受 SQL 末尾的语句分隔符。
     */
    protected String executableSql(IDbType dbType, String sql) {
        if (dbType == DbType.DB2 && sql.endsWith(";")) {
            return sql.substring(0, sql.length() - 1);
        }
        return sql;
    }

    /**
     * 包装 SQL 执行异常，保留当前失败 SQL 和失败前已成功执行的 SQL。
     */
    protected SQLException enrichSqlException(String sql, SQLException exception) {
        String separator = sql.endsWith(";") ? " " : "; ";
        return new SQLException(
                "Failed to execute DDL SQL: " + sql + separator,
                exception.getSQLState(),
                exception.getErrorCode(),
                exception
        );
    }

    /**
     * 通知执行失败，监听器自身异常不覆盖原始 SQL 异常。
     */
    protected void notifyExecuteError(String sql, SQLException exception) {
        try {
            executionListener.onExecuteError(sql, exception, getExecutedSqlList());
        } catch (RuntimeException listenerException) {
            exception.addSuppressed(listenerException);
        }
    }

    /**
     * 生成原值、大写、小写三种候选值，兼容不同数据库和驱动的大小写策略。
     */
    protected Set<String> candidates(String value) {
        return metadataNameMatcher.candidates(value);
    }

    /**
     * 统一列名大小写，保证对比时不受数据库大小写策略影响。
     */
    protected String normalize(String value) {
        return metadataNameMatcher.normalize(value);
    }

    /**
     * 判断已读取的元数据名称集合中是否包含目标名称。
     */
    protected boolean containsMetadataName(Collection<String> actualNames, String expectedName) {
        return metadataNameMatcher.containsMetadataName(actualNames, expectedName);
    }

    /**
     * 构建元数据名称索引，批量字段对比时避免逐列扫描。
     */
    protected MetadataNameIndex metadataNameIndex(Collection<String> actualNames) {
        return new MetadataNameIndex(actualNames);
    }

    /**
     * 提取实体列名集合。
     */
    protected List<String> columnNames(Collection<ColumnInfo> columns) {
        List<String> columnNames = new ArrayList<>(columns.size());
        for (ColumnInfo column : columns) {
            columnNames.add(column.getName());
        }
        return columnNames;
    }

    /**
     * 提取实体索引名集合。
     */
    protected List<String> indexNames(Collection<IndexInfo> indexes) {
        List<String> indexNames = new ArrayList<>(indexes.size());
        for (IndexInfo index : indexes) {
            indexNames.add(index.getName());
        }
        return indexNames;
    }

    /**
     * 构建元数据查找 key。
     */
    protected String metadataLookupKey(String value) {
        return metadataNameMatcher.metadataLookupKey(value);
    }

    /**
     * JDBC 元数据名称匹配索引。
     */
    protected class MetadataNameIndex {

        private final MetadataNameMatcher.MetadataNameIndex delegate;

        MetadataNameIndex(Collection<String> actualNames) {
            this.delegate = metadataNameMatcher.metadataNameIndex(actualNames);
        }

        boolean contains(String expectedName) {
            return delegate.contains(expectedName);
        }
    }

    /**
     * 判断 ResultSet 当前行是否匹配本次元数据查询目标。
     */
    protected boolean matchesMetadataRow(String catalog, String schema, String tableName, ResultSet resultSet) throws SQLException {
        return matchesMetadataRow(
                catalog,
                schema,
                tableName,
                getString(resultSet, "TABLE_CAT"),
                getString(resultSet, "TABLE_SCHEM"),
                getString(resultSet, "TABLE_NAME")
        );
    }

    /**
     * 判断元数据名称是否匹配本次查询目标。
     */
    protected boolean matchesMetadataRow(String expectedCatalog, String expectedSchema, String expectedTableName, String actualCatalog, String actualSchema, String actualTableName) {
        return metadataNameMatcher.matchesMetadataRow(
                expectedCatalog,
                expectedSchema,
                expectedTableName,
                actualCatalog,
                actualSchema,
                actualTableName
        );
    }

    /**
     * 索引元数据行。
     */
    protected static class IndexMetadata {

        private final String catalog;

        private final String schema;

        private final String tableName;

        private final String indexName;

        private boolean nonUnique;

        private final List<String> columnNames = new ArrayList<>();

        IndexMetadata(String catalog, String schema, String tableName, String indexName, boolean nonUnique, Collection<String> columnNames) {
            this.catalog = catalog;
            this.schema = schema;
            this.tableName = tableName;
            this.indexName = indexName;
            this.nonUnique = nonUnique;
            addColumnNames(columnNames);
        }

        void addColumnNames(Collection<String> columnNames) {
            if (columnNames == null) {
                return;
            }
            for (String columnName : columnNames) {
                if (columnName != null && !columnName.trim().isEmpty()) {
                    this.columnNames.add(columnName);
                }
            }
        }

        void addColumnName(String columnName) {
            if (columnName != null && !columnName.trim().isEmpty()) {
                this.columnNames.add(columnName);
            }
        }

        void setNonUnique(boolean nonUnique) {
            this.nonUnique = this.nonUnique || nonUnique;
        }
    }

    /**
     * 主键元数据行。
     */
    protected static class PrimaryKeyMetadata {

        private final String catalog;

        private final String schema;

        private final String tableName;

        private final String primaryKeyName;

        private final String columnName;

        PrimaryKeyMetadata(String catalog, String schema, String tableName, String primaryKeyName, String columnName) {
            this.catalog = catalog;
            this.schema = schema;
            this.tableName = tableName;
            this.primaryKeyName = primaryKeyName;
            this.columnName = columnName;
        }
    }

    /**
     * 表元数据行。
     */
    protected static class TableMetadata {

        private final String catalog;

        private final String schema;

        private final String tableName;

        private final String tableType;

        private final String metadataCatalog;

        private final String metadataSchema;

        private final String metadataTableName;

        TableMetadata(String catalog, String schema, String tableName, String tableType) {
            this(catalog, schema, tableName, tableType, catalog, schema, tableName);
        }

        TableMetadata(String catalog, String schema, String tableName, String tableType,
                      String metadataCatalog, String metadataSchema, String metadataTableName) {
            this.catalog = catalog;
            this.schema = schema;
            this.tableName = tableName;
            this.tableType = tableType;
            this.metadataCatalog = metadataCatalog;
            this.metadataSchema = metadataSchema;
            this.metadataTableName = metadataTableName;
        }
    }

    /**
     * 探测表中的单列定义。
     */
    protected static class ColumnTypeProbeSpec {

        private final String typeFamilyKey;

        private final String columnName;

        private final String typeSql;

        ColumnTypeProbeSpec(String typeFamilyKey, String columnName, String typeSql) {
            this.typeFamilyKey = typeFamilyKey;
            this.columnName = columnName;
            this.typeSql = typeSql;
        }
    }

    /**
     * 当前数据库对实体类型族的实际 JDBC 元数据口径。
     */
    protected static class ColumnTypeProbeResult {

        private final Map<String, ColumnMetadata> columnMetadataByTypeFamily;

        ColumnTypeProbeResult(Map<String, ColumnMetadata> columnMetadataByTypeFamily) {
            this.columnMetadataByTypeFamily = new LinkedHashMap<>(columnMetadataByTypeFamily);
        }

        ColumnMetadata getColumnMetadata(String typeFamilyKey) {
            return columnMetadataByTypeFamily.get(typeFamilyKey);
        }
    }

    /**
     * 列元数据行。
     */
    protected static class ColumnMetadata {

        private final String catalog;

        private final String schema;

        private final String tableName;

        private final String columnName;

        private final int dataType;

        private final String typeName;

        private final int columnSize;

        private final int decimalDigits;

        private final int nullable;

        private final String columnDefault;

        private final String isAutoIncrement;

        private final String isGeneratedColumn;

        private final String remarks;

        ColumnMetadata(String catalog, String schema, String tableName, String columnName,
                       int dataType, String typeName, int columnSize, int decimalDigits, int nullable,
                       String columnDefault, String isAutoIncrement, String isGeneratedColumn, String remarks) {
            this.catalog = catalog;
            this.schema = schema;
            this.tableName = tableName;
            this.columnName = columnName;
            this.dataType = dataType;
            this.typeName = typeName;
            this.columnSize = columnSize;
            this.decimalDigits = decimalDigits;
            this.nullable = nullable;
            this.columnDefault = columnDefault;
            this.isAutoIncrement = isAutoIncrement;
            this.isGeneratedColumn = isGeneratedColumn;
            this.remarks = remarks;
        }

        String getColumnName() {
            return columnName;
        }

        int getDataType() {
            return dataType;
        }

        String getTypeName() {
            return typeName;
        }

        int getColumnSize() {
            return columnSize;
        }

        int getDecimalDigits() {
            return decimalDigits;
        }

        int getNullable() {
            return nullable;
        }

        String getColumnDefault() {
            return columnDefault;
        }

        String getIsAutoIncrement() {
            return isAutoIncrement;
        }

        String getIsGeneratedColumn() {
            return isGeneratedColumn;
        }

        String getRemarks() {
            return remarks;
        }
    }

    /**
     * 序列元数据行。
     */
    protected static class SequenceMetadata {

        private final String catalog;

        private final String schema;

        private final String sequenceName;

        SequenceMetadata(String catalog, String schema, String sequenceName) {
            this.catalog = catalog;
            this.schema = schema;
            this.sequenceName = sequenceName;
        }
    }

    /**
     * 当前连接可见的表和列元数据快照。
     */
    protected class DatabaseMetadata {

        private final String catalog;

        private final String defaultSchema;

        private final Map<String, List<TableMetadata>> tablesByName = new LinkedHashMap<>();

        private final Map<String, List<ColumnMetadata>> columnsByTableName = new LinkedHashMap<>();

        private final Map<String, List<IndexMetadata>> indexesByTableName = new LinkedHashMap<>();

        private final Map<String, List<PrimaryKeyMetadata>> primaryKeysByTableName = new LinkedHashMap<>();

        private final Map<String, List<SequenceMetadata>> sequencesByName = new LinkedHashMap<>();

        private ColumnTypeProbeResult columnTypeProbeResult;

        DatabaseMetadata(String catalog) {
            this(catalog, null);
        }

        DatabaseMetadata(String catalog, String defaultSchema) {
            this.catalog = catalog;
            this.defaultSchema = defaultSchema;
        }

        void addTable(TableInfo tableInfo) {
            addTable(tableInfo, tableInfo.getTableName());
        }

        void addTable(TableInfo tableInfo, String tableName) {
            addTable(catalog, resolveSchema(tableInfo.getSchema(), defaultSchema), tableName);
        }

        void addTable(String catalog, String schema, String tableName) {
            addTable(catalog, schema, tableName, TABLE_TYPE);
        }

        void addTable(String catalog, String schema, String tableName, String tableType) {
            addTable(catalog, schema, tableName, tableType, catalog, schema, tableName);
        }

        void addTable(String catalog, String schema, String tableName, String tableType,
                      String metadataCatalog, String metadataSchema, String metadataTableName) {
            put(tablesByName, metadataLookupKey(tableName), new TableMetadata(catalog, schema, tableName,
                    normalizeTableType(tableType), metadataCatalog, metadataSchema, metadataTableName));
        }

        void addColumns(TableInfo tableInfo, Collection<ColumnInfo> addColumns) {
            addColumns(tableInfo, tableInfo.getTableName(), addColumns);
        }

        void addColumns(TableInfo tableInfo, String tableName, Collection<ColumnInfo> addColumns) {
            for (ColumnInfo column : addColumns) {
                addColumn(catalog, resolveSchema(tableInfo.getSchema(), defaultSchema), tableName, column.getName());
            }
        }

        void addColumn(String catalog, String schema, String tableName, String columnName) {
            addColumn(catalog, schema, tableName, columnName, -1, null, 0, 0, DatabaseMetaData.columnNullableUnknown, null, null, null, null);
        }

        void addColumn(String catalog, String schema, String tableName, String columnName, int dataType, String typeName,
                       int columnSize, int decimalDigits, int nullable, String columnDefault, String isAutoIncrement, String isGeneratedColumn, String remarks) {
            put(columnsByTableName, metadataLookupKey(tableName), new ColumnMetadata(catalog, schema, tableName, columnName,
                    dataType, typeName, columnSize, decimalDigits, nullable, columnDefault, isAutoIncrement, isGeneratedColumn, remarks));
        }

        void addIndexes(TableInfo tableInfo, Collection<IndexInfo> addIndexes) {
            addIndexes(tableInfo, tableInfo.getTableName(), addIndexes);
        }

        void addIndexes(TableInfo tableInfo, String tableName, Collection<IndexInfo> addIndexes) {
            for (IndexInfo index : addIndexes) {
                List<String> columnNames = new ArrayList<>(index.getFields().size());
                for (IndexInfo.Field field : index.getFields()) {
                    columnNames.add(field.getColumnName());
                }
                addIndex(catalog, resolveSchema(tableInfo.getSchema(), defaultSchema), tableName, index.getName(),
                        !index.isUnique(), columnNames);
            }
        }

        void addIndex(String catalog, String schema, String tableName, String indexName) {
            addIndex(catalog, schema, tableName, indexName, false, Collections.emptyList());
        }

        void addIndex(String catalog, String schema, String tableName, String indexName, boolean nonUnique, String columnName) {
            addIndex(catalog, schema, tableName, indexName, nonUnique,
                    columnName == null ? Collections.emptyList() : Collections.singletonList(columnName));
        }

        void addIndex(String catalog, String schema, String tableName, String indexName, boolean nonUnique, Collection<String> columnNames) {
            String key = metadataLookupKey(tableName);
            List<IndexMetadata> indexes = indexesByTableName.get(key);
            if (indexes == null) {
                indexes = new ArrayList<>();
                indexesByTableName.put(key, indexes);
            }
            IndexMetadata indexMetadata = findIndexMetadata(indexes, catalog, schema, tableName, indexName);
            if (indexMetadata == null) {
                indexes.add(new IndexMetadata(catalog, schema, tableName, indexName, nonUnique, columnNames));
                return;
            }
            indexMetadata.setNonUnique(nonUnique);
            indexMetadata.addColumnNames(columnNames);
        }

        void addPrimaryKey(String catalog, String schema, String tableName, String primaryKeyName, String columnName) {
            if (isBlank(columnName)) {
                return;
            }
            put(primaryKeysByTableName, metadataLookupKey(tableName),
                    new PrimaryKeyMetadata(catalog, schema, tableName, primaryKeyName, columnName));
        }

        void addSequences(TableInfo tableInfo, Collection<SequenceInfo> addSequences) {
            for (SequenceInfo sequence : addSequences) {
                addSequence(
                        catalog,
                        resolveSchema(sequence.getSchema() == null ? tableInfo.getSchema() : sequence.getSchema(), defaultSchema),
                        sequence.getName()
                );
            }
        }

        void addSequence(String catalog, String schema, String sequenceName) {
            if (isBlank(sequenceName)) {
                return;
            }
            put(sequencesByName, metadataLookupKey(sequenceName), new SequenceMetadata(catalog, schema, sequenceName));
        }

        void setColumnTypeProbeResult(ColumnTypeProbeResult columnTypeProbeResult) {
            this.columnTypeProbeResult = columnTypeProbeResult;
        }

        ColumnTypeProbeResult getColumnTypeProbeResult() {
            return columnTypeProbeResult;
        }

        int objectType(TableInfo tableInfo) {
            return objectType(tableInfo, tableInfo.getTableName());
        }

        int objectType(TableInfo tableInfo, String tableName) {
            List<TableMetadata> tables = tablesByName.get(metadataLookupKey(tableName));
            if (tables == null) {
                return OBJECT_NOT_EXISTS;
            }
            int objectType = OBJECT_NOT_EXISTS;
            for (TableMetadata table : tables) {
                if (!matches(tableInfo, tableName, table.catalog, table.schema, table.tableName)) {
                    continue;
                }
                if (VIEW_TYPE.equals(table.tableType)) {
                    return OBJECT_VIEW;
                }
                if (TABLE_TYPE.equals(table.tableType)) {
                    objectType = OBJECT_TABLE;
                }
            }
            return objectType;
        }

        TableMetadata getTableMetadata(TableInfo tableInfo, String tableName) {
            List<TableMetadata> tables = tablesByName.get(metadataLookupKey(tableName));
            if (tables == null) {
                return null;
            }
            TableMetadata view = null;
            for (TableMetadata table : tables) {
                if (!matches(tableInfo, tableName, table.catalog, table.schema, table.tableName)) {
                    continue;
                }
                if (TABLE_TYPE.equals(table.tableType)) {
                    return table;
                }
                if (view == null && VIEW_TYPE.equals(table.tableType)) {
                    view = table;
                }
            }
            return view;
        }

        private String normalizeTableType(String tableType) {
            String normalizedTableType = isBlank(tableType) ? TABLE_TYPE : tableType.toUpperCase(Locale.ROOT);
            return "BASE TABLE".equals(normalizedTableType) ? TABLE_TYPE : normalizedTableType;
        }

        Set<String> getColumnNames(TableInfo tableInfo) {
            return getColumnNames(tableInfo, tableInfo.getTableName());
        }

        Set<String> getColumnNames(TableInfo tableInfo, String tableName) {
            Set<String> columnNames = new LinkedHashSet<>();
            List<ColumnMetadata> columns = columnsByTableName.get(metadataLookupKey(tableName));
            if (columns == null) {
                return columnNames;
            }
            for (ColumnMetadata column : columns) {
                if (matches(tableInfo, tableName, column.catalog, column.schema, column.tableName)) {
                    columnNames.add(column.columnName);
                }
            }
            return columnNames;
        }

        ColumnMetadata getColumnMetadata(TableInfo tableInfo, String tableName, String columnName) {
            List<ColumnMetadata> columns = columnsByTableName.get(metadataLookupKey(tableName));
            if (columns == null) {
                return null;
            }
            for (ColumnMetadata column : columns) {
                if (matches(tableInfo, tableName, column.catalog, column.schema, column.tableName)
                        && metadataNameMatcher.matchesMetadataName(columnName, column.columnName)) {
                    return column;
                }
            }
            return null;
        }

        ColumnMetadata getColumnMetadata(String expectedCatalog, String expectedSchema, String expectedTableName, String columnName) {
            List<ColumnMetadata> columns = columnsByTableName.get(metadataLookupKey(expectedTableName));
            if (columns == null) {
                return null;
            }
            Set<String> schemaCandidates = candidates(expectedSchema);
            if (expectedSchema == null) {
                schemaCandidates.add(null);
            }
            Set<String> tableCandidates = candidates(expectedTableName);
            for (ColumnMetadata column : columns) {
                if (!metadataNameMatcher.matchesMetadataName(columnName, column.columnName)) {
                    continue;
                }
                for (String schema : schemaCandidates) {
                    for (String tableCandidate : tableCandidates) {
                        if (matchesMetadataRow(expectedCatalog, schema, tableCandidate, column.catalog, column.schema, column.tableName)) {
                            return column;
                        }
                        if (schema != null && matchesMetadataRow(schema, null, tableCandidate, column.catalog, column.schema, column.tableName)) {
                            return column;
                        }
                    }
                }
            }
            return null;
        }

        Set<String> getIndexNames(TableInfo tableInfo) {
            return getIndexNames(tableInfo, tableInfo.getTableName());
        }

        Set<String> getIndexNames(TableInfo tableInfo, String tableName) {
            Set<String> indexNames = new LinkedHashSet<>();
            List<IndexMetadata> indexes = indexesByTableName.get(metadataLookupKey(tableName));
            if (indexes == null) {
                return indexNames;
            }
            for (IndexMetadata index : indexes) {
                if (matches(tableInfo, tableName, index.catalog, index.schema, index.tableName)) {
                    indexNames.add(index.indexName);
                }
            }
            return indexNames;
        }

        Set<String> getPrimaryKeyColumnNames(TableInfo tableInfo) {
            return getPrimaryKeyColumnNames(tableInfo, tableInfo.getTableName());
        }

        Set<String> getPrimaryKeyColumnNames(TableInfo tableInfo, String tableName) {
            Set<String> columnNames = new LinkedHashSet<>();
            List<PrimaryKeyMetadata> primaryKeys = primaryKeysByTableName.get(metadataLookupKey(tableName));
            if (primaryKeys == null) {
                return columnNames;
            }
            for (PrimaryKeyMetadata primaryKey : primaryKeys) {
                if (matches(tableInfo, tableName, primaryKey.catalog, primaryKey.schema, primaryKey.tableName)) {
                    columnNames.add(primaryKey.columnName);
                }
            }
            return columnNames;
        }

        Set<String> getPrimaryKeyIndexNames(TableInfo tableInfo) {
            return getPrimaryKeyIndexNames(tableInfo, tableInfo.getTableName());
        }

        Set<String> getPrimaryKeyIndexNames(TableInfo tableInfo, String tableName) {
            Set<String> indexNames = new LinkedHashSet<>();
            List<PrimaryKeyMetadata> primaryKeys = primaryKeysByTableName.get(metadataLookupKey(tableName));
            if (primaryKeys == null) {
                primaryKeys = Collections.emptyList();
            }
            for (PrimaryKeyMetadata primaryKey : primaryKeys) {
                if (matches(tableInfo, tableName, primaryKey.catalog, primaryKey.schema, primaryKey.tableName)
                        && !isBlank(primaryKey.primaryKeyName)) {
                    indexNames.add(primaryKey.primaryKeyName);
                }
            }
            return indexNames;
        }

        /**
         * 从 JDBC 索引元数据中收集唯一索引名，SYNC 删除普通索引时不误删约束索引。
         */
        Set<String> getUniqueIndexNames(TableInfo tableInfo, String tableName) {
            Set<String> indexNames = new LinkedHashSet<>();
            List<IndexMetadata> indexes = indexesByTableName.get(metadataLookupKey(tableName));
            if (indexes == null) {
                return indexNames;
            }
            for (IndexMetadata index : indexes) {
                if (!index.nonUnique && matches(tableInfo, tableName, index.catalog, index.schema, index.tableName)) {
                    indexNames.add(index.indexName);
                }
            }
            return indexNames;
        }

        boolean sequenceExists(TableInfo tableInfo, SequenceInfo sequence) {
            List<SequenceMetadata> sequences = sequencesByName.get(metadataLookupKey(sequence.getName()));
            if (sequences == null) {
                return false;
            }
            for (SequenceMetadata current : sequences) {
                if (matchesSequence(tableInfo, sequence, current.catalog, current.schema, current.sequenceName)) {
                    return true;
                }
            }
            return false;
        }

        private <T> void put(Map<String, List<T>> map, String key, T value) {
            List<T> values = map.get(key);
            if (values == null) {
                values = new ArrayList<>();
                map.put(key, values);
            }
            values.add(value);
        }

        private boolean matches(TableInfo tableInfo, String actualCatalog, String actualSchema, String actualTableName) {
            return matches(tableInfo, tableInfo.getTableName(), actualCatalog, actualSchema, actualTableName);
        }

        private boolean matches(TableInfo tableInfo, String tableName, String actualCatalog, String actualSchema, String actualTableName) {
            String schemaValue = resolveSchema(tableInfo.getSchema(), defaultSchema);
            Set<String> schemaCandidates = candidates(schemaValue);
            if (schemaValue == null) {
                schemaCandidates.add(null);
            }
            Set<String> tableCandidates = candidates(tableName);
            for (String schema : schemaCandidates) {
                for (String tableCandidate : tableCandidates) {
                    if (matchesMetadataRow(catalog, schema, tableCandidate, actualCatalog, actualSchema, actualTableName)) {
                        return true;
                    }
                    if (schema != null && matchesMetadataRow(schema, null, tableCandidate, actualCatalog, actualSchema, actualTableName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean matchesSequence(TableInfo tableInfo, SequenceInfo sequence, String actualCatalog, String actualSchema, String actualSequenceName) {
            String schemaValue = resolveSchema(sequence.getSchema() == null ? tableInfo.getSchema() : sequence.getSchema(), defaultSchema);
            Set<String> schemaCandidates = candidates(schemaValue);
            if (schemaValue == null) {
                schemaCandidates.add(null);
            }
            Set<String> sequenceCandidates = candidates(sequence.getName());
            for (String schema : schemaCandidates) {
                for (String sequenceName : sequenceCandidates) {
                    if (matchesMetadataRow(catalog, schema, sequenceName, actualCatalog, actualSchema, actualSequenceName)) {
                        return true;
                    }
                    if (schema != null && matchesMetadataRow(schema, null, sequenceName, actualCatalog, actualSchema, actualSequenceName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private IndexMetadata findIndexMetadata(List<IndexMetadata> indexes, String catalog, String schema, String tableName, String indexName) {
            for (IndexMetadata index : indexes) {
                if (matchesMetadataRow(catalog, schema, tableName, index.catalog, index.schema, index.tableName)
                        && metadataNameMatcher.matchesMetadataName(indexName, index.indexName)) {
                    return index;
                }
            }
            return null;
        }

        private boolean sameMetadataNames(Collection<String> expectedNames, Collection<String> actualNames) {
            if (expectedNames.size() != actualNames.size()) {
                return false;
            }
            Set<String> normalizedActualNames = new LinkedHashSet<>();
            for (String actualName : actualNames) {
                normalizedActualNames.add(normalize(actualName));
            }
            if (expectedNames.size() != normalizedActualNames.size()) {
                return false;
            }
            for (String expectedName : expectedNames) {
                if (!normalizedActualNames.contains(normalize(expectedName))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 元数据可选名称匹配；部分 JDBC 驱动不会返回 catalog/schema，因此空返回值视为可接受。
     */
    protected boolean matchesOptionalMetadataName(String expectedName, String actualName) {
        return metadataNameMatcher.matchesOptionalMetadataName(expectedName, actualName);
    }

    /**
     * 元数据名称匹配；显式 quoted identifier 按去引号后的大小写精确匹配。
     */
    protected boolean matchesMetadataName(String expectedName, String actualName) {
        return metadataNameMatcher.matchesMetadataName(expectedName, actualName);
    }

    /**
     * 从 JDBC 元数据中读取字符串字段，兼容部分驱动缺失字段的情况。
     */
    protected String getString(ResultSet resultSet, String columnLabel) throws SQLException {
        try {
            return resultSet.getString(columnLabel);
        } catch (SQLException ignored) {
            return null;
        }
    }

    /**
     * 从 JDBC 元数据中读取整数字段，兼容部分驱动缺失字段的情况。
     */
    protected int getInt(ResultSet resultSet, String columnLabel) throws SQLException {
        try {
            return resultSet.getInt(columnLabel);
        } catch (SQLException ignored) {
            return 0;
        }
    }

    protected boolean isQuotedIdentifier(String value) {
        return metadataNameMatcher.isQuotedIdentifier(value);
    }

    protected String unquoteIdentifier(String value) {
        return metadataNameMatcher.unquoteIdentifier(value);
    }

    protected boolean isBlank(String value) {
        return metadataNameMatcher.isBlank(value);
    }

    /**
     * 转义 SQL 字符串字面量。
     */
    protected String escapeSqlString(String value) {
        return value == null ? null : value.replace("'", "''");
    }
}
