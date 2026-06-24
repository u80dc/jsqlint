package org.springframework.jdbc.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class JdbcTemplate {

    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
        return Collections.emptyList();
    }

    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        return Collections.emptyList();
    }

    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        return Collections.emptyList();
    }

    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return null;
    }

    public int update(String sql, Object... args) {
        return 0;
    }

    public void execute(String sql) {
    }
}
