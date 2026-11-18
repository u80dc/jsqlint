package org.u80dc.jsqlint.samples.jdbcmatrix;

import java.sql.*;

/** 独立 H2 入口，自动核验影响行数、批次顺序、存储过程输出与最终数据。 */
public final class JdbcUsageApplication {
    public static void main(String[] args) throws SQLException {
        run();
        System.out.println("JDBC usage matrix passed");
    }

    public static void run() throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_usage", "sa", "")) {
            JdbcScenarios.createTable(connection);
            check(JdbcScenarios.overwriteAndClear(connection) == 1);
            check(java.util.Arrays.equals(JdbcScenarios.preparedBatch(connection), new int[]{1, 1}));
            check(java.util.Arrays.equals(JdbcScenarios.statementBatch(connection), new int[]{1, 1}));
            check(java.util.Arrays.equals(JdbcScenarios.preparedLargeBatch(connection), new long[]{1, 1}));
            check(java.util.Arrays.equals(JdbcScenarios.statementLargeBatch(connection), new long[]{1, 1}));
            check(JdbcScenarios.largeUpdate(connection) == 1);
            check(JdbcScenarios.callable(connection) == 7);
            try (var rows = JdbcScenarios.scrollingQuery(connection)) {
                check(rows.next()); check(rows.getInt("id") == 1); check("O'Neil".equals(rows.getString("name")));
                check(rows.getObject("score") == null);
                check(rows.next()); check(rows.getInt("id") == 11); check(rows.getInt("score") == 110);
                check(rows.next()); check(rows.getInt("id") == 12); check("second".equals(rows.getString("name")));
                check(rows.last()); check(rows.getRow() == 9); check(rows.getInt("id") == 42);
            }
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("JDBC scenario assertion failed");
    }
}
