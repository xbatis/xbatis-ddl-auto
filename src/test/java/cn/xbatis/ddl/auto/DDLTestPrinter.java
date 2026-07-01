package cn.xbatis.ddl.auto;

import db.sql.api.IDbType;

import java.sql.SQLException;
import java.util.List;

final class DDLTestPrinter {

    private DDLTestPrinter() {
    }

    static DDLAuto ddl(IDbType dbType) {
        return ddl(dbType, null);
    }

    static DDLAuto ddl(IDbType dbType, List<String> executedSqlList) {
        return DDLAuto.of(dbType).executionListener(new DDLExecutionListener() {
            @Override
            public void beforeExecute(String sql, List<String> executedSqlList) {
                print("execute", sql);
            }

            @Override
            public void afterExecute(String sql, List<String> currentExecutedSqlList) {
                if (executedSqlList != null) {
                    executedSqlList.add(sql);
                }
            }

            @Override
            public void onExecuteError(String sql, SQLException exception, List<String> executedSqlList) {
                print("error", sql);
            }
        });
    }

    static List<String> printSqlList(String label, List<String> sqlList) {
        if (sqlList.isEmpty()) {
            System.out.println("[DDL][" + label + "] <empty>");
            return sqlList;
        }
        for (String sql : sqlList) {
            print(label, sql);
        }
        return sqlList;
    }

    static void print(String label, String sql) {
        System.out.println("[DDL][" + label + "] " + sql);
    }
}
