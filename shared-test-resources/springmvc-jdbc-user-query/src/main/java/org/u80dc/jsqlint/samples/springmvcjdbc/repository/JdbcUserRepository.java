package org.u80dc.jsqlint.samples.springmvcjdbc.repository;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;
import org.u80dc.jsqlint.samples.springmvcjdbc.model.User;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 数据访问层，只有两个业务查询方法，直接调用 JDBC API 以便定位 SQL 调用点。
 * 每次调用独立获取连接，并用 try-with-resources 关闭连接、语句和结果集。
 *
 * @author u80dc
 */
@Repository
public class JdbcUserRepository {
    private static final String SELECT_USERS = "SELECT id, username, email, age, active FROM app_users";
    private final DataSource dataSource;

    public JdbcUserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 查询一：long 参数直接参与 + 拼接，通过 Statement 执行完整 SQL。 */
    public Optional<User> findById(long id) {
        String sql = SELECT_USERS + " WHERE id = " + id;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? Optional.of(readUser(rows)) : Optional.empty();
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException("按 ID 查询用户失败", exception);
        }
    }

    /** 查询二：动态条件及排序/LIMIT 拼接，与字符串、整数、布尔参数绑定混合使用。 */
    public List<User> findUsers(String namePattern, int minimumAge, boolean activeOnly, boolean orderByAge, int limit) {
        String sql = SELECT_USERS + " WHERE username LIKE ? ESCAPE '!' AND age >= ?";
        if (activeOnly) {
            sql += " AND active = ?";
        }
        // 拼接的排序片段来自内部常量，不直接拼接 HTTP 请求中的 sort 文本。
        String orderBy = orderByAge ? "age ASC, id ASC" : "id ASC";
        sql += " ORDER BY " + orderBy + " LIMIT " + limit;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namePattern);
            statement.setInt(2, minimumAge);
            if (activeOnly) {
                // 绑定和 SQL 条件由同一个布尔变量控制，便于验证联合路径一致性。
                statement.setBoolean(3, activeOnly);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<User> users = new ArrayList<>();
                while (rows.next()) {
                    users.add(readUser(rows));
                }
                return List.copyOf(users);
            }
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException("按条件查询用户失败", exception);
        }
    }

    private static User readUser(ResultSet rows) throws SQLException {
        return new User(rows.getLong("id"), rows.getString("username"), rows.getString("email"),
                rows.getInt("age"), rows.getBoolean("active"));
    }
}
