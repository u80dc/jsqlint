package org.u80dc.jsqlint.mybatis;

import java.util.*;

/** MyBatis 专属设置；显式传入项目配置，不改变 core 的通用分析选项。 @author u80dc */
public record MybatisOptions(Optional<String> databaseId, boolean useActualParamName,
                             boolean nullableOnForEach, Map<String, String> variables) {
    public MybatisOptions {
        Objects.requireNonNull(databaseId); variables = Map.copyOf(variables);
        databaseId = databaseId.filter(value -> !value.isBlank());
    }
    public static MybatisOptions defaults() { return new MybatisOptions(Optional.empty(), true, false, Map.of()); }
    public MybatisOptions withDatabaseId(String value) { return new MybatisOptions(Optional.ofNullable(value), useActualParamName, nullableOnForEach, variables); }
    public MybatisOptions withUseActualParamName(boolean value) { return new MybatisOptions(databaseId, value, nullableOnForEach, variables); }
    public MybatisOptions withNullableOnForEach(boolean value) { return new MybatisOptions(databaseId, useActualParamName, value, variables); }
    public MybatisOptions withVariables(Map<String, String> value) { return new MybatisOptions(databaseId, useActualParamName, nullableOnForEach, value); }
}
