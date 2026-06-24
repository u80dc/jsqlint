package cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.service;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 业务层
 *
 * @author zhangcs
 */
@Service
@Transactional
@SuppressWarnings("unchecked")
public class UserQueryService {

    private final EntityManager entityManager;

    public UserQueryService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<User> findActiveUsers() {
        String jpql = "select u from User u";
        TypedQuery<User> query = entityManager.createQuery(jpql + " where u.status = :status", User.class);
        query.setParameter("status", "ACTIVE");
        return query.getResultList();
    }

    @Transactional(readOnly = true)
    public List<User> findNativeById(long id) {
        String sql = "SELECT id, username, email, status, age, created_at, updated_at FROM users";
        sql += " WHERE id = ?";
        Query query = entityManager.createNativeQuery(sql, User.class);
        query.setParameter(1, id);
        return query.getResultList();
    }

    @Transactional(readOnly = true)
    public List<User> search(UserSearchDTO criteria) {
        StringBuilder jpql = new StringBuilder("select u from User u");
        List<String> predicates = new ArrayList<>();

        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            predicates.add("u.status = :status");
        }
        if (criteria.getMinAge() != null) {
            predicates.add("u.age >= :minAge");
        }
        if (criteria.getMaxAge() != null) {
            predicates.add("u.age <= :maxAge");
        }
        if (!criteria.getIds().isEmpty()) {
            predicates.add("u.id in :ids");
        }
        if (!criteria.getKeywords().isEmpty()) {
            StringBuilder keywordClause = new StringBuilder("(");
            for (int i = 0; i < criteria.getKeywords().size(); i++) {
                if (i > 0) {
                    keywordClause.append(" or ");
                }
                keywordClause.append("(lower(u.username) like :keyword")
                        .append(i)
                        .append(" or lower(u.email) like :keyword")
                        .append(i)
                        .append(')');
            }
            keywordClause.append(')');
            predicates.add(keywordClause.toString());
        }

        appendWhere(jpql, predicates);
        appendOrder(jpql, criteria.getOrderBy(), criteria.isDescending(), "u.");

        TypedQuery<User> query = entityManager.createQuery(jpql.toString(), User.class);
        bindSearchParameters(query, criteria);
        return query.getResultList();
    }

    @Transactional(readOnly = true)
    public List<User> searchNative(UserSearchDTO criteria) {
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
        appendOrder(sql, criteria.getOrderBy(), criteria.isDescending(), "");

        Query query = entityManager.createNativeQuery(sql.toString(), User.class);
        bindPositionalParameters(query, parameters);
        return query.getResultList();
    }

    public int updateDynamic(User user, boolean includeEmail, boolean includeAge) {
        StringBuilder jpql = new StringBuilder("update User u set ");
        List<String> assignments = new ArrayList<>();

        assignments.add("u.username = :username");
        assignments.add("u.status = :status");

        if (includeEmail && user.getEmail() != null) {
            assignments.add("u.email = :email");
        }
        if (includeAge) {
            assignments.add("u.age = :age");
        }

        List<String> auditAssignments = List.of("u.updatedAt = :updatedAt");
        for (String auditAssignment : auditAssignments) {
            assignments.add(auditAssignment);
        }

        for (int i = 0; i < assignments.size(); i++) {
            if (i > 0) {
                jpql.append(", ");
            }
            jpql.append(assignments.get(i));
        }
        jpql.append(" where u.id = :id");

        Query query = entityManager.createQuery(jpql.toString());
        query.setParameter("id", user.getId());
        query.setParameter("username", user.getUsername());
        query.setParameter("status", user.getStatus());
        query.setParameter("updatedAt", LocalDateTime.now());
        if (includeEmail && user.getEmail() != null) {
            query.setParameter("email", user.getEmail());
        }
        if (includeAge) {
            query.setParameter("age", user.getAge());
        }
        return query.executeUpdate();
    }

    public int deleteNativeByStatuses(Collection<String> statuses, boolean requireInactive) {
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
        Query query = entityManager.createNativeQuery(sql.toString());
        bindPositionalParameters(query, parameters);
        return query.executeUpdate();
    }

    @Transactional(readOnly = true)
    public Number countNativeByStatusUsingLiteral(String status) {
        String sql = "SELECT COUNT(*) FROM users";
        if (status != null && !status.isBlank()) {
            sql += " WHERE status = '" + escapeSqlLiteral(status) + "'";
        }

        Object value = entityManager.createNativeQuery(sql).getSingleResult();
        return value instanceof Number number ? number : 0;
    }

    public void resetData() {
        entityManager.createNativeQuery("TRUNCATE TABLE users").executeUpdate();
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

    private void appendOrder(StringBuilder sql, String orderBy, boolean descending, String prefix) {
        String column = switch (orderBy == null ? "" : orderBy) {
            case "username" -> prefix + "username";
            case "age" -> prefix + "age";
            case "createdAt" -> prefix.isEmpty() ? "created_at" : prefix + "createdAt";
            default -> prefix + "id";
        };
        sql.append(" ORDER BY ").append(column);
        if (descending) {
            sql.append(" DESC");
        } else {
            sql.append(" ASC");
        }
    }

    private void bindSearchParameters(Query query, UserSearchDTO criteria) {
        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            query.setParameter("status", criteria.getStatus());
        }
        if (criteria.getMinAge() != null) {
            query.setParameter("minAge", criteria.getMinAge());
        }
        if (criteria.getMaxAge() != null) {
            query.setParameter("maxAge", criteria.getMaxAge());
        }
        if (!criteria.getIds().isEmpty()) {
            query.setParameter("ids", criteria.getIds());
        }
        for (int i = 0; i < criteria.getKeywords().size(); i++) {
            query.setParameter("keyword" + i, "%" + criteria.getKeywords().get(i).toLowerCase() + "%");
        }
    }

    private void bindPositionalParameters(Query query, List<Object> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            query.setParameter(i + 1, parameters.get(i));
        }
    }

    private String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
