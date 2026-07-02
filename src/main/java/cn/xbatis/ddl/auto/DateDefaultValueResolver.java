package cn.xbatis.ddl.auto;

import db.sql.api.DbModel;
import db.sql.api.DbType;
import db.sql.api.IDbType;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;

/**
 * 日期/时间动态默认值到数据库默认表达式的适配。
 */
final class DateDefaultValueResolver {

    private static final String NOW_DYNAMIC_VALUE = "{NOW}";
    private static final String TODAY_DYNAMIC_VALUE = "{TODAY}";

    private DateDefaultValueResolver() {

    }

    static String resolve(IDbType dbType, Class<?> javaType, String dynamicValue) {
        if (NOW_DYNAMIC_VALUE.equals(dynamicValue)) {
            if (isDateOnlyType(javaType)) {
                return currentDateDefaultValue(dbType);
            }
            if (isTimeOnlyType(javaType)) {
                return currentTimeDefaultValue(dbType);
            }
            if (isDateTimeWithTimeZoneType(javaType)) {
                return currentDateTimeWithTimeZoneDefaultValue(dbType);
            }
            if (isDateTimeType(javaType)) {
                return currentDateTimeDefaultValue(dbType);
            }
            return "";
        }
        if (TODAY_DYNAMIC_VALUE.equals(dynamicValue) && isDateOnlyType(javaType)) {
            return currentDateDefaultValue(dbType);
        }
        return "";
    }

    private static boolean isDateOnlyType(Class<?> javaType) {
        return javaType == LocalDate.class || javaType == java.sql.Date.class;
    }

    private static boolean isTimeOnlyType(Class<?> javaType) {
        return javaType == LocalTime.class || javaType == Time.class;
    }

    private static boolean isDateTimeType(Class<?> javaType) {
        return javaType == LocalDateTime.class
                || javaType == Timestamp.class
                || (Date.class.isAssignableFrom(javaType) && javaType != java.sql.Date.class);
    }

    private static boolean isDateTimeWithTimeZoneType(Class<?> javaType) {
        return javaType == Instant.class || javaType == OffsetDateTime.class || javaType == ZonedDateTime.class;
    }

    private static String currentDateTimeDefaultValue(IDbType dbType) {
        if (dbType == DbType.SQL_SERVER) {
            return "SYSDATETIME()";
        }
        if (dbType == DbType.DB2) {
            return "CURRENT TIMESTAMP";
        }
        if (dbType == DbType.CLICK_HOUSE) {
            return "now()";
        }
        return "CURRENT_TIMESTAMP";
    }

    private static String currentDateTimeWithTimeZoneDefaultValue(IDbType dbType) {
        if (dbType == DbType.SQL_SERVER) {
            return "SYSDATETIMEOFFSET()";
        }
        if (dbType == DbType.DB2) {
            return "CURRENT TIMESTAMP";
        }
        if (dbType == DbType.CLICK_HOUSE) {
            return "now()";
        }
        return "CURRENT_TIMESTAMP";
    }

    private static String currentDateDefaultValue(IDbType dbType) {
        if (isMysql(dbType)) {
            return "(CURRENT_DATE)";
        }
        if (dbType == DbType.SQL_SERVER) {
            return "(CAST(GETDATE() AS DATE))";
        }
        if (isOracle(dbType)) {
            return "TRUNC(SYSDATE)";
        }
        if (dbType == DbType.DB2) {
            return "CURRENT DATE";
        }
        if (dbType == DbType.CLICK_HOUSE) {
            return "today()";
        }
        return "CURRENT_DATE";
    }

    private static String currentTimeDefaultValue(IDbType dbType) {
        if (isMysql(dbType)) {
            return "(CURRENT_TIME)";
        }
        if (dbType == DbType.SQL_SERVER) {
            return "(CAST(GETDATE() AS TIME))";
        }
        if (isOracle(dbType)) {
            return "CURRENT_TIMESTAMP";
        }
        if (dbType == DbType.DB2) {
            return "CURRENT TIME";
        }
        if (dbType == DbType.CLICK_HOUSE) {
            return "now()";
        }
        return "CURRENT_TIME";
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
