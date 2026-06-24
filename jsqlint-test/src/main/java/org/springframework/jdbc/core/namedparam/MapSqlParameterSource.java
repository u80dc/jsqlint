package org.springframework.jdbc.core.namedparam;

public class MapSqlParameterSource implements SqlParameterSource {

    public MapSqlParameterSource addValue(String paramName, Object value) {
        return this;
    }
}
