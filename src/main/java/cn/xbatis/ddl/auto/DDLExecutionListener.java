package cn.xbatis.ddl.auto;

import java.sql.SQLException;
import java.util.List;

/**
 * DDL 执行监听器。
 * <p>
 * 可用于记录实际执行过的 SQL，或在执行失败时输出当前失败 SQL 与已执行 SQL。
 */
public interface DDLExecutionListener {

    DDLExecutionListener NONE = new DDLExecutionListener() {
    };

    /**
     * SQL 执行前触发。
     *
     * @param sql             即将执行的 SQL
     * @param executedSqlList 本轮已成功执行的 SQL
     */
    default void beforeExecute(String sql, List<String> executedSqlList) {
    }

    /**
     * SQL 执行成功后触发。
     *
     * @param sql             已成功执行的 SQL
     * @param executedSqlList 本轮已成功执行的 SQL，包含当前 SQL
     */
    default void afterExecute(String sql, List<String> executedSqlList) {
    }

    /**
     * SQL 执行失败时触发。
     *
     * @param sql             当前失败的 SQL
     * @param exception       JDBC 抛出的异常
     * @param executedSqlList 本轮失败前已成功执行的 SQL
     */
    default void onExecuteError(String sql, SQLException exception, List<String> executedSqlList) {
    }
}
