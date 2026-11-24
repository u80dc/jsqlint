package org.u80dc.jsqlint.mybatis.plus;

import java.util.*;

/** 默认注入器的实体写入模板；字段顺序与条件同时用于列和值，不执行填充器或主键生成器。 */
final class PlusCrudTemplates {
    private PlusCrudTemplates() {}

    static String build(EntityMapping entity, String operation, List<String> issues) {
        return build(entity,operation,issues,false);
    }
    static String build(EntityMapping entity,String operation,List<String> issues,boolean optimistic) {
        return build(entity, operation, issues, optimistic, true);
    }
    static String build(EntityMapping entity,String operation,List<String> issues,boolean optimistic,boolean conditionalAutoIdInsert) {
        boolean insert = operation.equals("insert");
        var fields = new ArrayList<EntityMapping.Field>();
        if (insert) entity.primaryKey().ifPresent(fields::add);
        entity.fields().stream().filter(field -> !entity.primaryKey().filter(field::equals).isPresent())
                .filter(field -> insert || !entity.logicDelete().filter(field::equals).isPresent()).forEach(fields::add);
        var columns = new StringBuilder(); var values = new StringBuilder();
        for (var field : fields) {
            boolean key = entity.primaryKey().filter(field::equals).isPresent();
            if (insert && key && field.idType().equals("AUTO") && !conditionalAutoIdInsert) continue;
            if (insert && key && field.keySequence()) issues.add("MyBatis-Plus sequence key generation is not modeled: " + field.property());
            String strategy = insert ? field.insertStrategy() : field.updateStrategy();
            if (key) strategy = field.idType().equals("AUTO") ? "NOT_NULL" : "ALWAYS";
            if (strategy.equals("DEFAULT")) strategy = "NOT_NULL";
            boolean filled = Set.of(insert ? "INSERT" : "UPDATE", "INSERT_UPDATE").contains(field.fill());
            if (filled) { strategy = "ALWAYS"; issues.add("MyBatis-Plus automatic field fill is not modeled: " + field.property()); }
            if (field.version() && !insert && !optimistic) issues.add("MyBatis-Plus optimistic locking is not modeled: " + field.property());
            if (strategy.equals("NEVER")) continue;
            String expression = "#{" + field.property() + field.bindingOptions() + "}";
            if (!insert && !field.update().isBlank()) {
                try { expression = String.format(Locale.ROOT, field.update(), field.column()); }
                catch (IllegalFormatException error) { issues.add("Invalid field update expression: " + field.property()); }
            }
            String condition = condition(field, strategy);
            columns.append(wrap(field.column() + ",", condition));
            if (insert && key && Set.of("ASSIGN_ID", "ASSIGN_UUID").contains(field.idType())) {
                String missing = field.property() + " == null" + (field.type().equals("java.lang.String") ? " or " + field.property() + " == ''" : "");
                values.append("<choose><when test=\"").append(xml(missing))
                        .append("\">#{").append(generatedKeyName(entity)).append("},</when><otherwise>").append(xml(expression + ","))
                        .append("</otherwise></choose>");
            } else values.append(wrap(insert ? expression + "," : field.column() + "=" + expression + ",", condition));
        }
        if (insert) return "<script>INSERT INTO " + xml(entity.table())
                + " <trim prefix='(' suffix=')' suffixOverrides=','>" + columns + "</trim> VALUES ("
                + "<trim suffixOverrides=','>" + values + "</trim>)</script>";
        if (operation.equals("updateSet")) return values.toString();
        var key = entity.primaryKey().orElseThrow();
        String where = xml(key.column()) + "=#{" + key.property() + "}";
        if (entity.logicDelete().isPresent()) {
            var logic = entity.logicDelete().get();
            where += " AND " + xml(logic.column()) + (logic.logicNotDeleted().equalsIgnoreCase("null") ? " IS NULL"
                    : "=" + xml(literal(logic.logicNotDeleted(), logic.type())));
        }
        return "<script>UPDATE " + xml(entity.table()) + " <set>" + values + "</set> WHERE " + where + "</script>";
    }
    private static String generatedKeyName(EntityMapping entity) {
        String name = "__generatedPrimaryKey";
        var properties = entity.fields().stream().map(EntityMapping.Field::property).toList();
        while (properties.contains(name)) name += "_";
        return name;
    }

    private static String condition(EntityMapping.Field field, String strategy) {
        if (strategy.equals("ALWAYS") || Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double").contains(field.type())) return "";
        String property = field.property();
        return property + " != null" + (strategy.equals("NOT_EMPTY") && Set.of("java.lang.String", "java.lang.CharSequence", "java.lang.StringBuilder", "java.lang.StringBuffer").contains(field.type()) ? " and " + property + " != ''" : "");
    }
    private static String wrap(String text, String condition) {
        return condition.isEmpty() ? xml(text) : "<if test=\"" + xml(condition) + "\">" + xml(text) + "</if>";
    }
    private static String literal(String value, String type) {
        return Set.of("java.lang.String", "java.lang.CharSequence", "java.lang.StringBuilder", "java.lang.StringBuffer").contains(type)
                && !value.equalsIgnoreCase("null") ? "'" + value + "'" : value;
    }
    private static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }
}
