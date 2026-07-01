package cn.xbatis.ddl.auto;

import java.util.Objects;

/**
 * 数据库序列元数据。
 */
public class SequenceInfo {

    private final String schema;

    private final String name;

    public SequenceInfo(String name) {
        this(null, name);
    }

    public SequenceInfo(String schema, String name) {
        this.schema = blankToNull(schema);
        this.name = Objects.requireNonNull(name, "name");
        if (this.name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    public String getSchema() {
        return schema;
    }

    public String getName() {
        return name;
    }

    public String getQualifiedName() {
        return schema == null ? name : schema + "." + name;
    }
}
