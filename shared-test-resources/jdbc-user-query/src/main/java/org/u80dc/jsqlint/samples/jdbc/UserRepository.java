package org.u80dc.jsqlint.samples.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC 字符串拼接样例，保留清晰的 SQL 构造过程与执行点，便于后续验证静态分析。
 * Connection 由调用方持有；每个方法关闭自己创建的 Statement 和 ResultSet。
 *
 * @author u80dc
 */
public final class UserRepository {
    private static final String TABLE = "app_users";
    private static final String COLUMNS = "id, username, email, age, active";
    private final Connection connection;

    public UserRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** 仅用于初始化样例数据库；这是额外的 DDL 执行点。 */
    public void createTable() throws SQLException {
        String sql = "CREATE TABLE " + TABLE + " ("
                + "id BIGINT PRIMARY KEY, "
                + "username VARCHAR(100) NOT NULL, "
                + "email VARCHAR(200) NOT NULL, "
                + "age INTEGER NOT NULL, "
                + "active BOOLEAN NOT NULL)";
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** INSERT：多行 + 拼接，字符串、整数及布尔参数共同构成 VALUES。 */
    public int insert(long id, String username, String email, int age, boolean active) throws SQLException {
        String sql = "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES ("
                + id + ", " + quote(username) + ", " + quote(email) + ", "
                + age + ", " + active + ")";
        try (var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /** SELECT：固定前缀与数值参数拼接，作为最简单的完整 SQL 提取样例。 */
    public Optional<User> findById(long id) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = " + id;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            return rows.next() ? Optional.of(readUser(rows)) : Optional.empty();
        }
    }

    /**
     * SELECT：根据参数追加不同条件，覆盖分支合流和 += 拼接。
     * keyword 为 null 或空白时不过滤用户名，minimumAge 为 null 时不过滤年龄。
     */
    public List<User> findUsers(String keyword, Integer minimumAge, boolean activeOnly) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE 1 = 1";
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND username LIKE " + quote("%" + keyword + "%");
        }
        if (minimumAge != null) {
            sql += " AND age >= " + minimumAge;
        }
        if (activeOnly) {
            sql += " AND active = TRUE";
        }
        sql += " ORDER BY id";
        // 整条 SQL 在 prepareStatement 前已拼好，没有参数占位符。
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            List<User> users = new ArrayList<>();
            while (rows.next()) users.add(readUser(rows));
            return List.copyOf(users);
        }
    }

    /** UPDATE：String.concat 与 + 混用，覆盖分段拼接后的 prepareStatement 调用。 */
    public int update(long id, String email, int age, boolean active) throws SQLException {
        String sql = ("UPDATE " + TABLE)
                .concat(" SET email = " + quote(email))
                .concat(", age = " + age)
                .concat(", active = " + active)
                .concat(" WHERE id = " + id);
        try (var statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    /** DELETE：先构造条件片段，再与前缀拼接。 */
    public int deleteById(long id) throws SQLException {
        String condition = " WHERE id = " + id;
        String sql = "DELETE FROM " + TABLE + condition;
        try (var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /** H2 字符串字面量中的单引号以两个单引号表示，保证姓名等文本不会破坏拼接后的 SQL。 */
    private static String quote(String value) {
        return "'" + Objects.requireNonNull(value, "SQL text value").replace("'", "''") + "'";
    }

    private static User readUser(ResultSet rows) throws SQLException {
        return new User(rows.getLong("id"), rows.getString("username"), rows.getString("email"),
                rows.getInt("age"), rows.getBoolean("active"));
    }
}
