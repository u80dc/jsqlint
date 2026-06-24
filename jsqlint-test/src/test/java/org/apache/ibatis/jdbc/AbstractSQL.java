package org.apache.ibatis.jdbc;

public abstract class AbstractSQL<T extends AbstractSQL<T>> {

    public abstract T getSelf();

    public T SELECT(String columns) {
        return getSelf();
    }

    public T SELECT(String... columns) {
        return getSelf();
    }

    public T SELECT_DISTINCT(String... columns) {
        return getSelf();
    }

    public T FROM(String table) {
        return getSelf();
    }

    public T INNER_JOIN(String join) {
        return getSelf();
    }

    public T LEFT_OUTER_JOIN(String join) {
        return getSelf();
    }

    public T WHERE(String condition) {
        return getSelf();
    }

    public T ORDER_BY(String orderBy) {
        return getSelf();
    }

    public T INSERT_INTO(String tableName) {
        return getSelf();
    }

    public T INTO_COLUMNS(String... columns) {
        return getSelf();
    }

    public T INTO_VALUES(String... values) {
        return getSelf();
    }

    public T VALUES(String columns, String values) {
        return getSelf();
    }

    public T UPDATE(String table) {
        return getSelf();
    }

    public T SET(String set) {
        return getSelf();
    }

    public T DELETE_FROM(String table) {
        return getSelf();
    }

    public T LIMIT(String value) {
        return getSelf();
    }

    public T LIMIT(int value) {
        return getSelf();
    }
}
