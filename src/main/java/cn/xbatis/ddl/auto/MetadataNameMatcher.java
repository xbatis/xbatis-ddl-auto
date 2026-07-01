package cn.xbatis.ddl.auto;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * JDBC 元数据名称匹配工具。
 * <p>
 * 统一处理 catalog、schema、table、column 在不同驱动中的大小写和 quoted identifier 差异。
 */
class MetadataNameMatcher {

    Set<String> candidates(String value) {
        Set<String> values = new LinkedHashSet<>();
        if (isBlank(value)) {
            return values;
        }
        values.add(value);
        String unquotedValue = unquoteIdentifier(value);
        values.add(unquotedValue);
        if (!isQuotedIdentifier(value)) {
            values.add(value.toUpperCase(Locale.ROOT));
            values.add(value.toLowerCase(Locale.ROOT));
            values.add(unquotedValue.toUpperCase(Locale.ROOT));
            values.add(unquotedValue.toLowerCase(Locale.ROOT));
        }
        return values;
    }

    String normalize(String value) {
        if (value == null) {
            return null;
        }
        String unquotedValue = unquoteIdentifier(value);
        return isQuotedIdentifier(value) ? unquotedValue : unquotedValue.toLowerCase(Locale.ROOT);
    }

    boolean containsMetadataName(Collection<String> actualNames, String expectedName) {
        for (String actualName : actualNames) {
            if (matchesMetadataName(expectedName, actualName)) {
                return true;
            }
        }
        return false;
    }

    MetadataNameIndex metadataNameIndex(Collection<String> actualNames) {
        return new MetadataNameIndex(actualNames);
    }

    String metadataLookupKey(String value) {
        if (value == null) {
            return null;
        }
        return unquoteIdentifier(value).toLowerCase(Locale.ROOT);
    }

    boolean matchesMetadataRow(String expectedCatalog, String expectedSchema, String expectedTableName,
                               String actualCatalog, String actualSchema, String actualTableName) {
        return matchesOptionalMetadataName(expectedCatalog, actualCatalog)
                && matchesOptionalMetadataName(expectedSchema, actualSchema)
                && matchesMetadataName(expectedTableName, actualTableName);
    }

    boolean matchesOptionalMetadataName(String expectedName, String actualName) {
        return isBlank(expectedName) || isBlank(actualName) || matchesMetadataName(expectedName, actualName);
    }

    boolean matchesMetadataName(String expectedName, String actualName) {
        if (expectedName == null || actualName == null) {
            return expectedName == actualName;
        }
        String expected = unquoteIdentifier(expectedName);
        String actual = unquoteIdentifier(actualName);
        if (expected.equals(actual)) {
            return true;
        }
        if (isQuotedIdentifier(expectedName) || isQuotedIdentifier(actualName)) {
            return false;
        }
        return expected.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT));
    }

    boolean isQuotedIdentifier(String value) {
        if (value == null || value.length() < 2) {
            return false;
        }
        return (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("`") && value.endsWith("`"))
                || (value.startsWith("[") && value.endsWith("]"));
    }

    String unquoteIdentifier(String value) {
        if (isQuotedIdentifier(value)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    class MetadataNameIndex {

        private boolean containsNull;

        private final Set<String> exactNames = new LinkedHashSet<>();

        private final Set<String> normalizedNames = new LinkedHashSet<>();

        MetadataNameIndex(Collection<String> actualNames) {
            for (String actualName : actualNames) {
                if (actualName == null) {
                    containsNull = true;
                    continue;
                }
                String actual = unquoteIdentifier(actualName);
                exactNames.add(actual);
                if (!isQuotedIdentifier(actualName)) {
                    normalizedNames.add(actual.toLowerCase(Locale.ROOT));
                }
            }
        }

        boolean contains(String expectedName) {
            if (expectedName == null) {
                return containsNull;
            }
            String expected = unquoteIdentifier(expectedName);
            if (exactNames.contains(expected)) {
                return true;
            }
            if (isQuotedIdentifier(expectedName)) {
                return false;
            }
            return normalizedNames.contains(expected.toLowerCase(Locale.ROOT));
        }
    }
}
