package cn.xbatis.ddl.auto;

import cn.xbatis.core.db.reflect.TableFieldInfo;
import cn.xbatis.core.util.TableInfoUtil;
import cn.xbatis.db.IdAutoType;
import cn.xbatis.db.annotations.ColumnDefinition;
import cn.xbatis.db.annotations.TableId;
import db.sql.api.DbType;
import db.sql.api.IDbType;

import java.lang.reflect.Field;

/**
 * xbatis 字段元数据到数据库列元数据的轻量适配对象。
 */
public class ColumnInfo {

    private final TableFieldInfo tableFieldInfo;

    private final String name;

    private final Field field;

    private final Class<?> javaType;

    private final ColumnDefinition definition;

    private final boolean id;

    private final TableId tableId;

    /**
     * 从 xbatis 的 TableFieldInfo 中提取建表所需信息。
     *
     * @param tableFieldInfo xbatis 字段元数据
     * @param dbType         当前数据库类型，用于选择匹配的 @TableId 配置
     */
    public ColumnInfo(TableFieldInfo tableFieldInfo, IDbType dbType) {
        this.tableFieldInfo = tableFieldInfo;
        this.name = tableFieldInfo.getColumnName();
        this.field = tableFieldInfo.getField();
        this.definition = getColumnDefinition(tableFieldInfo, dbType);
        if (this.definition.javaType() != Void.class) {
            this.javaType = this.definition.javaType();
        } else {
            this.javaType = tableFieldInfo.getFieldInfo().getTypeClass();
        }
        this.id = tableFieldInfo.isTableId();
        this.tableId = this.id ? TableInfoUtil.getTableIdAnnotation(tableFieldInfo, dbType) : null;
    }

    public TableFieldInfo getTableFieldInfo() {
        return tableFieldInfo;
    }

    public String getName() {
        return name;
    }

    public Field getField() {
        return field;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public ColumnDefinition getDefinition() {
        return definition;
    }

    public boolean isId() {
        return id;
    }

    public TableId getTableId() {
        return tableId;
    }

    public IdAutoType getIdAutoType() {
        return tableId == null ? IdAutoType.NONE : tableId.value();
    }

    private static ColumnDefinition getColumnDefinition(TableFieldInfo tableFieldInfo, IDbType dbType) {
        Field field = tableFieldInfo.getField();
        ColumnDefinition[] definitions = field.getAnnotationsByType(ColumnDefinition.class);
        ColumnDefinition definition = null;
        if (definitions.length == 1) {
            definition = definitions[0];
        } else if (definitions.length > 1) {
            ColumnDefinition unknown = null;
            for (ColumnDefinition df : definitions) {
                if (dbType.getName().equals(df.dbType())) {
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
        return ColumnDefinitionValue.of(dbType,tableFieldInfo, definition);
    }
}
