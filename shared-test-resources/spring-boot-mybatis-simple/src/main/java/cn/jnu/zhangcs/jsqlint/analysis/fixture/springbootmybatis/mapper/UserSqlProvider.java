package cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.mapper;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.entity.User;
import org.apache.ibatis.jdbc.SQL;

import java.util.List;

public class UserSqlProvider {

    public String search(UserSearchDTO criteria) {
        SQL sql = new SQL()
                .SELECT("id, username, email, status, age, created_at, updated_at")
                .FROM("users");

        if (criteria != null) {
            if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
                sql.WHERE("status = #{status}");
            }
            if (criteria.getMinAge() != null) {
                sql.WHERE("age >= #{minAge}");
            }
            if (criteria.getMaxAge() != null) {
                sql.WHERE("age <= #{maxAge}");
            }
            if (!criteria.getIds().isEmpty()) {
                StringBuilder ids = new StringBuilder("id IN (");
                for (int i = 0; i < criteria.getIds().size(); i++) {
                    if (i > 0) {
                        ids.append(", ");
                    }
                    ids.append("#{ids[").append(i).append("]}");
                }
                ids.append(")");
                sql.WHERE(ids.toString());
            }
            if (!criteria.getKeywords().isEmpty()) {
                StringBuilder keywords = new StringBuilder("(");
                for (int i = 0; i < criteria.getKeywords().size(); i++) {
                    if (i > 0) {
                        keywords.append(" OR ");
                    }
                    keywords.append("username LIKE CONCAT('%', #{keywords[")
                            .append(i)
                            .append("]}, '%')");
                    keywords.append(" OR email LIKE CONCAT('%', #{keywords[")
                            .append(i)
                            .append("]}, '%')");
                }
                keywords.append(")");
                sql.WHERE(keywords.toString());
            }

            String direction = criteria.isDescending() ? " DESC" : " ASC";
            switch (criteria.getOrderBy() == null ? "" : criteria.getOrderBy()) {
                case "age" -> sql.ORDER_BY("age" + direction);
                case "email" -> sql.ORDER_BY("email" + direction);
                case "username" -> sql.ORDER_BY("username" + direction);
                default -> sql.ORDER_BY("id" + direction);
            }
        } else {
            sql.ORDER_BY("id ASC");
        }

        return sql.toString();
    }

    public String updateDynamic(User user) {
        if (user == null) {
            return "UPDATE users SET updated_at = CURRENT_TIMESTAMP WHERE 1 = 0";
        }

        SQL sql = new SQL().UPDATE("users");
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            sql.SET("username = #{username}");
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            sql.SET("email = #{email}");
        }
        if (user.getStatus() != null && !user.getStatus().isBlank()) {
            sql.SET("status = #{status}");
        }
        if (user.getAge() != null) {
            sql.SET("age = #{age}");
        }

        for (String auditColumn : List.of("updated_at")) {
            if ("updated_at".equals(auditColumn)) {
                sql.SET(auditColumn + " = CURRENT_TIMESTAMP");
            }
        }
        if (user.getId() == null) {
            sql.WHERE("1 = 0");
        } else {
            sql.WHERE("id = #{id}");
        }
        return sql.toString();
    }

    public String deleteByStatuses(List<String> statuses) {
        StringBuilder sql = new StringBuilder("DELETE FROM users");
        if (statuses == null || statuses.isEmpty()) {
            return sql.append(" WHERE 1 = 0").toString();
        }

        sql.append(" WHERE status IN (");
        for (int i = 0; i < statuses.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("#{statuses[").append(i).append("]}");
        }
        sql.append(")");
        return sql.toString();
    }
}
