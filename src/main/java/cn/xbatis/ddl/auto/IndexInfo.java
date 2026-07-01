package cn.xbatis.ddl.auto;

import cn.xbatis.db.IndexDirection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * xbatis 索引注解到数据库索引 DDL 的轻量适配对象。
 */
public class IndexInfo {

    private final String name;

    private final boolean unique;

    private final List<Field> fields;

    private final boolean explicitName;

    public IndexInfo(String name, boolean unique, List<Field> fields) {
        this(name, unique, fields, true);
    }

    public IndexInfo(String name, boolean unique, List<Field> fields, boolean explicitName) {
        this.name = Objects.requireNonNull(name, "name");
        this.unique = unique;
        this.explicitName = explicitName;
        Objects.requireNonNull(fields, "fields");
        List<Field> checkedFields = new ArrayList<>(fields.size());
        for (Field field : fields) {
            checkedFields.add(Objects.requireNonNull(field, "field"));
        }
        this.fields = Collections.unmodifiableList(checkedFields);
    }

    public String getName() {
        return name;
    }

    public boolean isUnique() {
        return unique;
    }

    public List<Field> getFields() {
        return fields;
    }

    public boolean isExplicitName() {
        return explicitName;
    }

    /**
     * 索引字段。
     */
    public static class Field {

        private final String columnName;

        private final IndexDirection direction;

        public Field(String columnName, IndexDirection direction) {
            this.columnName = Objects.requireNonNull(columnName, "columnName");
            this.direction = direction == null ? IndexDirection.DEFAULT : direction;
        }

        public String getColumnName() {
            return columnName;
        }

        public IndexDirection getDirection() {
            return direction;
        }
    }
}
