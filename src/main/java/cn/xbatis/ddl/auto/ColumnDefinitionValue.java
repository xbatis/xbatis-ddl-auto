package cn.xbatis.ddl.auto;

import cn.xbatis.core.XbatisGlobalConfig;
import cn.xbatis.core.db.reflect.TableFieldInfo;
import cn.xbatis.db.annotations.ColumnDefinition;
import db.sql.api.IDbType;

import java.lang.annotation.Annotation;
import java.util.Arrays;

/**
 * ColumnDefinition 的运行时值对象，用于兼容其他字段默认值来源。
 */
class ColumnDefinitionValue implements ColumnDefinition {

    private static final String DEFAULT_COLUMN_DEFINITION_FIELD = "DEFAULT_COLUMN_DEFINITION";
    static final ColumnDefinition DEFAULT = getDefaultColumnDefinition();
    @ColumnDefinition
    private static final Object DEFAULT_COLUMN_DEFINITION = null;
    private final ColumnDefinition definition;

    private final String defaultValue;

    private ColumnDefinitionValue(ColumnDefinition definition, String defaultValue) {
        this.definition = definition;
        this.defaultValue = defaultValue;
    }

    static ColumnDefinition of(IDbType dbType, TableFieldInfo tableFieldInfo, ColumnDefinition definition) {
        ColumnDefinition resolvedDefinition = definition == null ? DEFAULT : definition;
        if (!resolvedDefinition.defaultValue().isEmpty()) {
            return resolvedDefinition;
        }
        String tableFieldDefaultValue = "";

        if (tableFieldInfo.isLogicDelete()) {
            tableFieldDefaultValue = tableFieldInfo.getLogicDeleteInitValue() == null ? "" : tableFieldInfo.getLogicDeleteInitValue().toString();
        }

        if (tableFieldDefaultValue.isEmpty()) {
            tableFieldDefaultValue = tableFieldInfo.getTableFieldAnnotation().defaultValue();
        }

        if (tableFieldDefaultValue.isEmpty() && tableFieldInfo.isVersion()) {
            tableFieldDefaultValue = "1";
        }

        if (tableFieldDefaultValue.isEmpty()) {
            return resolvedDefinition;
        }

        if (XbatisGlobalConfig.isDynamicValueKeyFormat(tableFieldDefaultValue)) {
            tableFieldDefaultValue = DateDefaultValueResolver.resolve(dbType,
                    tableFieldInfo.getFieldInfo().getTypeClass(),
                    tableFieldDefaultValue);
        }

        if (tableFieldDefaultValue.isEmpty()) {
            return resolvedDefinition;
        }
        tableFieldDefaultValue = BooleanDefaultValueResolver.resolve(dbType,
                tableFieldInfo.getFieldInfo().getTypeClass(),
                tableFieldDefaultValue);
        return new ColumnDefinitionValue(resolvedDefinition, tableFieldDefaultValue);
    }

    private static ColumnDefinition getDefaultColumnDefinition() {
        return Arrays.stream(ColumnDefinitionValue.class.getDeclaredFields())
                .filter(field -> DEFAULT_COLUMN_DEFINITION_FIELD.equals(field.getName()))
                .findFirst()
                .get()
                .getAnnotation(ColumnDefinition.class);
    }

    @Override
    public String dbType() {
        return definition.dbType();
    }

    @Override
    public int index() {
        return definition.index();
    }

    @Override
    public String defaultValue() {
        return defaultValue;
    }

    @Override
    public int length() {
        return definition.length();
    }

    @Override
    public boolean unique() {
        return definition.unique();
    }

    @Override
    public boolean nullable() {
        return definition.nullable();
    }

    @Override
    public int precision() {
        return definition.precision();
    }

    @Override
    public int scale() {
        return definition.scale();
    }

    @Override
    public String definition() {
        return definition.definition();
    }

    @Override
    public String comment() {
        return definition.comment();
    }

    @Override
    public Class<?> javaType() {
        return definition.javaType();
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return ColumnDefinition.class;
    }
}
