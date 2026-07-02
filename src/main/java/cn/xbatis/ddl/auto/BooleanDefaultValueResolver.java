package cn.xbatis.ddl.auto;

import db.sql.api.DbModel;
import db.sql.api.DbType;
import db.sql.api.IDbType;

import java.util.Locale;

/**
 * Boolean 字段默认值到数据库默认表达式的适配。
 */
final class BooleanDefaultValueResolver {

    private BooleanDefaultValueResolver() {

    }

    static String resolve(IDbType dbType, Class<?> javaType, String defaultValue) {
        if (javaType != Boolean.class && javaType != boolean.class) {
            return defaultValue;
        }
        Boolean booleanValue = parseBooleanDefaultValue(defaultValue);
        if (booleanValue == null) {
            return defaultValue;
        }
        if (usesNumericBooleanDefault(dbType)) {
            return booleanValue ? "1" : "0";
        }
        return booleanValue ? "TRUE" : "FALSE";
    }

    private static Boolean parseBooleanDefaultValue(String defaultValue) {
        String normalizedValue = defaultValue.trim().toUpperCase(Locale.ROOT);
        if ("0".equals(normalizedValue) || "FALSE".equals(normalizedValue)) {
            return Boolean.FALSE;
        }
        if ("1".equals(normalizedValue) || "TRUE".equals(normalizedValue)) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static boolean usesNumericBooleanDefault(IDbType dbType) {
        return isMysql(dbType) || isOracle(dbType) || dbType == DbType.SQL_SERVER;
    }

    private static boolean isMysql(IDbType dbType) {
        return dbType == DbType.MYSQL || dbType == DbType.MARIA_DB || dbType == DbType.COBAR
                || dbType == DbType.OCEAN_BASE || isDbModel(dbType, DbModel.MYSQL);
    }

    private static boolean isOracle(IDbType dbType) {
        return dbType == DbType.ORACLE || dbType == DbType.DM || isDbModel(dbType, DbModel.ORACLE);
    }

    private static boolean isDbModel(IDbType dbType, DbModel dbModel) {
        return dbType != null && dbType.getDbModel() == dbModel;
    }
}
