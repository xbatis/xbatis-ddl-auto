package cn.xbatis.ddl.auto;

import cn.xbatis.db.annotations.ColumnDefinition;
import db.sql.api.IDbType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Java 字段类型到数据库列类型的默认映射。
 */
class ColumnTypeMapper {

    private final DDLDialect dialect;

    ColumnTypeMapper(DDLDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    String getColumnType(IDbType dbType, Class<?> type, ColumnDefinition definition, boolean autoIncrement) {
        if (autoIncrement) {
            String autoIncrementType = dialect.getAutoIncrementType(dbType, type);
            if (autoIncrementType != null) {
                return autoIncrementType;
            }
        }
        if (type == String.class || type == Character.class || type == char.class || type == UUID.class) {
            return getStringType(dbType, getLength(definition, type == UUID.class ? 36 : 255));
        }
        if (type == Integer.class || type == int.class) {
            return autoIncrement && isPostgresql(dbType) ? "INTEGER" : getIntegerType(dbType);
        }
        if (type == Long.class || type == long.class || type == BigInteger.class) {
            return getBigIntType(dbType);
        }
        if (type == Short.class || type == short.class) {
            return getSmallIntType(dbType);
        }
        if (type == Byte.class || type == byte.class) {
            return getByteType(dbType);
        }
        if (type == Boolean.class || type == boolean.class) {
            return getBooleanType(dbType);
        }
        if (type == BigDecimal.class) {
            int precision = definition.precision() > 0 ? definition.precision() : 19;
            int scale = definition.scale() > 0 ? definition.scale() : 2;
            return getDecimalType(dbType, precision, scale);
        }
        if (type == Float.class || type == float.class) {
            return isOracle(dbType) ? "BINARY_FLOAT" : "REAL";
        }
        if (type == Double.class || type == double.class) {
            return isMysql(dbType) ? "DOUBLE" : "DOUBLE PRECISION";
        }
        if (type == byte[].class || type == Byte[].class) {
            return getBlobType(dbType);
        }
        if (type == LocalDate.class || type == java.sql.Date.class) {
            return "DATE";
        }
        if (type == LocalTime.class || type == Time.class) {
            return getTimeType(dbType);
        }
        if (type == LocalDateTime.class || type == Timestamp.class || type == Instant.class || Date.class.isAssignableFrom(type)) {
            return getDateTimeType(dbType);
        }
        return getStringType(dbType, getLength(definition, 255));
    }

    String getStringType(IDbType dbType, int length) {
        return dialect.getStringType(dbType, length);
    }

    String getIntegerType(IDbType dbType) {
        return dialect.getIntegerType(dbType);
    }

    String getBigIntType(IDbType dbType) {
        return dialect.getBigIntType(dbType);
    }

    String getSmallIntType(IDbType dbType) {
        return dialect.getSmallIntType(dbType);
    }

    String getByteType(IDbType dbType) {
        return dialect.getByteType(dbType);
    }

    String getBooleanType(IDbType dbType) {
        return dialect.getBooleanType(dbType);
    }

    String getDecimalType(IDbType dbType, int precision, int scale) {
        return dialect.getDecimalType(dbType, precision, scale);
    }

    String getBlobType(IDbType dbType) {
        return dialect.getBlobType(dbType);
    }

    String getTimeType(IDbType dbType) {
        return dialect.getTimeType(dbType);
    }

    String getDateTimeType(IDbType dbType) {
        return dialect.getDateTimeType(dbType);
    }

    int getLength(ColumnDefinition columnDefinition, int defaultLength) {
        return columnDefinition.length() > 0 ? columnDefinition.length() : defaultLength;
    }

    private boolean isMysql(IDbType dbType) {
        return dialect.isMysql(dbType);
    }

    private boolean isPostgresql(IDbType dbType) {
        return dialect.isPostgresql(dbType);
    }

    private boolean isOracle(IDbType dbType) {
        return dialect.isOracle(dbType);
    }
}
