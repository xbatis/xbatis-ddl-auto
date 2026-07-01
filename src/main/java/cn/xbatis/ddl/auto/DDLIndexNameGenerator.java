package cn.xbatis.ddl.auto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * DDL 索引名生成规则。
 */
public final class DDLIndexNameGenerator {

    private static final Pattern NON_IDENTIFIER_CHAR_PATTERN = Pattern.compile("[^A-Za-z0-9_]");

    private static final int MAX_IDENTIFIER_LENGTH = 48;

    private static final int HASH_PREFIX_LENGTH = 39;

    private DDLIndexNameGenerator() {
    }

    /**
     * 生成唯一约束对应的索引名。
     */
    public static String uniqueName(String tableName, String columnName) {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(columnName, "columnName");
        String normalizedName = normalize("uk_" + tableName + "_" + columnName);
        if (normalizedName.length() <= MAX_IDENTIFIER_LENGTH) {
            return normalizedName;
        }
        return shortenedName(normalizedName, tableName, columnName);
    }

    /**
     * 生成普通/唯一索引名。
     */
    public static String indexName(String tableName, List<IndexInfo.Field> fields, boolean unique) {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(fields, "fields");
        StringBuilder baseName = new StringBuilder(unique ? "uk_" : "idx_");
        baseName.append(tableName);
        List<String> columnNames = new ArrayList<>(fields.size());
        for (IndexInfo.Field field : fields) {
            Objects.requireNonNull(field, "field");
            baseName.append("_").append(field.getColumnName());
            columnNames.add(field.getColumnName());
        }
        String normalizedName = normalize(baseName.toString());
        if (normalizedName.length() <= MAX_IDENTIFIER_LENGTH) {
            return normalizedName;
        }
        return shortenedName(normalizedName, tableName, columnNames, unique);
    }

    private static String normalize(String name) {
        return NON_IDENTIFIER_CHAR_PATTERN.matcher(name).replaceAll("_");
    }

    private static String shortenedName(String normalizedName, Object... hashValues) {
        return normalizedName.substring(0, HASH_PREFIX_LENGTH) + "_" + Integer.toHexString(Objects.hash(hashValues));
    }
}
