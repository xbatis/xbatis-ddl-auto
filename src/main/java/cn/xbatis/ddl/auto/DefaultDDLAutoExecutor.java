package cn.xbatis.ddl.auto;

import cn.xbatis.core.db.reflect.TableInfo;
import cn.xbatis.core.db.reflect.Tables;
import db.sql.api.DbType;
import db.sql.api.IDbType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

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

    private final DDLBuilder ddlBuilder;

    private final DDLExecutionListener executionListener;

    private final MetadataNameMatcher metadataNameMatcher = new MetadataNameMatcher();

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
        DatabaseMetadata databaseMetadata = loadDatabaseMetadataForEntities(dbType, connection, entityMetadataList, mode == Mode.UPDATE);
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
            DatabaseMetadata databaseMetadata = loadDatabaseMetadataForEntities(dbType, connection, entityMetadataList, mode == Mode.UPDATE);
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
        return createSqlList(dbType, mode, entityMetadata,
                loadDatabaseMetadataForEntities(dbType, connection, Collections.singletonList(entityMetadata), mode == Mode.UPDATE));
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
                if (mode == Mode.UPDATE) {
                    databaseMetadata.addColumns(tableInfo, tableName, entityMetadata.getColumns());
                    databaseMetadata.addIndexes(tableInfo, tableName,
                            ddlBuilder.resolveIndexes(dbType, tableInfo, entityMetadata.getIndexes(), tableName));
                }
                continue;
            }
            if (mode == Mode.UPDATE) {
                sqlList.addAll(createAddSequenceSqlList(dbType, entityMetadata, databaseMetadata));
                sqlList.addAll(createAddColumnSqlList(dbType, entityMetadata, tableName, databaseMetadata));
                sqlList.addAll(createAddIndexSqlList(dbType, entityMetadata, tableName, databaseMetadata));
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
    protected DatabaseMetadata loadDatabaseMetadataForEntities(IDbType dbType, Connection connection, Collection<EntityDDLMetadata> entityMetadataList, boolean includeColumns) throws SQLException {
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
            if (shouldReadIndexesForEntities(dbType, entityMetadataList)) {
                readIndexes(metaData, databaseMetadata, tableInfos);
            }
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
        if (shouldBatchReadSchemaMetadata(tableInfos)) {
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
                Set<String> tableCandidates = candidates(physicalTableName);
                for (String schemaCandidate : schemaCandidates) {
                    for (String tableName : tableCandidates) {
                        readTableMetadataIfNecessary(metaData, databaseMetadata.catalog, schemaCandidate, tableName, databaseMetadata, readKeys);
                        if (schemaCandidate != null && schemaAsCatalogFallback) {
                            readTableMetadataIfNecessary(metaData, schemaCandidate, null, tableName, databaseMetadata, readKeys);
                        }
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
                databaseMetadata.addTable(
                        getString(resultSet, "TABLE_CAT"),
                        getString(resultSet, "TABLE_SCHEM"),
                        getString(resultSet, "TABLE_NAME"),
                        getString(resultSet, "TABLE_TYPE")
                );
            }
        }
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
        if (shouldBatchReadSchemaMetadata(tableInfos) && readColumnsBySchema(metaData, databaseMetadata, tableInfos)) {
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
                if (databaseMetadata.objectType(tableInfo, tableName) == OBJECT_TABLE) {
                    readColumns(metaData, databaseMetadata, tableInfo, tableName, readKeys, schemaAsCatalogFallback);
                }
            }
        }
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
        try (ResultSet resultSet = metaData.getColumns(catalog, schema, tableName, null)) {
            while (resultSet.next()) {
                databaseMetadata.addColumn(
                        getString(resultSet, "TABLE_CAT"),
                        getString(resultSet, "TABLE_SCHEM"),
                        getString(resultSet, "TABLE_NAME"),
                        getString(resultSet, "COLUMN_NAME")
                );
            }
        }
    }

    /**
     * 按 schema 分组读取数据索引。
     */
    protected void readIndexes(DatabaseMetaData metaData, DatabaseMetadata databaseMetadata, Collection<TableInfo> tableInfos) throws SQLException {
        if (shouldBatchReadSchemaMetadata(tableInfos) && readIndexesBySchema(metaData, databaseMetadata, tableInfos)) {
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
                if (databaseMetadata.objectType(tableInfo, tableName) == OBJECT_TABLE) {
                    readIndexes(metaData, databaseMetadata, tableInfo, tableName, readKeys, schemaAsCatalogFallback);
                }
            }
        }
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
                            indexName
                    );
                }
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
        List<String> sqlList = ddlBuilder.addColumnSqlList(dbType, tableInfo, missingColumns, tableName);
        if (databaseMetadata != null) {
            databaseMetadata.addColumns(tableInfo, tableName, missingColumns);
        }
        return sqlList;
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
     * 部分数据库驱动把 schema 放在 catalog 位置返回；SQL Server 的 catalog 是数据库名，不能把 dbo 当 database 再查。
     */
    protected boolean supportsSchemaAsCatalogFallback(DatabaseMetaData metaData) throws SQLException {
        if (metaData == null) {
            return true;
        }
        String productName = metaData.getDatabaseProductName();
        return productName == null || !productName.toLowerCase(Locale.ROOT).contains("sql server");
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
                "Failed to execute DDL SQL: " + sql + separator + "executed SQL before failure: " + executedSqlList,
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

        IndexMetadata(String catalog, String schema, String tableName, String indexName) {
            this.catalog = catalog;
            this.schema = schema;
            this.tableName = tableName;
            this.indexName = indexName;
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

        TableMetadata(String catalog, String schema, String tableName, String tableType) {
            this.catalog = catalog;
            this.schema = schema;
            this.tableName = tableName;
            this.tableType = tableType;
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

        ColumnMetadata(String catalog, String schema, String tableName, String columnName) {
            this.catalog = catalog;
            this.schema = schema;
            this.tableName = tableName;
            this.columnName = columnName;
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

        private final Map<String, List<SequenceMetadata>> sequencesByName = new LinkedHashMap<>();

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
            put(tablesByName, metadataLookupKey(tableName), new TableMetadata(catalog, schema, tableName, normalizeTableType(tableType)));
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
            put(columnsByTableName, metadataLookupKey(tableName), new ColumnMetadata(catalog, schema, tableName, columnName));
        }

        void addIndexes(TableInfo tableInfo, Collection<IndexInfo> addIndexes) {
            addIndexes(tableInfo, tableInfo.getTableName(), addIndexes);
        }

        void addIndexes(TableInfo tableInfo, String tableName, Collection<IndexInfo> addIndexes) {
            for (IndexInfo index : addIndexes) {
                addIndex(catalog, resolveSchema(tableInfo.getSchema(), defaultSchema), tableName, index.getName());
            }
        }

        void addIndex(String catalog, String schema, String tableName, String indexName) {
            put(indexesByTableName, metadataLookupKey(tableName), new IndexMetadata(catalog, schema, tableName, indexName));
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

    protected boolean isQuotedIdentifier(String value) {
        return metadataNameMatcher.isQuotedIdentifier(value);
    }

    protected String unquoteIdentifier(String value) {
        return metadataNameMatcher.unquoteIdentifier(value);
    }

    protected boolean isBlank(String value) {
        return metadataNameMatcher.isBlank(value);
    }
}
