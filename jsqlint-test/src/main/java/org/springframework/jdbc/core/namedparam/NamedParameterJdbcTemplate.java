package org.springframework.jdbc.core.namedparam;

import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;

public class NamedParameterJdbcTemplate {

    public <T> List<T> query(String sql, SqlParameterSource paramSource, RowMapper<T> rowMapper) {
        return Collections.emptyList();
    }

    public <T> T queryForObject(String sql, SqlParameterSource paramSource, Class<T> requiredType) {
        return null;
    }

    public int update(String sql, SqlParameterSource paramSource) {
        return 0;
    }
}
