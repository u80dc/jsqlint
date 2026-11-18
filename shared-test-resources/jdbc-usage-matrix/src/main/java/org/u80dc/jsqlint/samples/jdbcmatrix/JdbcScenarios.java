package org.u80dc.jsqlint.samples.jdbcmatrix;

import java.sql.*;

/** 每个方法由独立入口实际调用；过期值、清除项和批次快照用于验证执行时状态。 */
public final class JdbcScenarios {
    public static void createTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE jdbc_usage (id INTEGER PRIMARY KEY, name VARCHAR(80), score INTEGER)");
        }
    }

    public static int overwriteAndClear(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_usage VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, 999);
            statement.setString(2, "cleared");
            statement.setInt(3, 999);
            statement.clearParameters();
            statement.setInt(1, 1);
            statement.setString(2, "overwritten");
            statement.setObject(2, "O'Neil", JDBCType.VARCHAR);
            statement.setNull(3, Types.INTEGER);
            int count = statement.executeUpdate();
            statement.setString(2, "too late");
            return count;
        }
    }

    public static int[] preparedBatch(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_usage (id, name) VALUES (?, ?)")) {
            statement.setInt(1, 999); statement.setString(2, "removed"); statement.addBatch();
            statement.clearBatch();
            statement.setInt(1, 11); statement.setString(2, "first"); statement.addBatch();
            statement.setInt(1, 12); statement.setString(2, "second"); statement.addBatch();
            statement.setInt(1, 777); statement.setString(2, "not queued");
            return statement.executeBatch();
        }
    }

    public static int[] statementBatch(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.addBatch("DELETE FROM jdbc_usage"); statement.clearBatch();
            statement.addBatch("INSERT INTO jdbc_usage (id, name) VALUES (21, 'plain first')");
            statement.addBatch("INSERT INTO jdbc_usage (id, name) VALUES (22, 'plain second')");
            return statement.executeBatch();
        }
    }

    public static long[] preparedLargeBatch(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO jdbc_usage (id, score) VALUES (?, ?)", new String[]{"ID"})) {
            statement.setInt(1, 31); statement.setObject(2, 310, Types.INTEGER); statement.addBatch();
            statement.setInt(1, 32); statement.setObject(2, 320, Types.INTEGER); statement.addBatch();
            return statement.executeLargeBatch();
        }
    }

    public static long[] statementLargeBatch(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.addBatch("INSERT INTO jdbc_usage (id, name) VALUES (41, 'large first')");
            statement.addBatch("INSERT INTO jdbc_usage (id, name) VALUES (42, 'large second')");
            return statement.executeLargeBatch();
        }
    }

    public static long largeUpdate(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE jdbc_usage SET score=? WHERE id=?", new int[]{1})) {
            statement.setObject(1, 110, JDBCType.INTEGER, 0); statement.setInt(2, 11);
            return statement.executeLargeUpdate();
        }
    }

    public static int callable(Connection connection) throws SQLException {
        try (var statement = connection.prepareCall("{? = call ABS(?)}", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.registerOutParameter(1, Types.INTEGER);
            statement.setInt(2, -7);
            statement.execute();
            return statement.getInt(1);
        }
    }

    public static ResultSet scrollingQuery(Connection connection) throws SQLException {
        var statement = connection.prepareStatement("SELECT id, name, score FROM jdbc_usage ORDER BY id",
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
        statement.closeOnCompletion();
        return statement.executeQuery();
    }
}
