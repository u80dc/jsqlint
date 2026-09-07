package cn.jnu.zhangcs.jsqlint.analysis.fixture.nativejdbc;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.nativejdbc.dto.UserSearchCriteria;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.nativejdbc.entity.User;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 数据访问层
 * <p>
 * 包含多种 JDBC SQL 调用形态，供提取与参数推断测试使用。
 * </p>
 *
 * @author zhangcs
 */
public class UserRepository {

    private static final String INSERT_SQL = """
            INSERT INTO users (username, email, status, age)
            VALUES (?, ?, ?, ?)
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, username, email, status, age, created_at, updated_at
            FROM users
            WHERE id = ?
            """;

    private static final String DELETE_BY_ID_SQL = "DELETE FROM users WHERE id = ?";

    private final Connection connection;

    public UserRepository(Connection connection) {
        this.connection = connection;
    }

    public User create(User user) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            bindRequiredString(statement, 1, user.getUsername());
            bindRequiredString(statement, 2, user.getEmail());
            bindRequiredString(statement, 3, user.getStatus());
            bindNullableInteger(statement, 4, user.getAge());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    user.setId(keys.getLong(1));
                }
            }
        }
        return findById(user.getId()).orElseThrow();
    }

    public List<User> createAll(List<User> users) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            for (User user : users) {
                bindRequiredString(statement, 1, user.getUsername());
                bindRequiredString(statement, 2, user.getEmail());
                bindRequiredString(statement, 3, user.getStatus());
                bindNullableInteger(statement, 4, user.getAge());
                statement.addBatch();
            }
            statement.executeBatch();

            int index = 0;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                while (keys.next() && index < users.size()) {
                    users.get(index).setId(keys.getLong(1));
                    index++;
                }
            }
        }
        return users;
    }

    public Optional<User> findById(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_ID_SQL)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapRow(resultSet));
                }
                return Optional.empty();
            }
        }
    }

    public List<User> search(UserSearchCriteria criteria) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT id, username, email, status, age, created_at, updated_at
                FROM users
                """);
        List<Object> parameters = new ArrayList<>();
        List<String> predicates = new ArrayList<>();

        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            predicates.add("status = ?");
            parameters.add(criteria.getStatus());
        }
        if (criteria.getMinAge() != null) {
            predicates.add("age >= ?");
            parameters.add(criteria.getMinAge());
        }
        if (criteria.getMaxAge() != null) {
            predicates.add("age <= ?");
            parameters.add(criteria.getMaxAge());
        }
        if (!criteria.getIds().isEmpty()) {
            StringBuilder inClause = new StringBuilder("id IN (");
            for (int i = 0; i < criteria.getIds().size(); i++) {
                if (i > 0) {
                    inClause.append(", ");
                }
                inClause.append('?');
                parameters.add(criteria.getIds().get(i));
            }
            inClause.append(')');
            predicates.add(inClause.toString());
        }
        if (!criteria.getKeywords().isEmpty()) {
            StringBuilder keywordClause = new StringBuilder("(");
            for (int i = 0; i < criteria.getKeywords().size(); i++) {
                if (i > 0) {
                    keywordClause.append(" OR ");
                }
                keywordClause.append("(LOWER(username) LIKE ? OR LOWER(email) LIKE ?)");
                String pattern = "%" + criteria.getKeywords().get(i).toLowerCase() + "%";
                parameters.add(pattern);
                parameters.add(pattern);
            }
            keywordClause.append(')');
            predicates.add(keywordClause.toString());
        }

        appendWhere(sql, predicates);
        appendOrder(sql, criteria.getOrderBy(), criteria.isDescending());
        return queryUsers(sql.toString(), parameters);
    }

    public boolean update(User user, boolean includeEmail, boolean includeAge) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE users SET ");
        List<Object> parameters = new ArrayList<>();
        List<String> assignments = new ArrayList<>();

        assignments.add("username = ?");
        parameters.add(user.getUsername());

        assignments.add("status = ?");
        parameters.add(user.getStatus());

        if (includeEmail && user.getEmail() != null) {
            assignments.add("email = ?");
            parameters.add(user.getEmail());
        }
        if (includeAge) {
            assignments.add("age = ?");
            parameters.add(user.getAge());
        }

        List<String> auditAssignments = List.of("updated_at = ?");
        for (String auditAssignment : auditAssignments) {
            assignments.add(auditAssignment);
            parameters.add(LocalDateTime.now());
        }

        for (int i = 0; i < assignments.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(assignments.get(i));
        }
        sql.append(" WHERE id = ?");
        parameters.add(user.getId());

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, parameters);
            return statement.executeUpdate() == 1;
        }
    }

    public int deleteById(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_BY_ID_SQL)) {
            statement.setLong(1, id);
            return statement.executeUpdate();
        }
    }

    /**
     * 演示常量绑定：状态为固定值，不依赖任何形参。
     */
    public int deleteByFixedStatus(long minId) throws SQLException {
        String sql = "DELETE FROM users WHERE status = ? AND id >= ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "DISABLED");
            statement.setLong(2, minId);
            return statement.executeUpdate();
        }
    }

    /**
     * 演示分支多定义：if/else 各分支对同一变量赋不同值后在调用点使用。
     */
    public int deleteByBranchStatus(long id, boolean locked) throws SQLException {
        String status = "DEFAULT";
        if (locked) {
            status = "LOCKED";
        } else {
            status = "ACTIVE";
        }
        String sql = "DELETE FROM users WHERE status = ? AND id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, id);
            return statement.executeUpdate();
        }
    }

    /**
     * 演示嵌套二分支：两个 if/else 菱形共同为同一绑定提供三个候选值。
     */
    public int deleteByThreeBranchStatus(long id, boolean locked, boolean pending) throws SQLException {
        String status = "DEFAULT";
        if (locked) {
            status = "LOCKED";
        } else if (pending) {
            status = "PENDING";
        } else {
            status = "ACTIVE";
        }
        String sql = "DELETE FROM users WHERE status = ? AND id = ? AND age >= 0";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, id);
            return statement.executeUpdate();
        }
    }

    /**
     * 普通顺序重赋值不属于分支特判，绑定点应保持原有的最近定义语义。
     */
    public int deleteBySequentialStatus(long id) throws SQLException {
        String status = "INITIAL";
        status = "FINAL";
        String sql = "DELETE FROM users WHERE status = ? AND id > ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, id);
            return statement.executeUpdate();
        }
    }

    /**
     * 演示能力边界：SQL 文本来自辅助方法返回值，过程内不求值返回值时无法解析，
     * 提取结果应携带诊断而非静默丢弃该调用点。
     */
    public int deleteByDynamicStatus(long id, String tenant) throws SQLException {
        String sql = buildDeleteSql(tenant);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate();
        }
    }

    private String buildDeleteSql(String tenant) {
        return "DELETE FROM users WHERE tenant = '" + tenant + "' AND id = ?";
    }

    /**
     * 演示 switch 多分支：到达定义分析将各 case 的赋值汇聚为候选集，
     * 绑定值应收集全部 case 值（if/else 特判不覆盖 switch 形态）。
     */
    public int deleteBySwitchStatus(long id, int level) throws SQLException {
        String status;
        switch (level) {
            case 1 -> status = "LOW";
            case 2 -> status = "MEDIUM";
            default -> status = "HIGH";
        }
        String sql = "DELETE FROM users WHERE status = ? AND id < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, id);
            return statement.executeUpdate();
        }
    }

    public int deleteByStatus(Collection<String> statuses, boolean requireInactive) throws SQLException {
        StringBuilder sql = new StringBuilder("DELETE FROM users");
        List<Object> parameters = new ArrayList<>();
        List<String> predicates = new ArrayList<>();

        if (requireInactive) {
            predicates.add("status <> ?");
            parameters.add("ACTIVE");
        }
        if (statuses != null && !statuses.isEmpty()) {
            StringBuilder statusClause = new StringBuilder("status IN (");
            int index = 0;
            for (String status : statuses) {
                if (index > 0) {
                    statusClause.append(", ");
                }
                statusClause.append('?');
                parameters.add(status);
                index++;
            }
            statusClause.append(')');
            predicates.add(statusClause.toString());
        }

        appendWhere(sql, predicates);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, parameters);
            return statement.executeUpdate();
        }
    }

    public int countByStatusUsingStatement(String status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users";
        if (status != null && !status.isBlank()) {
            sql += " WHERE status = '" + escapeSqlLiteral(status) + "'";
        }

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                return resultSet.getInt(1);
            }
            return 0;
        }
    }

    public int updateStatusUsingStatement(List<Long> ids, String status) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        StringBuilder sql = new StringBuilder("UPDATE users SET status = '");
        sql.append(escapeSqlLiteral(status));
        sql.append("', updated_at = CURRENT_TIMESTAMP WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(ids.get(i));
        }
        sql.append(')');

        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql.toString());
        }
    }

    public int deleteByStatusesUsingStatementBatch(Collection<String> statuses) throws SQLException {
        if (statuses == null || statuses.isEmpty()) {
            return 0;
        }

        try (Statement statement = connection.createStatement()) {
            for (String status : statuses) {
                String sql = "DELETE FROM users WHERE status = '" + escapeSqlLiteral(status) + "'";
                statement.addBatch(sql);
            }

            int affectedRows = 0;
            for (int updated : statement.executeBatch()) {
                if (updated > 0) {
                    affectedRows += updated;
                }
            }
            return affectedRows;
        }
    }

    private List<User> queryUsers(String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<User> users = new ArrayList<>();
                while (resultSet.next()) {
                    users.add(mapRow(resultSet));
                }
                return users;
            }
        }
    }

    private void appendWhere(StringBuilder sql, List<String> predicates) {
        if (predicates.isEmpty()) {
            return;
        }
        sql.append(" WHERE ");
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(predicates.get(i));
        }
    }

    private void appendOrder(StringBuilder sql, String orderBy, boolean descending) {
        String column = switch (orderBy == null ? "" : orderBy) {
            case "username" -> "username";
            case "age" -> "age";
            case "createdAt" -> "created_at";
            default -> "id";
        };
        sql.append(" ORDER BY ").append(column);
        if (descending) {
            sql.append(" DESC");
        } else {
            sql.append(" ASC");
        }
    }

    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Object value = parameters.get(i);
            int parameterIndex = i + 1;
            if (value instanceof Integer integer) {
                statement.setInt(parameterIndex, integer);
            } else if (value instanceof Long longValue) {
                statement.setLong(parameterIndex, longValue);
            } else if (value instanceof LocalDateTime localDateTime) {
                statement.setTimestamp(parameterIndex, Timestamp.valueOf(localDateTime));
            } else if (value == null) {
                statement.setNull(parameterIndex, Types.NULL);
            } else {
                statement.setString(parameterIndex, value.toString());
            }
        }
    }

    private void bindRequiredString(PreparedStatement statement, int index, String value) throws SQLException {
        statement.setString(index, value);
    }

    private void bindNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private User mapRow(ResultSet resultSet) throws SQLException {
        return new User(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                resultSet.getString("email"),
                resultSet.getString("status"),
                getNullableInteger(resultSet, "age"),
                getNullableDateTime(resultSet, "created_at"),
                getNullableDateTime(resultSet, "updated_at"));
    }

    private Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private LocalDateTime getNullableDateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
