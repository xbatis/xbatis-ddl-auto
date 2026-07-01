package cn.xbatis.ddl.auto;

import cn.xbatis.core.db.reflect.TableInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单个实体生成 DDL 时可复用的解析结果。
 */
public class EntityDDLMetadata {

    private final TableInfo tableInfo;

    private final List<ColumnInfo> columns;

    private final List<IndexInfo> indexes;

    private final List<SequenceInfo> sequences;

    public EntityDDLMetadata(TableInfo tableInfo, List<ColumnInfo> columns, List<IndexInfo> indexes, List<SequenceInfo> sequences) {
        this.tableInfo = Objects.requireNonNull(tableInfo, "tableInfo");
        this.columns = immutableCopy(columns, "columns");
        this.indexes = immutableCopy(indexes, "indexes");
        this.sequences = immutableCopy(sequences, "sequences");
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        List<T> checkedValues = new ArrayList<>(values.size());
        for (T value : values) {
            checkedValues.add(Objects.requireNonNull(value, name + " item"));
        }
        return Collections.unmodifiableList(checkedValues);
    }

    public TableInfo getTableInfo() {
        return tableInfo;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public List<IndexInfo> getIndexes() {
        return indexes;
    }

    public List<SequenceInfo> getSequences() {
        return sequences;
    }
}
