package cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.entity.User;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
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

    private static final String FIND_BY_USERNAME_SQL = """
            SELECT id, username, email, status, age, created_at, updated_at
            FROM users
            WHERE username = ?
            """;

    private static final String DELETE_BY_ID_SQL = "DELETE FROM users WHERE id = ?";

    private static final RowMapper<User> USER_ROW_MAPPER = new BeanPropertyRowMapper<>(User.class);

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    public User create(User user) {
        jdbcTemplate.update(INSERT_SQL, user.getUsername(), user.getEmail(), user.getStatus(), user.getAge());
        return findByUsername(user.getUsername()).orElse(user);
    }

    public List<User> createAll(List<User> users) {
        for (User user : users) {
            jdbcTemplate.update(INSERT_SQL, user.getUsername(), user.getEmail(), user.getStatus(), user.getAge());
        }
        return users;
    }

    public Optional<User> findById(long id) {
        List<User> users = jdbcTemplate.query(FIND_BY_ID_SQL, USER_ROW_MAPPER, id);
        return users.stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        List<User> users = jdbcTemplate.query(FIND_BY_USERNAME_SQL, USER_ROW_MAPPER, username);
        return users.stream().findFirst();
    }

    public List<User> search(UserSearchDTO criteria) {
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
        return jdbcTemplate.query(sql.toString(), USER_ROW_MAPPER, parameters.toArray());
    }

    public List<User> searchWithNamedParameters(UserSearchDTO criteria) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, username, email, status, age, created_at, updated_at
                FROM users
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();

        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            predicates.add("status = :status");
            parameters.addValue("status", criteria.getStatus());
        }
        if (criteria.getMinAge() != null) {
            predicates.add("age >= :minAge");
            parameters.addValue("minAge", criteria.getMinAge());
        }
        if (criteria.getMaxAge() != null) {
            predicates.add("age <= :maxAge");
            parameters.addValue("maxAge", criteria.getMaxAge());
        }
        if (!criteria.getIds().isEmpty()) {
            predicates.add("id IN (:ids)");
            parameters.addValue("ids", criteria.getIds());
        }
        if (!criteria.getKeywords().isEmpty()) {
            StringBuilder keywordClause = new StringBuilder("(");
            for (int i = 0; i < criteria.getKeywords().size(); i++) {
                if (i > 0) {
                    keywordClause.append(" OR ");
                }
                String parameterName = "keyword" + i;
                keywordClause.append("(LOWER(username) LIKE :")
                        .append(parameterName)
                        .append(" OR LOWER(email) LIKE :")
                        .append(parameterName)
                        .append(')');
                parameters.addValue(parameterName, "%" + criteria.getKeywords().get(i).toLowerCase() + "%");
            }
            keywordClause.append(')');
            predicates.add(keywordClause.toString());
        }

        appendWhere(sql, predicates);
        appendOrder(sql, criteria.getOrderBy(), criteria.isDescending());
        return namedJdbcTemplate.query(sql.toString(), parameters, USER_ROW_MAPPER);
    }

    public boolean update(User user, boolean includeEmail, boolean includeAge) {
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

        return jdbcTemplate.update(sql.toString(), parameters.toArray()) == 1;
    }

    public int updateStatusWithNamedParameters(Long id, String status) {
        String sql = """
                UPDATE users
                SET status = :status, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status);
        return namedJdbcTemplate.update(sql, parameters);
    }

    public int deleteById(long id) {
        return jdbcTemplate.update(DELETE_BY_ID_SQL, id);
    }

    public int deleteByStatus(Collection<String> statuses, boolean requireInactive) {
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
        if (predicates.isEmpty()) {
            return 0;
        }

        appendWhere(sql, predicates);
        return jdbcTemplate.update(sql.toString(), parameters.toArray());
    }

    public int countByStatusUsingLiteral(String status) {
        String sql = "SELECT COUNT(*) FROM users";
        if (status != null && !status.isBlank()) {
            sql += " WHERE status = '" + escapeSqlLiteral(status) + "'";
        }

        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    public int updateStatusUsingLiteral(List<Long> ids, String status) {
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

        return jdbcTemplate.update(sql.toString());
    }

    public int deleteByStatusesUsingLoop(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return 0;
        }

        int affectedRows = 0;
        for (String status : statuses) {
            String sql = "DELETE FROM users WHERE status = '" + escapeSqlLiteral(status) + "'";
            affectedRows += jdbcTemplate.update(sql);
        }
        return affectedRows;
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

    private String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
