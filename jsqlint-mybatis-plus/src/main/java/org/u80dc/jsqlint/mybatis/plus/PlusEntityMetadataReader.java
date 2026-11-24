package org.u80dc.jsqlint.mybatis.plus;

import org.objectweb.asm.*;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.u80dc.jsqlint.mybatis.plus.PlusClassMetadata.GenericType;

/** 按 Mapper 的泛型继承关系读取实体映射；只访问所需类型，不枚举依赖中的全部实体。 */
final class PlusEntityMetadataReader {
    static final String BASE_MAPPER = "com.baomidou.mybatisplus.core.mapper.BaseMapper";
    private static final String ANNOTATIONS = "com.baomidou.mybatisplus.annotation.";
    private final JavaView view;
    private final MybatisPlusOptions options;
    private final Map<String, PlusClassMetadata> classes = new HashMap<>();
    private final Map<String, Optional<EntityMapping>> mappings = new HashMap<>();
    private AnnotationDefaults tableFieldDefaults;
    private FieldCollection fieldCollection;
    private List<String> fieldCollectionIssues = List.of();

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
        // 先按目标ReflectionKit完成反射字段收集，再应用数据库字段筛选；排除字段也会影响旧HashMap顺序。
        for (BoundField bound : reflectedFields(lineage, issues)) {
            var field = bound.field();
            if (!selected.isEmpty() ? !selected.contains(field.name()) : excluded.contains(field.name())) continue;
            if (!bool(field.annotations().getOrDefault(ANNOTATIONS + "TableField", Map.of()), "exist", true)) continue;
            if ((field.access() & Opcodes.ACC_SYNTHETIC) != 0) {
                issues.add("MyBatis-Plus instance synthetic field is not supported: " + entityType + "." + field.name());
                continue;
            }
            candidates.put(field.name(), bound);
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
            var tableField = fieldAnnotation(field, issues);
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
            String legacyExpression = string(tableField, "el", "");
            if (!legacyExpression.isBlank() && !legacyExpression.split(",", 2)[0].strip().equals(property))
                issues.add("MyBatis-Plus TableField.el property override is not supported: " + field.name());
            if (column.contains(";")) issues.add("MyBatis-Plus multi-column field mapping is not supported: " + field.name());
            if (!string(tableField, "typeHandler", "org.apache.ibatis.type.UnknownTypeHandler").equals("org.apache.ibatis.type.UnknownTypeHandler"))
                issues.add("MyBatis-Plus custom type handler is not supported: " + field.name());
            if (legacyExpression.contains("typeHandler=") && !legacyExpression.contains("typeHandler=org.apache.ibatis.type.UnknownTypeHandler"))
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
                    idStrategy(tableId, issues), fieldStrategy(tableField, "insertStrategy", options.insertStrategy(), issues),
                    fieldStrategy(tableField, "updateStrategy", options.updateStrategy(), issues), string(tableField, "update", ""),
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

    private List<BoundField> reflectedFields(List<BoundClass> lineage, List<String> issues) {
        if (fieldCollection == null) {
            var failures = new ArrayList<String>();fieldCollection = fieldCollection(failures);
            fieldCollectionIssues = List.copyOf(failures);
        }
        issues.addAll(fieldCollectionIssues);
        if (fieldCollection == FieldCollection.FILTERED_LIST) {
            var result = new ArrayList<BoundField>();var names = new HashSet<String>();
            for (BoundClass bound : lineage) for (var field : bound.metadata().fields()) {
                if (instanceField(field) && names.add(field.name())) result.add(new BoundField(field, bound.bindings()));
            }
            return result;
        }
        if (fieldCollection == FieldCollection.HASH_MAP) {
            List<BoundField> inherited = List.of();
            for (int index = lineage.size() - 1; index >= 0; index--) {
                BoundClass bound = lineage.get(index);
                // 与目标Collectors.toMap两参数一致：先包含本类所有声明字段，再追加父类已过滤字段。
                Map<String, BoundField> fields = bound.metadata().fields().stream().collect(Collectors.toMap(
                        PlusClassMetadata.Field::name, field -> new BoundField(field, bound.bindings())));
                for (BoundField parent : inherited) if (!fields.containsKey(parent.field().name())) fields.put(parent.field().name(), parent);
                inherited = fields.values().stream().filter(field -> instanceField(field.field())).toList();
            }
            return inherited;
        }
        var result = new ArrayList<BoundField>();var names = new HashSet<String>();
        for (BoundClass bound : lineage) for (var field : bound.metadata().fields()) {
            if (names.add(field.name()) && instanceField(field)) result.add(new BoundField(field, bound.bindings()));
        }
        return result;
    }

    private FieldCollection fieldCollection(List<String> issues) {
        var profiles = new LinkedHashMap<String, FieldCollection>();var calls = new LinkedHashSet<String>();
        String owner = "com/baomidou/mybatisplus/core/toolkit/ReflectionKit";
        try {
            new ClassReader(PlusClassMetadata.classBytes(owner.replace('/', '.'), view)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    boolean merging = name.equals("excludeOverrideSuperField");
                    boolean collecting = name.contains("getFieldList") || name.contains("GetFieldList");
                    if (!merging && !collecting) return null;
                    if (merging && Type.getReturnType(descriptor).getClassName().equals("java.util.List"))
                        profiles.put(descriptor, FieldCollection.FILTERED_LIST);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String target, String method, String arguments, boolean isInterface) {
                            if (collecting && target.equals(owner) && method.equals("excludeOverrideSuperField")) calls.add(arguments);
                            if (merging && Type.getReturnType(descriptor).getClassName().equals("java.util.Map")
                                    && target.equals("java/util/stream/Collectors") && method.equals("toMap") && Type.getArgumentTypes(arguments).length == 2)
                                profiles.put(descriptor, FieldCollection.HASH_MAP);
                        }
                        @Override public void visitInvokeDynamicInsn(String method, String arguments, Handle bootstrap, Object... values) {
                            if (!merging) return;
                            for (Object value : values) if (value instanceof Handle handle && handle.getOwner().equals("java/util/LinkedHashMap"))
                                profiles.put(descriptor, FieldCollection.LINKED_MAP);
                        }
                        @Override public void visitTypeInsn(int opcode, String target) {
                            if (merging && opcode == Opcodes.NEW && target.equals("java/util/LinkedHashMap")) profiles.put(descriptor, FieldCollection.LINKED_MAP);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var selected = calls.stream().map(profiles::get).filter(Objects::nonNull).distinct().toList();
            if (selected.size() == 1) return selected.getFirst();
        } catch (IOException | RuntimeException failure) {
            issues.add("MyBatis-Plus reflection field collection capabilities are unavailable: " + failure.getClass().getSimpleName());
            return FieldCollection.LINKED_MAP;
        }
        issues.add("MyBatis-Plus reflection field collection strategy is not supported in target framework");
        return FieldCollection.LINKED_MAP;
    }

    private static boolean instanceField(PlusClassMetadata.Field field) {
        return (field.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_TRANSIENT)) == 0;
    }

    private Map<String, Object> fieldAnnotation(PlusClassMetadata.Field field, List<String> issues) {
        var declared = field.annotations().get(ANNOTATIONS + "TableField");
        if (declared == null) return Map.of();
        if (tableFieldDefaults == null) tableFieldDefaults = fieldAnnotationDefaults();
        issues.addAll(tableFieldDefaults.issues());
        var result = new LinkedHashMap<>(tableFieldDefaults.values());
        result.putAll(declared);
        return Map.copyOf(result);
    }

    private AnnotationDefaults fieldAnnotationDefaults() {
        var result = new LinkedHashMap<String, Object>();
        var issues = new ArrayList<String>();
        try { result.putAll(metadata(ANNOTATIONS + "TableField").annotationDefaults()); }
        catch (IOException | RuntimeException unavailable) {
            // 完整Maven输入读取真实注解；只有core JAR的输入可由其实际字段策略能力恢复默认值。
            try {
                var configuration = metadata("com.baomidou.mybatisplus.core.config.GlobalConfig$DbConfig");
                if (configuration.fields().stream().anyMatch(value -> value.name().equals("insertStrategy"))) {
                    result.putAll(Map.of("insertStrategy", "DEFAULT", "updateStrategy", "DEFAULT", "whereStrategy", "DEFAULT"));
                } else {
                    boolean inherit = PlusClassMetadata.readsStaticField("com.baomidou.mybatisplus.core.metadata.TableFieldInfo",
                            ANNOTATIONS + "FieldStrategy", "DEFAULT", view);
                    result.put("strategy", inherit ? "DEFAULT" : "NOT_NULL");
                }
            } catch (IOException | RuntimeException failure) {
                issues.add("MyBatis-Plus TableField annotation defaults are unavailable: " + failure.getClass().getSimpleName());
            }
        }
        return new AnnotationDefaults(Map.copyOf(result), List.copyOf(issues));
    }

    private String idStrategy(Map<String, Object> annotation, List<String> issues) {
        String value = strategy(annotation, "type", "NONE", options.idType());
        try { return MybatisPlusOptions.normalizeIdType(value); }
        catch (IllegalArgumentException failure) { issues.add(failure.getMessage()); return "INPUT"; }
    }

    private static String fieldStrategy(Map<String, Object> annotation, String name, String fallback, List<String> issues) {
        String value = annotation.containsKey(name) ? strategy(annotation, name, "DEFAULT", fallback)
                : strategy(annotation, "strategy", "DEFAULT", fallback);
        try { return MybatisPlusOptions.normalizeFieldStrategy(value); }
        catch (IllegalArgumentException failure) { issues.add(failure.getMessage()); return "NEVER"; }
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
        String expression = string(field, "el", "");
        int separator = expression.indexOf(',');
        if (separator >= 0) result.append(',').append(expression.substring(separator + 1).strip());
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
    private record AnnotationDefaults(Map<String, Object> values, List<String> issues) {}
    private enum FieldCollection { FILTERED_LIST, HASH_MAP, LINKED_MAP }
}
