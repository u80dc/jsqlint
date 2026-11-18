package org.u80dc.jsqlint.samples.jdbcapi;

import java.sql.*;

/**
 * 第二批状态样例。由主入口建立 jdbc_state / jdbc_generated；每个方法都有固定的执行顺序。
 * 字段/数组别名及异常路径仍应保留分析器的 UNKNOWN/PARTIAL 诊断，不以数据库可执行代替路径证明。
 */
public final class JdbcStateScenarios {
    private JdbcStateScenarios() {}

    /** 确定清除的 Statement 批次为空，不能恢复成先前已移除的 SQL。 */
    public static int[] emptyStatementBatch(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.addBatch("INSERT INTO jdbc_state VALUES ('empty_statement_removed', 901)");
            statement.clearBatch();
            return statement.executeBatch();
        }
    }

    /** PreparedStatement 工厂 SQL 存在，但空批次没有执行 SQL。 */
    public static int[] emptyPreparedBatch(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_state VALUES ('empty_prepared_removed', ?)")) {
            statement.setInt(1, 902); statement.addBatch();
            statement.clearBatch();
            return statement.executeBatch();
        }
    }

    /** 正常 executeBatch 清除已提交的批次，第二次执行仅包含后来加入的项。 */
    public static int[] statementConsecutiveBatches(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.addBatch("INSERT INTO jdbc_state VALUES ('statement_first', 11)");
            int[] first = statement.executeBatch();
            statement.addBatch("INSERT INTO jdbc_state VALUES ('statement_second', 12)");
            int[] second = statement.executeBatch();
            return new int[]{first[0], second[0]};
        }
    }

    /** 正常执行清空批次，但不清除 PreparedStatement 的当前位置绑定。 */
    public static int[] preparedConsecutiveBatches(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_state VALUES ('prepared_retained', ?)")) {
            statement.setInt(1, 21); statement.addBatch();
            int[] first = statement.executeBatch();
            statement.addBatch();
            int[] second = statement.executeBatch();
            return new int[]{first[0], second[0]};
        }
    }

    /** clearParameters 仅清除当前绑定，已经 addBatch 的快照仍有效。 */
    public static int[] clearParametersAfterAdd(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_state VALUES ('clear_parameters_after_add', ?)")) {
            statement.setInt(1, 31); statement.addBatch();
            statement.clearParameters();
            statement.setInt(1, 32); statement.addBatch();
            return statement.executeBatch();
        }
    }

    /** clearParameters 后再 clearBatch，两个已加入项均移除，新绑定才能成为新批次。 */
    public static int[] clearParametersThenBatchClear(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_state VALUES ('clear_parameters_then_batch', ?)")) {
            statement.setInt(1, 903); statement.addBatch();
            statement.clearParameters();
            statement.setInt(1, 904); statement.addBatch();
            statement.clearBatch();
            statement.setInt(1, 33); statement.addBatch();
            return statement.executeBatch();
        }
    }

    /** clearBatch 不清除当前位置绑定；随后 clearParameters 后必须重新绑定。 */
    public static int[] clearBatchThenParameters(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_state VALUES ('clear_batch_then_parameters', ?)")) {
            statement.setInt(1, 905); statement.addBatch();
            statement.clearBatch();
            statement.addBatch();
            statement.clearParameters();
            statement.setInt(1, 34); statement.addBatch();
            return statement.executeBatch();
        }
    }

    public static int localAlias(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_state VALUES ('local_alias', ?)")) {
            PreparedStatement alias = statement;
            alias.setInt(1, 41);
            return statement.executeUpdate();
        }
    }

    /** 用封装入口演示 Statement 字段写入/读取，方便独立回放同一个真实对象。 */
    public static int fieldAlias(Connection connection) throws SQLException {
        var holder = new StatementHolder();
        holder.initialize(connection);
        try (var statement = holder.statement) {
            holder.statement.setInt(1, 42);
            return statement.executeUpdate();
        }
    }

    public static int arrayAlias(Connection connection) throws SQLException {
        PreparedStatement[] statements = {connection.prepareStatement("INSERT INTO jdbc_state VALUES ('array_alias', ?)")};
        PreparedStatement[] alias = statements;
        try (var statement = statements[0]) {
            alias[0].setInt(1, 43);
            return statement.executeUpdate();
        }
    }

    /** 接口返回 PreparedStatement，实际工厂方法委托标准 prepareStatement。 */
    public static int wrappedFactory(Connection connection) throws SQLException {
        PreparedFactory factory = new StandardFactory();
        try (var statement = factory.prepare(connection, "INSERT INTO jdbc_state VALUES ('wrapped_factory', ?)")) {
            statement.setInt(1, 44);
            return statement.executeUpdate();
        }
    }

    /** 实际提交 tx_kept / tx_committed；tx_reverted 的 SQL 已执行但数据库修改被 savepoint 回滚。 */
    public static int[] transactionSavepoint(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            int kept = statement.executeUpdate("INSERT INTO jdbc_state VALUES ('tx_kept', 71)");
            Savepoint point = connection.setSavepoint("jdbc_state_point");
            int reverted = statement.executeUpdate("INSERT INTO jdbc_state VALUES ('tx_reverted', 72)");
            connection.rollback(point);
            int committed = statement.executeUpdate("INSERT INTO jdbc_state VALUES ('tx_committed', 73)");
            connection.releaseSavepoint(point);
            connection.commit();
            return new int[]{kept, reverted, committed};
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** 完整 rollback；SQL 提取仍应报告该实际执行 SQL，但最终数据库没有 tx_full_rollback。 */
    public static int transactionRollback(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            int count = statement.executeUpdate("INSERT INTO jdbc_state VALUES ('tx_full_rollback', 81)");
            connection.rollback();
            return count;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public static long generatedKeys(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_generated (label) VALUES ('generated_key')", Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Expected an H2 generated key");
                return keys.getLong(1);
            }
        }
    }

    /**
     * H2 专用失败/重试例：首批一项成功、一项失败，updateCounts=[1, EXECUTE_FAILED]。
     * 显式 clearBatch 后才重试，不假设其他驱动异常后清空或保留队列。
     * 分析时首次异常执行和重试路径应保留 UNKNOWN，不将故障分支伪称为已证明可行。
     */
    public static int[] exceptionalBatchRetry(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.addBatch("INSERT INTO jdbc_state VALUES ('retry_first', 91)");
            statement.addBatch("INSERT INTO jdbc_missing_retry (row_value) VALUES (92)");
            int[] failedCounts;
            try {
                statement.executeBatch();
                throw new SQLException("Expected the intentionally missing H2 table to fail");
            } catch (BatchUpdateException failure) {
                failedCounts = failure.getUpdateCounts();
            }
            statement.clearBatch();
            statement.addBatch("INSERT INTO jdbc_state VALUES ('retry_after_clear', 93)");
            int[] retried = statement.executeBatch();
            return new int[]{failedCounts.length, failedCounts[0], failedCounts[1], retried[0]};
        }
    }

    private static final class StatementHolder {
        private PreparedStatement statement;
        private void initialize(Connection connection) throws SQLException {
            statement = connection.prepareStatement("INSERT INTO jdbc_state VALUES ('field_alias', ?)");
        }
    }

    /** SQL 执行与最终数据分开核验：被回滚的 SQL 仍应保留在提取结果中。 */
    public static int[] verifyRows(Connection connection) throws SQLException {
        int total, removed, retained, keys;
        try (var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT COUNT(*) FROM jdbc_state")) {
            rows.next(); total=rows.getInt(1);
        }
        try (var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT COUNT(*) FROM jdbc_state WHERE case_name IN ('tx_reverted', 'tx_full_rollback', 'empty_statement_removed', 'empty_prepared_removed')")) {
            rows.next(); removed=rows.getInt(1);
        }
        try (var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT COUNT(*) FROM jdbc_state WHERE case_name='prepared_retained' AND row_value=21")) {
            rows.next(); retained=rows.getInt(1);
        }
        try (var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT COUNT(*) FROM jdbc_generated WHERE label='generated_key'")) {
            rows.next(); keys=rows.getInt(1);
        }
        return new int[]{total,removed,retained,keys};
    }

    private interface PreparedFactory {
        PreparedStatement prepare(Connection connection, String sql) throws SQLException;
    }

    private static final class StandardFactory implements PreparedFactory {
        @Override
        public PreparedStatement prepare(Connection connection, String sql) throws SQLException {
            return connection.prepareStatement(sql);
        }
    }
}
