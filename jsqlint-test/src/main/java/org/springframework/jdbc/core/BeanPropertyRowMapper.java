package org.springframework.jdbc.core;

public class BeanPropertyRowMapper<T> implements RowMapper<T> {

    public BeanPropertyRowMapper(Class<T> mappedClass) {
    }
}
