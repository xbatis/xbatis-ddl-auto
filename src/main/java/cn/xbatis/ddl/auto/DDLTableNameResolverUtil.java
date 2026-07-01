package cn.xbatis.ddl.auto;

import cn.xbatis.core.db.reflect.TableInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * DDL 物理表名解析工具。
 * <p>
 * 默认不干预实体表名；设置解析函数后，一个实体可以展开为多个物理表。
 */
public final class DDLTableNameResolverUtil {

    private static final ConcurrentMap<Class<?>, Function<String, List<String>>> RESOLVERS = new ConcurrentHashMap<>();

    private DDLTableNameResolverUtil() {
    }

    /**
     * 设置实体表名解析函数。resolver 为 null 时移除配置。
     */
    public static void set(Class<?> entityClass, Function<String, List<String>> resolver) {
        Objects.requireNonNull(entityClass, "entityClass");
        if (resolver == null) {
            remove(entityClass);
            return;
        }
        RESOLVERS.put(entityClass, resolver);
    }

    /**
     * 移除指定实体的表名解析函数。
     */
    public static void remove(Class<?> entityClass) {
        RESOLVERS.remove(Objects.requireNonNull(entityClass, "entityClass"));
    }

    /**
     * 清空所有表名解析函数。
     */
    public static void clear() {
        RESOLVERS.clear();
    }

    /**
     * 根据实体元数据解析物理表名列表。
     */
    public static List<String> resolve(TableInfo tableInfo) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        if (!isTable(tableInfo)) {
            return Collections.emptyList();
        }
        return resolve(tableInfo.getType(), tableInfo.getTableName());
    }

    /**
     * 判断实体是否对应真实数据库表；@Table(table = false) 通常用于视图等非建表对象。
     */
    public static boolean isTable(TableInfo tableInfo) {
        Objects.requireNonNull(tableInfo, "tableInfo");
        return tableInfo.getAnnotation() == null || tableInfo.getAnnotation().table();
    }

    /**
     * 根据实体类型和原始表名解析物理表名列表。
     */
    public static List<String> resolve(Class<?> entityClass, String tableName) {
        Objects.requireNonNull(entityClass, "entityClass");
        validateTableName(entityClass, tableName);

        Function<String, List<String>> resolver = RESOLVERS.get(entityClass);
        if (resolver == null) {
            return Collections.singletonList(tableName);
        }

        List<String> resolvedTableNames = resolver.apply(tableName);
        if (resolvedTableNames == null) {
            throw new IllegalArgumentException("DDL table name resolver returned null table names: "
                    + entityClass.getName());
        }
        if (resolvedTableNames.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> tableNames = new LinkedHashSet<>();
        for (String resolvedTableName : resolvedTableNames) {
            validateTableName(entityClass, resolvedTableName);
            tableNames.add(resolvedTableName);
        }
        return Collections.unmodifiableList(new ArrayList<>(tableNames));
    }

    private static void validateTableName(Class<?> entityClass, String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("DDL table name resolver returned blank table name: "
                    + entityClass.getName());
        }
        if (tableName.indexOf('.') >= 0) {
            throw new IllegalArgumentException("DDL table name resolver must return table name only: "
                    + entityClass.getName() + " -> " + tableName);
        }
    }
}
