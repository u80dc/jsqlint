package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.repository;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BorrowSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.BorrowRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class BorrowRecordJdbcRepository {

    private static final String INSERT_SQL = """
            INSERT INTO borrow_records (book_id, borrower_name, borrower_phone, status, due_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, book_id, borrower_name, borrower_phone, status, borrowed_at, due_at, returned_at, created_at, updated_at
            FROM borrow_records
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public BorrowRecordJdbcRepository(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    public BorrowRecord create(BorrowRecord record) {
        jdbcTemplate.update(INSERT_SQL, record.getBookId(), record.getBorrowerName(),
                record.getBorrowerPhone(), record.getStatus(), record.getDueAt());
        return findLatestByBookAndBorrower(record.getBookId(), record.getBorrowerName()).orElse(record);
    }

    public Optional<BorrowRecord> findById(long id) {
        List<BorrowRecord> records = jdbcTemplate.query(FIND_BY_ID_SQL, this::mapRow, id);
        return records.stream().findFirst();
    }

    public Optional<BorrowRecord> findLatestByBookAndBorrower(Long bookId, String borrowerName) {
        String sql = """
                SELECT id, book_id, borrower_name, borrower_phone, status, borrowed_at, due_at, returned_at, created_at, updated_at
                FROM borrow_records
                WHERE book_id = ? AND borrower_name = ?
                ORDER BY id DESC
                LIMIT 1
                """;
        List<BorrowRecord> records = jdbcTemplate.query(sql, this::mapRow, bookId, borrowerName);
        return records.stream().findFirst();
    }

    public List<BorrowRecord> search(BorrowSearchDTO criteria) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, book_id, borrower_name, borrower_phone, status, borrowed_at, due_at, returned_at, created_at, updated_at
                FROM borrow_records
                """);
        List<Object> parameters = new ArrayList<>();
        List<String> predicates = new ArrayList<>();

        if (criteria.getBookId() != null) {
            predicates.add("book_id = ?");
            parameters.add(criteria.getBookId());
        }
        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            predicates.add("status = ?");
            parameters.add(criteria.getStatus());
        }
        if (criteria.getBorrowerName() != null && !criteria.getBorrowerName().isBlank()) {
            predicates.add("LOWER(borrower_name) LIKE ?");
            parameters.add("%" + criteria.getBorrowerName().toLowerCase() + "%");
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

        appendWhere(sql, predicates);
        appendOrder(sql, criteria.getOrderBy(), criteria.isDescending());
        return jdbcTemplate.query(sql.toString(), this::mapRow, parameters.toArray());
    }

    public List<BorrowRecord> searchWithNamedParameters(BorrowSearchDTO criteria) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, book_id, borrower_name, borrower_phone, status, borrowed_at, due_at, returned_at, created_at, updated_at
                FROM borrow_records
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();

        if (criteria.getBookId() != null) {
            predicates.add("book_id = :bookId");
            parameters.addValue("bookId", criteria.getBookId());
        }
        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            predicates.add("status = :status");
            parameters.addValue("status", criteria.getStatus());
        }
        if (!criteria.getIds().isEmpty()) {
            predicates.add("id IN (:ids)");
            parameters.addValue("ids", criteria.getIds());
        }

        appendWhere(sql, predicates);
        appendOrder(sql, criteria.getOrderBy(), criteria.isDescending());
        return namedJdbcTemplate.query(sql.toString(), parameters, this::mapRow);
    }

    public int markReturned(Long id, LocalDateTime returnedAt, String status) {
        String sql = """
                UPDATE borrow_records
                SET status = :status,
                    returned_at = :returnedAt,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("returnedAt", returnedAt);
        return namedJdbcTemplate.update(sql, parameters);
    }

    public int deleteByStatuses(Collection<String> statuses, boolean includeReturned) {
        StringBuilder sql = new StringBuilder("DELETE FROM borrow_records");
        List<Object> parameters = new ArrayList<>();
        List<String> predicates = new ArrayList<>();

        if (!includeReturned) {
            predicates.add("status <> ?");
            parameters.add("RETURNED");
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
        String sql = "SELECT COUNT(*) FROM borrow_records";
        if (status != null && !status.isBlank()) {
            sql += " WHERE status = '" + escapeSqlLiteral(status) + "'";
        }
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
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
            case "borrowerName" -> "borrower_name";
            case "dueAt" -> "due_at";
            case "borrowedAt" -> "borrowed_at";
            default -> "id";
        };
        sql.append(" ORDER BY ").append(column);
        sql.append(descending ? " DESC" : " ASC");
    }

    private BorrowRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        BorrowRecord record = new BorrowRecord();
        record.setId(rs.getLong("id"));
        record.setBookId(rs.getLong("book_id"));
        record.setBorrowerName(rs.getString("borrower_name"));
        record.setBorrowerPhone(rs.getString("borrower_phone"));
        record.setStatus(rs.getString("status"));
        record.setBorrowedAt(toLocalDateTime(rs.getTimestamp("borrowed_at")));
        record.setDueAt(toLocalDateTime(rs.getTimestamp("due_at")));
        record.setReturnedAt(toLocalDateTime(rs.getTimestamp("returned_at")));
        record.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        record.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        return record;
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
