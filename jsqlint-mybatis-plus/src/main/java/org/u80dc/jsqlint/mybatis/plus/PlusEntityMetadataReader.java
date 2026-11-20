package org.u80dc.jsqlint.mybatis.plus;

import org.objectweb.asm.Opcodes;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.util.*;

import static org.u80dc.jsqlint.mybatis.plus.PlusClassMetadata.GenericType;

/** 按 Mapper 的泛型继承关系读取实体映射；只访问所需类型，不枚举依赖中的全部实体。 */
final class PlusEntityMetadataReader {
    static final String BASE_MAPPER = "com.baomidou.mybatisplus.core.mapper.BaseMapper";
    private static final String ANNOTATIONS = "com.baomidou.mybatisplus.annotation.";
    private final JavaView view;
    private final MybatisPlusOptions options;
    private final Map<String, PlusClassMetadata> classes = new HashMap<>();
    private final Map<String, Optional<EntityMapping>> mappings = new HashMap<>();

    PlusEntityMetadataReader(JavaView view, MybatisPlusOptions options) {
        this.view = view;
        this.options = options;
    }

    Optional<EntityMapping> resolve(String mapperType) {
        return mappings.computeIfAbsent(mapperType, this::read);
    }

    private Optional<EntityMapping> read(String mapperType) {
        var issues = new ArrayList<String>();
        var entities = new LinkedHashSet<String>();
        boolean[] mapper = {false};
        collectEntities(GenericType.type(mapperType), new HashSet<>(), mapper, entities, issues, 0);
        if (!mapper[0]) return Optional.empty();
        if (entities.size() != 1) {
            issues.add(entities.isEmpty() ? "MyBatis-Plus Mapper entity type is unresolved: " + mapperType
                    : "MyBatis-Plus Mapper has ambiguous entity types: " + entities);
            return Optional.of(new EntityMapping(mapperType, "", "", List.of(), Optional.empty(), Optional.empty(), issues));
        }
        String entityType = entities.iterator().next();
        var lineage = new ArrayList<BoundClass>();
        collectEntityClasses(GenericType.type(entityType), lineage, issues, new HashSet<>(), 0);
        if (lineage.isEmpty()) return Optional.of(new EntityMapping(mapperType, entityType, "", List.of(), Optional.empty(), Optional.empty(), issues));
        var tableName = lineage.stream().map(bound -> bound.metadata().annotations().get(ANNOTATIONS + "TableName"))
                .filter(Objects::nonNull).findFirst().orElse(Map.of());
        String table = string(tableName, "value", "");
        boolean explicitTable = !table.isBlank();
        if (!explicitTable) {
            String simpleName = entityType.substring(Math.max(entityType.lastIndexOf('.'), entityType.lastIndexOf('$')) + 1);
            table = options.tableUnderline() ? underline(simpleName) : firstLower(simpleName);
            if (options.capitalMode()) table = table.toUpperCase(Locale.ROOT);
        }
        if (!explicitTable || bool(tableName, "keepGlobalPrefix", false)) table = options.tablePrefix() + table;
        String schema = string(tableName, "schema", options.schema());
        if (!schema.isBlank()) table = schema + "." + table;
        if (table.contains("${")) issues.add("MyBatis-Plus table name contains unresolved configuration variables: " + table);
        List<String> selected = strings(tableName.get("properties"));
        Set<String> excluded = new HashSet<>(strings(tableName.get("excludeProperty")));
        var candidates = new LinkedHashMap<String, BoundField>();
        // 子类同名字段遮蔽父字段，包括被排除的字段，保持实际反射字段选择边界。
        Set<String> visitedFields = new HashSet<>();
        for (BoundClass bound : lineage) for (PlusClassMetadata.Field field : bound.metadata().fields()) {
            if (!visitedFields.add(field.name())) continue;
            if ((field.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC)) != 0) continue;
            if (!selected.isEmpty() ? !selected.contains(field.name()) : excluded.contains(field.name())) continue;
            if (!bool(field.annotations().getOrDefault(ANNOTATIONS + "TableField", Map.of()), "exist", true)) continue;
            candidates.put(field.name(), new BoundField(field, bound.bindings()));
        }
        var annotatedKeys = candidates.values().stream().filter(field -> field.field().annotations().containsKey(ANNOTATIONS + "TableId")).toList();
        var keys = annotatedKeys.isEmpty() ? candidates.values().stream().filter(field -> field.field().name().equalsIgnoreCase("id")).toList() : annotatedKeys;
        if (keys.size() > 1) issues.add("MyBatis-Plus entity has more than one primary key: " + entityType);
        String keyName = keys.size() == 1 ? keys.getFirst().field().name() : "";
        var explicitLogic = candidates.values().stream().filter(field -> field.field().annotations().containsKey(ANNOTATIONS + "TableLogic")).toList();
        if (explicitLogic.size() > 1) issues.add("MyBatis-Plus entity has more than one logical delete field: " + entityType);
        String logicName = explicitLogic.size() == 1 ? explicitLogic.getFirst().field().name()
                : explicitLogic.isEmpty() ? options.logicDeleteField() : "";
        boolean resultMap = !string(tableName, "resultMap", "").isBlank();
        boolean autoResultMap = bool(tableName, "autoResultMap", false);
        var fields = new ArrayList<EntityMapping.Field>();
        EntityMapping.Field primaryKey = null, logicDelete = null;
        for (BoundField bound : candidates.values()) {
            var field = bound.field();
            var tableField = field.annotations().getOrDefault(ANNOTATIONS + "TableField", Map.of());
            var tableId = field.annotations().getOrDefault(ANNOTATIONS + "TableId", Map.of());
            boolean key = field.name().equals(keyName);
            String column = string(key ? tableId : tableField, "value", "");
            if (column.isBlank()) {
                column = options.mapUnderscoreToCamelCase() ? underline(field.name()) : field.name();
                if (options.capitalMode()) column = column.toUpperCase(Locale.ROOT);
            }
            if (column.contains("${")) issues.add("MyBatis-Plus column contains unresolved configuration variables: " + column);
            String property = field.name();
            if (!string(tableField, "property", "").isBlank()) issues.add("MyBatis-Plus TableField.property override is not supported: " + field.name());
            if (!string(tableField, "typeHandler", "org.apache.ibatis.type.UnknownTypeHandler").equals("org.apache.ibatis.type.UnknownTypeHandler"))
                issues.add("MyBatis-Plus custom type handler is not supported: " + field.name());
            String type = field.genericType().map(value -> value.substitute(bound.bindings())).filter(GenericType::concrete)
                    .map(GenericType::name).orElse(field.type());
            var logic = field.annotations().getOrDefault(ANNOTATIONS + "TableLogic", Map.of());
            String notDeleted = field.name().equals(logicName) ? string(logic, "value", options.logicNotDeleteValue()) : "";
            String deleted = field.name().equals(logicName) ? string(logic, "delval", options.logicDeleteValue()) : "";
            String unquoted = column.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
            boolean related = !property.equalsIgnoreCase(unquoted)
                    && !(options.mapUnderscoreToCamelCase() && property.equalsIgnoreCase(unquoted.replace("_", "")));
            String selectColumn = related && !resultMap && !autoResultMap ? column + " AS " + property : column;
            var mapped = new EntityMapping.Field(property, column, selectColumn, type, key || bool(tableField, "select", true),
                    notDeleted, deleted, string(tableField, "fill", "DEFAULT"), field.annotations().containsKey(ANNOTATIONS + "Version"),
                    strategy(tableId, "type", "NONE", options.idType()), strategy(tableField, "insertStrategy", "DEFAULT", options.insertStrategy()),
                    strategy(tableField, "updateStrategy", "DEFAULT", options.updateStrategy()), string(tableField, "update", ""),
                    bindingOptions(tableField), key && lineage.stream().anyMatch(value -> value.metadata().annotations().containsKey(ANNOTATIONS + "KeySequence")));
            fields.add(mapped);
            if (key) primaryKey = mapped;
            if (field.name().equals(logicName)) logicDelete = mapped;
            if (field.annotations().containsKey(ANNOTATIONS + "TableName")) issues.add("Unexpected field-level MyBatis-Plus TableName annotation");
        }
        if(fields.stream().filter(EntityMapping.Field::version).count()>1)issues.add("Multiple entity version fields are not supported: "+entityType);
        return Optional.of(new EntityMapping(mapperType, entityType, table, fields,
                Optional.ofNullable(primaryKey), Optional.ofNullable(logicDelete), issues));
    }

    private void collectEntities(GenericType type, Set<GenericType> visited, boolean[] mapper, Set<String> entities,
                                 List<String> issues, int depth) {
        if (depth > 64) { issues.add("MyBatis-Plus generic inheritance depth exceeded"); return; }
        if (!visited.add(type) || type.name().equals("java.lang.Object")) return;
        if (type.name().equals(BASE_MAPPER)) {
            mapper[0] = true;
            if (type.arguments().size() == 1 && type.arguments().getFirst().concrete()
                    && !type.arguments().getFirst().name().equals("java.lang.Object")) entities.add(type.arguments().getFirst().name());
            return;
        }
        try {
            var metadata = metadata(type.name());
            Map<String, GenericType> bindings = bindings(metadata, type);
            for (GenericType parent : metadata.parents()) collectEntities(parent.substitute(bindings), visited, mapper, entities, issues, depth + 1);
        } catch (IOException | RuntimeException error) { issues.add("MyBatis-Plus generic metadata is unavailable for " + type.name() + ": " + error.getMessage()); }
    }

    private void collectEntityClasses(GenericType type, List<BoundClass> result, List<String> issues, Set<String> visited, int depth) {
        if (type.name().equals("java.lang.Object") || !visited.add(type.name())) return;
        if (depth > 64) { issues.add("MyBatis-Plus entity inheritance depth exceeded"); return; }
        try {
            var metadata = metadata(type.name());
            Map<String, GenericType> bindings = bindings(metadata, type);
            result.add(new BoundClass(metadata, bindings));
            // class 签名的第一个父类型是 superclass，接口不会贡献实例字段。
            if (!metadata.parents().isEmpty()) collectEntityClasses(metadata.parents().getFirst().substitute(bindings), result, issues, visited, depth + 1);
        } catch (IOException | RuntimeException error) { issues.add("MyBatis-Plus entity metadata is unavailable for " + type.name() + ": " + error.getMessage()); }
    }

    private PlusClassMetadata metadata(String type) throws IOException {
        var cached = classes.get(type);
        if (cached != null) return cached;
        var loaded = PlusClassMetadata.read(type, view);
        classes.put(type, loaded);
        return loaded;
    }
    private static Map<String, GenericType> bindings(PlusClassMetadata metadata, GenericType type) {
        var result = new HashMap<String, GenericType>();
        for (int i = 0; i < Math.min(metadata.parameters().size(), type.arguments().size()); i++)
            result.put(metadata.parameters().get(i), type.arguments().get(i));
        return result;
    }
    private static String string(Map<String, Object> values, String name, String fallback) {
        Object value = values.get(name);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
    private static boolean bool(Map<String, Object> values, String name, boolean fallback) {
        return values.get(name) instanceof Boolean value ? value : fallback;
    }
    private static String bindingOptions(Map<String, Object> field) {
        var result = new StringBuilder();
        String jdbcType = string(field, "jdbcType", "UNDEFINED");
        if (!jdbcType.equals("UNDEFINED")) result.append(",jdbcType=").append(jdbcType);
        String scale = Objects.toString(field.getOrDefault("numericScale", ""));
        if (!scale.isBlank()) result.append(",numericScale=").append(scale);
        return result.toString();
    }
    private static String strategy(Map<String, Object> annotation, String name, String inherited, String fallback) {
        String value = string(annotation, name, inherited);
        return value.equals(inherited) ? fallback : value;
    }
    private static List<String> strings(Object value) {
        return value instanceof List<?> values ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
    }
    private static String firstLower(String name) { return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1); }
    private static String underline(String name) {
        var result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (Character.isUpperCase(character)) {
                if (i > 0) result.append('_');
                result.append(Character.toLowerCase(character));
            } else result.append(character);
        }
        return result.toString();
    }
    private record BoundClass(PlusClassMetadata metadata, Map<String, GenericType> bindings) {}
    private record BoundField(PlusClassMetadata.Field field, Map<String, GenericType> bindings) {}
}
