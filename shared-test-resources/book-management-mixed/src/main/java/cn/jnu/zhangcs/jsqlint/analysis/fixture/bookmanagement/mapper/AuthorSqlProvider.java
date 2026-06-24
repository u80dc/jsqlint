package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.mapper;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.AuthorSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Author;
import org.apache.ibatis.jdbc.SQL;

import java.util.List;

public class AuthorSqlProvider {

    public String search(AuthorSearchDTO criteria) {
        SQL sql = new SQL()
                .SELECT("id, name, country, status, created_at, updated_at")
                .FROM("authors");

        if (criteria != null) {
            if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
                sql.WHERE("status = #{status}");
            }
            if (criteria.getCountry() != null && !criteria.getCountry().isBlank()) {
                sql.WHERE("country = #{country}");
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
                    keywords.append("name LIKE CONCAT('%', #{keywords[")
                            .append(i)
                            .append("]}, '%')");
                }
                keywords.append(")");
                sql.WHERE(keywords.toString());
            }

            String direction = criteria.isDescending() ? " DESC" : " ASC";
            switch (criteria.getOrderBy() == null ? "" : criteria.getOrderBy()) {
                case "name" -> sql.ORDER_BY("name" + direction);
                case "country" -> sql.ORDER_BY("country" + direction);
                default -> sql.ORDER_BY("id" + direction);
            }
        } else {
            sql.ORDER_BY("id ASC");
        }

        return sql.toString();
    }

    public String updateDynamic(Author author) {
        if (author == null || author.getId() == null) {
            return "UPDATE authors SET updated_at = CURRENT_TIMESTAMP WHERE 1 = 0";
        }

        SQL sql = new SQL().UPDATE("authors");
        if (author.getName() != null && !author.getName().isBlank()) {
            sql.SET("name = #{name}");
        }
        if (author.getCountry() != null && !author.getCountry().isBlank()) {
            sql.SET("country = #{country}");
        }
        if (author.getStatus() != null && !author.getStatus().isBlank()) {
            sql.SET("status = #{status}");
        }
        for (String auditColumn : List.of("updated_at")) {
            sql.SET(auditColumn + " = CURRENT_TIMESTAMP");
        }
        sql.WHERE("id = #{id}");
        return sql.toString();
    }

    public String deleteByStatuses(List<String> statuses) {
        StringBuilder sql = new StringBuilder("DELETE FROM authors");
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
