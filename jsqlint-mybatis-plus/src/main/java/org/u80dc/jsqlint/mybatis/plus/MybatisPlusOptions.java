package org.u80dc.jsqlint.mybatis.plus;

import java.util.Objects;

/** 首轮默认注入器及实体命名/逻辑删除配置；配置由调用方明确传入，不执行目标配置代码。 */
public record MybatisPlusOptions(boolean tableUnderline, boolean mapUnderscoreToCamelCase, boolean capitalMode,
                                 String tablePrefix, String schema, String logicDeleteField,
                                 String logicNotDeleteValue, String logicDeleteValue,
                                 String idType, String insertStrategy, String updateStrategy) {
    public MybatisPlusOptions {
        Objects.requireNonNull(tablePrefix); Objects.requireNonNull(schema); Objects.requireNonNull(logicDeleteField);
        Objects.requireNonNull(logicNotDeleteValue); Objects.requireNonNull(logicDeleteValue);
        if (!java.util.Set.of("AUTO", "INPUT", "ASSIGN_ID", "ASSIGN_UUID").contains(idType)) throw new IllegalArgumentException("Unsupported ID strategy: " + idType);
        for (String strategy : java.util.List.of(insertStrategy, updateStrategy))
            if (!java.util.Set.of("ALWAYS", "NOT_NULL", "NOT_EMPTY", "NEVER").contains(strategy)) throw new IllegalArgumentException("Unsupported field strategy: " + strategy);
    }
    public MybatisPlusOptions(boolean tableUnderline, boolean mapUnderscoreToCamelCase, boolean capitalMode,
                              String tablePrefix, String schema, String logicDeleteField, String logicNotDeleteValue, String logicDeleteValue) {
        this(tableUnderline, mapUnderscoreToCamelCase, capitalMode, tablePrefix, schema, logicDeleteField, logicNotDeleteValue, logicDeleteValue, "ASSIGN_ID", "NOT_NULL", "NOT_NULL");
    }
    public static MybatisPlusOptions defaults() { return new MybatisPlusOptions(true, true, false, "", "", "", "0", "1"); }
    public MybatisPlusOptions withTableUnderline(boolean value) { return new MybatisPlusOptions(value, mapUnderscoreToCamelCase, capitalMode, tablePrefix, schema, logicDeleteField, logicNotDeleteValue, logicDeleteValue, idType, insertStrategy, updateStrategy); }
    public MybatisPlusOptions withMapUnderscoreToCamelCase(boolean value) { return new MybatisPlusOptions(tableUnderline, value, capitalMode, tablePrefix, schema, logicDeleteField, logicNotDeleteValue, logicDeleteValue, idType, insertStrategy, updateStrategy); }
    public MybatisPlusOptions withCapitalMode(boolean value) { return new MybatisPlusOptions(tableUnderline, mapUnderscoreToCamelCase, value, tablePrefix, schema, logicDeleteField, logicNotDeleteValue, logicDeleteValue, idType, insertStrategy, updateStrategy); }
    public MybatisPlusOptions withTablePrefix(String value) { return new MybatisPlusOptions(tableUnderline, mapUnderscoreToCamelCase, capitalMode, value, schema, logicDeleteField, logicNotDeleteValue, logicDeleteValue, idType, insertStrategy, updateStrategy); }
    public MybatisPlusOptions withSchema(String value) { return new MybatisPlusOptions(tableUnderline, mapUnderscoreToCamelCase, capitalMode, tablePrefix, value, logicDeleteField, logicNotDeleteValue, logicDeleteValue, idType, insertStrategy, updateStrategy); }
    public MybatisPlusOptions withLogicDelete(String field, String notDeleted, String deleted) { return new MybatisPlusOptions(tableUnderline, mapUnderscoreToCamelCase, capitalMode, tablePrefix, schema, field, notDeleted, deleted, idType, insertStrategy, updateStrategy); }
    public MybatisPlusOptions withIdType(String value) { return new MybatisPlusOptions(tableUnderline, mapUnderscoreToCamelCase, capitalMode, tablePrefix, schema, logicDeleteField, logicNotDeleteValue, logicDeleteValue, value, insertStrategy, updateStrategy); }
    public MybatisPlusOptions withFieldStrategies(String insert, String update) { return new MybatisPlusOptions(tableUnderline, mapUnderscoreToCamelCase, capitalMode, tablePrefix, schema, logicDeleteField, logicNotDeleteValue, logicDeleteValue, idType, insert, update); }
}
