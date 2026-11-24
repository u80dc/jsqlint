package org.u80dc.jsqlint.mybatis.plus;

import java.util.Objects;
import java.util.Locale;
import java.util.Set;

/** 首轮默认注入器及实体命名/逻辑删除配置；配置由调用方明确传入，不执行目标配置代码。 */
public record MybatisPlusOptions(boolean tableUnderline, boolean mapUnderscoreToCamelCase, boolean capitalMode,
                                 String tablePrefix, String schema, String logicDeleteField,
                                 String logicNotDeleteValue, String logicDeleteValue,
                                 String idType, String insertStrategy, String updateStrategy) {
    public MybatisPlusOptions {
        Objects.requireNonNull(tablePrefix); Objects.requireNonNull(schema); Objects.requireNonNull(logicDeleteField);
        Objects.requireNonNull(logicNotDeleteValue); Objects.requireNonNull(logicDeleteValue);
        idType = normalizeIdType(idType);
        insertStrategy = normalizeFieldStrategy(insertStrategy);
        updateStrategy = normalizeFieldStrategy(updateStrategy);
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

    /** 旧3.x枚举与新枚举共用静态生成规则；实际生成的ID值仍保持未知。 */
    static String normalizeIdType(String value) {
        String strategy = Objects.requireNonNull(value, "idType").strip().toUpperCase(Locale.ROOT);
        strategy = switch (strategy) {
            case "ID_WORKER", "ID_WORKER_STR" -> "ASSIGN_ID";
            case "UUID" -> "ASSIGN_UUID";
            default -> strategy;
        };
        if (!Set.of("AUTO", "NONE", "INPUT", "ASSIGN_ID", "ASSIGN_UUID").contains(strategy))
            throw new IllegalArgumentException("Unsupported ID strategy: " + value);
        return strategy;
    }

    /** DEFAULT 在全局配置中等同 NOT_NULL；注解的 DEFAULT 先由实体读取器继承全局值。 */
    static String normalizeFieldStrategy(String value) {
        String strategy = Objects.requireNonNull(value, "fieldStrategy").strip().toUpperCase(Locale.ROOT);
        strategy = switch (strategy) {
            case "IGNORED" -> "ALWAYS";
            case "DEFAULT" -> "NOT_NULL";
            default -> strategy;
        };
        if (!Set.of("ALWAYS", "NOT_NULL", "NOT_EMPTY", "NEVER").contains(strategy))
            throw new IllegalArgumentException("Unsupported field strategy: " + value);
        return strategy;
    }
}
