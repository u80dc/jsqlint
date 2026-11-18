package org.u80dc.jsqlint.samples.jdbcapi;

import java.sql.*;

/** H2 可执行场景与接口专用场景有独立驱动入口，不将未执行的用法计为数据库通过。 */
public final class JdbcApiApplication {
    public static void main(String[] args) throws SQLException {
        run();
        System.out.println("JDBC standard API H2 scenarios passed");
        System.out.println("namedCallable / inOutCallable require exerciseInterfaceOnly with a compatible driver or recording JDBC proxy");
    }

    public static void run() throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_standard_api", "sa", "")) {
            JdbcApiScenarios.createTables(connection);
            check(JdbcApiScenarios.createStatementHoldability(connection) == 101);
            check(JdbcApiScenarios.scrollingPrepared(connection) == 11);
            check(JdbcApiScenarios.plainCallable(connection) == 12);
            check(JdbcApiScenarios.holdableCallable(connection) == 13);
            check("SELECTABS(-14)".equals(JdbcApiScenarios.nativeSql(connection).replaceAll("\\s+", "")));
            check(JdbcApiScenarios.executeGeneratedFlag(connection) == 1L);
            check(JdbcApiScenarios.executeGeneratedIndexes(connection) == 2L);
            check(JdbcApiScenarios.executeGeneratedNames(connection) == 3L);
            check(JdbcApiScenarios.updateGeneratedFlag(connection) == 1);
            check(JdbcApiScenarios.updateGeneratedIndexes(connection) == 1);
            check(JdbcApiScenarios.updateGeneratedNames(connection) == 1);
            check(JdbcApiScenarios.largeUpdatePlain(connection) == 1L);
            check(JdbcApiScenarios.largeUpdateGeneratedFlag(connection) == 1L);
            check(JdbcApiScenarios.largeUpdateGeneratedIndexes(connection) == 1L);
            check(JdbcApiScenarios.largeUpdateGeneratedNames(connection) == 1L);
            try (var rows = JdbcApiScenarios.numericBindings(connection)) {
                check(rows.next());
                check(rows.getByte(1) == 7); check(rows.getShort(2) == 32000); check(rows.getLong(3) == 9_000_000_000L);
                check(rows.getFloat(4) == 1.25f); check(rows.getDouble(5) == 2.5d);
                check("plain object".equals(rows.getString(6))); check(rows.getInt(7) == 42); check(rows.getObject(8) == null);
            }
            try (var rows = JdbcApiScenarios.dynamicIndex(connection)) {
                check(rows.next()); check(rows.getInt(1) == 77); check(rows.getInt(2) == 88);
            }
            check(JdbcApiScenarios.countRows(connection) == 10);
            exerciseStates(connection);
        }
    }

    public static void exerciseStates(Connection connection) throws SQLException {
        check(JdbcStateScenarios.emptyStatementBatch(connection).length == 0);
        check(JdbcStateScenarios.emptyPreparedBatch(connection).length == 0);
        check(java.util.Arrays.equals(JdbcStateScenarios.statementConsecutiveBatches(connection),new int[]{1,1}));
        check(java.util.Arrays.equals(JdbcStateScenarios.preparedConsecutiveBatches(connection),new int[]{1,1}));
        check(java.util.Arrays.equals(JdbcStateScenarios.clearParametersAfterAdd(connection),new int[]{1,1}));
        check(java.util.Arrays.equals(JdbcStateScenarios.clearParametersThenBatchClear(connection),new int[]{1}));
        check(java.util.Arrays.equals(JdbcStateScenarios.clearBatchThenParameters(connection),new int[]{1,1}));
        check(JdbcStateScenarios.localAlias(connection) == 1);
        check(JdbcStateScenarios.fieldAlias(connection) == 1);
        check(JdbcStateScenarios.arrayAlias(connection) == 1);
        check(JdbcStateScenarios.wrappedFactory(connection) == 1);
        check(java.util.Arrays.equals(JdbcStateScenarios.transactionSavepoint(connection),new int[]{1,1,1}));
        check(JdbcStateScenarios.transactionRollback(connection) == 1);
        check(JdbcStateScenarios.generatedKeys(connection) > 0);
        check(java.util.Arrays.equals(JdbcStateScenarios.exceptionalBatchRetry(connection),new int[]{2,1,Statement.EXECUTE_FAILED,1}));
        check(java.util.Arrays.equals(JdbcStateScenarios.verifyRows(connection),new int[]{17,0,2,1}));
    }

    public static void exerciseInterfaceOnly(Connection connection) throws SQLException {
        JdbcApiScenarios.namedCallable(connection);
        JdbcApiScenarios.inOutCallable(connection);
    }

    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("JDBC standard API scenario assertion failed");
    }
}
