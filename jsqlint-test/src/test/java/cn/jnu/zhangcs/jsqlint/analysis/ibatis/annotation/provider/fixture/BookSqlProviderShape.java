package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.jdbc.SQL;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class BookSqlProviderShape {

    public String selectByCategoryIds(Set<Long> categoryIds) {
        return new SQL() {{
            SELECT_DISTINCT(
                    "b.id",
                    "b.title",
                    "b.cover",
                    "b.description",
                    "b.status",
                    "b.create_time",
                    "b.update_time"
            );

            FROM("book b");
            INNER_JOIN("book_category_relation bcr ON b.id = bcr.book_id");

            if (categoryIds != null && !categoryIds.isEmpty()) {
                String ids = categoryIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                WHERE("bcr.category_id IN ( " + ids + " )");
            } else {
                WHERE("1 = 0");
            }

            ORDER_BY("b.create_time DESC");
        }}.toString();
    }

    public String selectLowStockQuery(Integer threshold) {
        SQL sql = new SQL();
        sql.SELECT("b.id, b.name, b.author, b.isbn, b.publish_date AS publishDate, b.price, " +
                "b.category_id AS categoryId, b.stock, b.status, " +
                "b.create_time AS createTime, b.update_time AS updateTime, " +
                "c.name AS category_name");
        sql.FROM("book b");
        sql.LEFT_OUTER_JOIN("category c ON b.category_id = c.id");

        if (threshold != null) {
            sql.WHERE("b.stock <= " + threshold);
        } else {
            sql.WHERE("b.stock <= 5");
        }
        sql.WHERE("b.status = 1");

        sql.ORDER_BY("b.stock ASC");

        return sql.toString();
    }

    public String insertBook(String title, Integer stock) {
        SQL sql = new SQL();
        sql.INSERT_INTO("book");
        sql.INTO_COLUMNS("title", "stock");
        if (stock != null) {
            sql.INTO_VALUES(title, String.valueOf(stock));
        } else {
            sql.INTO_VALUES(title, "0");
        }
        return sql.toString();
    }

    public String insertBookWithValues(String title) {
        return new SQL()
                .INSERT_INTO("book")
                .VALUES("title", title)
                .toString();
    }

    public String updateBookStock(Integer stock, Long id) {
        SQL sql = new SQL();
        sql.UPDATE("book");
        sql.SET("stock = " + stock);
        sql.WHERE("id = " + id);
        sql.LIMIT(1);
        return sql.toString();
    }

    public String deleteDisabledBooks(Integer limit) {
        SQL sql = new SQL();
        sql.DELETE_FROM("book");
        sql.WHERE("status = 0");
        sql.LIMIT(limit);
        return sql.toString();
    }

    public String buildFindByPage(Book book) {
        SQL sql = new SQL();
        sql.SELECT("b.id, b.name, b.author, b.isbn, b.publish_date AS publishDate, b.price, " +
                "b.category_id AS categoryId, b.stock, b.status, " +
                "b.create_time AS createTime, b.update_time AS updateTime, " +
                "c.name AS category_name");
        sql.FROM("book b");
        sql.LEFT_OUTER_JOIN("category c ON b.category_id = c.id");

        if (book.getName() != null && !book.getName().isEmpty()) {
            sql.WHERE("b.name LIKE CONCAT('%', #{name}, '%')");
        }
        if (book.getAuthor() != null && !book.getAuthor().isEmpty()) {
            sql.WHERE("b.author LIKE CONCAT('%', #{author}, '%')");
        }
        if (book.getCategoryId() != null) {
            sql.WHERE("b.category_id = #{categoryId}");
        }
        if (book.getStatus() != null) {
            sql.WHERE("b.status = #{status}");
        }

        sql.ORDER_BY("b.id DESC");

        if (book.getOffset() != null && book.getLimit() != null) {
            sql.LIMIT("#{offset}, #{limit}");
        }

        return sql.toString();
    }

    public String buildIsbnCheck(String isbn, Long excludeId) {
        SQL sql = new SQL();
        sql.SELECT("COUNT(*)");
        sql.FROM("book");
        sql.WHERE("isbn = #{isbn}");

        if (excludeId != null) {
            sql.WHERE("id != #{excludeId}");
        }

        return sql.toString();
    }

    public String buildOverdueQuery(Integer daysOverdue) {
        SQL sql = new SQL();
        sql.SELECT("br.id, br.user_id AS userId, br.book_id AS bookId, " +
                "br.borrow_date AS borrowDate, br.due_date AS dueDate, br.return_date AS returnDate, " +
                "br.status, br.create_time AS createTime, br.update_time AS updateTime, " +
                "u.real_name AS userName, b.name AS bookName");
        sql.FROM("borrow_record br");
        sql.LEFT_OUTER_JOIN("user u ON br.user_id = u.id");
        sql.LEFT_OUTER_JOIN("book b ON br.book_id = b.id");

        sql.WHERE("br.status = 0");

        if (daysOverdue != null) {
            sql.WHERE("br.due_date < DATE_SUB(NOW(), INTERVAL " + daysOverdue + " DAY)");
        } else {
            sql.WHERE("br.due_date < NOW()");
        }

        sql.ORDER_BY("br.due_date ASC");

        return sql.toString();
    }

    public String buildBatchDelete(List<Long> ids) {
        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM category WHERE id IN ");
        sql.append(this.buildInClause(ids));
        return sql.toString();
    }

    public String buildBatchUpdate(@Param("ids") List<Long> ids, @Param("description") String description) {
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE category SET update_time = NOW()");
        if (description != null) {
            sql.append(", description = '").append(this.escapeSql(description)).append("'");
        }

        sql.append(" WHERE id IN ");
        sql.append(this.buildInClause(ids));
        return sql.toString();
    }

    private String buildInClause(List<?> list) {
        StringBuilder sb = new StringBuilder("(");

        for (int i = 0; i < list.size(); ++i) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append("'").append(list.get(i)).append("'");
        }

        sb.append(")");
        return sb.toString();
    }

    private String escapeSql(String value) {
        return value.replace("'", "''");
    }

    public String buildUpdateReturn(BorrowRecord record) {
        SQL sql = new SQL();
        sql.UPDATE("borrow_record");
        if (record.getReturnDate() != null) {
            sql.SET("return_date = #{returnDate}");
        }

        if (record.getStatus() != null) {
            sql.SET("status = #{status}");
        }

        sql.SET("update_time = NOW()");
        sql.WHERE("id = #{id}");
        return sql.toString();
    }

    public String buildUpdate(@Param("c") Category category) {
        SQL sql = new SQL();
        sql.UPDATE("category");
        if (category.getName() != null) {
            sql.SET("name = #{name}");
        }

        if (category.getDescription() != null) {
            sql.SET("description = #{description}");
        }

        sql.SET("update_time = NOW()");
        sql.WHERE("id = #{id}");
        return sql.toString();
    }

    public String buildInsert(User user) {
        SQL sql = new SQL();
        sql.INSERT_INTO("user");
        sql.INTO_COLUMNS(new String[]{"username", "password", "real_name", "phone", "email", "role", "status", "create_time", "update_time"});
        sql.VALUES("username", "#{username}");
        sql.VALUES("password", "#{password}");
        if (user.getRealName() != null) {
            sql.VALUES("real_name", "#{realName}");
        }

        if (user.getPhone() != null) {
            sql.VALUES("phone", "#{phone}");
        }

        if (user.getEmail() != null) {
            sql.VALUES("email", "#{email}");
        }

        if (user.getRole() != null) {
            sql.VALUES("role", "#{role}");
        }

        if (user.getStatus() != null) {
            sql.VALUES("status", "#{status}");
        }

        sql.VALUES("create_time", "NOW()");
        sql.VALUES("update_time", "NOW()");
        return sql.toString();
    }

    public String buildDynamicSelect(User query) {
        SQL sql = new SQL();
        sql.SELECT("id, username, password, real_name AS realName, phone, email, role, status, create_time AS createTime, update_time AS updateTime");
        sql.FROM("user");
        if (query.getUsername() != null && !query.getUsername().isEmpty()) {
            sql.WHERE("username LIKE CONCAT('%', #{username}, '%')");
        }

        if (query.getRealName() != null && !query.getRealName().isEmpty()) {
            sql.WHERE("real_name LIKE CONCAT('%', #{realName}, '%')");
        }

        if (query.getRole() != null) {
            sql.WHERE("role = #{role}");
        }

        if (query.getStatus() != null) {
            sql.WHERE("status = #{status}");
        }

        sql.ORDER_BY("id DESC");
        if (query.getOffset() != null && query.getPageSize() != null) {
            sql.LIMIT("#{offset}, #{pageSize}");
        }

        return sql.toString();
    }
}
