package org.u80dc.jsqlint.mybatis.plus;

import org.objectweb.asm.*;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** 从目标分页组件读取默认计数及页大小规则，不实例化目标插件或借用模型运行时版本。 */
final class PlusPaginationDefaults {
    private static final String MODERN = "com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor";
    private static final String LEGACY = "com.baomidou.mybatisplus.extension.plugins.PaginationInterceptor";
    private static final String LEGACY_COUNT = "com.baomidou.mybatisplus.extension.plugins.pagination.optimize.JsqlParserCountOptimize";
    private static final String SQL_UTILS = "com.baomidou.mybatisplus.extension.toolkit.SqlParserUtils";
    private static final Object NULL = new Object(), UNKNOWN = new Object();
    private static final Pattern COUNT = Pattern.compile("COUNT\\s*\\(\\s*(1|\\*)\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRAPPER_ALIAS = Pattern.compile("\\)\\s*(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*$", Pattern.CASE_INSENSITIVE);

    private PlusPaginationDefaults() {}

    static Optional<Map<String, String>> read(JavaView view) {
        try {
            // 较新发行版可能仍包含废弃的旧类；存在 InnerInterceptor 时使用其实际规则。
            boolean modern = present(view, MODERN);
            String component = modern ? MODERN : LEGACY;
            if (!present(view, component)) return Optional.empty();
            byte[] pagination = PlusClassMetadata.classBytes(component, view);
            byte[] countClass = modern ? pagination : PlusClassMetadata.classBytes(LEGACY_COUNT, view);
            CountData count = count(countClass);
            if (count == null || !count.complete()) return Optional.empty();
            String wrapper = wrapperAlias(PlusClassMetadata.classBytes(SQL_UTILS, view));
            if (wrapper == null) return Optional.empty();
            FieldDefaults parserDefaults = defaults(countClass);
            Object optimizeJoin = parserDefaults.values().getOrDefault("optimizeJoin", Integer.valueOf(0));
            if (!(optimizeJoin instanceof Number)) return Optional.empty();
            FieldDefaults pageDefaults = modern ? parserDefaults : defaults(pagination);
            Object limit = pageDefaults.values().getOrDefault(modern ? "maxLimit" : "limit", NULL);
            if (limit != NULL && !(limit instanceof Number)) return Optional.empty();
            String maximum = limit instanceof Number number && number.longValue() > 0 ? Long.toString(number.longValue()) : "";
            return Optional.of(Map.of("pagination.countDefault", count.expression,
                    "pagination.countAlias", count.alias, "pagination.countWrapperAlias", wrapper,
                    "pagination.optimizeJoinDefault", Boolean.toString(((Number) optimizeJoin).intValue() != 0),
                    "pagination.maxLimitDefault", maximum));
        } catch (IOException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static boolean present(JavaView view, String type) {
        return view.getClass(view.getIdentifierFactory().getClassType(type)).isPresent();
    }

    private static final class CountData {
        private String expression, alias = "", lastString;
        private Number lastNumber;
        private boolean aliasSeen, aliasKnown;
        private boolean complete() { return expression != null && (!aliasSeen || aliasKnown); }
    }

    private static CountData count(byte[] bytes) {
        String owner = new ClassReader(bytes).getClassName();
        CountData[] field = {null}, helper = {null};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                boolean initializer = name.equals("<clinit>");
                if (!initializer && !name.toLowerCase(Locale.ROOT).contains("countselectitem")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    private CountData data = new CountData();
                    @Override public void visitLdcInsn(Object value) {
                        if (value instanceof String text) {
                            data.lastString = text;
                            var match = COUNT.matcher(text);
                            if (match.find()) data.expression = match.group(1);
                        } else if (value instanceof Number number) data.lastNumber = number;
                    }
                    @Override public void visitInsn(int opcode) {
                        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) data.lastNumber = opcode - Opcodes.ICONST_0;
                        else if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) data.lastNumber = (long) (opcode - Opcodes.LCONST_0);
                    }
                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW && type.endsWith("/AllColumns")) data.expression = "*";
                    }
                    @Override public void visitMethodInsn(int opcode, String type, String method, String descriptor, boolean isInterface) {
                        if (type.endsWith("/LongValue") && method.equals("<init>")) {
                            if (data.lastNumber != null && data.lastNumber.longValue() == 1 || "1".equals(data.lastString)) data.expression = "1";
                        }
                        if (type.equals("net/sf/jsqlparser/expression/Alias")) {
                            data.aliasSeen = true;
                            if ((method.equals("<init>") && descriptor.contains("Ljava/lang/String;"))
                                    || method.equals("setName") || method.equals("withName")) {
                                if (data.lastString != null) { data.alias = data.lastString;data.aliasKnown = true; }
                            }
                        }
                    }
                    @Override public void visitFieldInsn(int opcode, String type, String name, String descriptor) {
                        if (initializer && opcode == Opcodes.PUTSTATIC && type.equals(owner)) {
                            if (name.equals("COUNT_SELECT_ITEM")) field[0] = data;
                            data = new CountData();
                        }
                    }
                    @Override public void visitEnd() { if (!initializer && data.complete()) helper[0] = data; }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return field[0] != null && field[0].complete() ? field[0] : helper[0];
    }

    private static String wrapperAlias(byte[] bytes) {
        var pieces = new ArrayList<String>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("getOriginalCountSql")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object value) { if (value instanceof String text) pieces.add(text); }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                        for (Object value : arguments) if (value instanceof String text) pieces.add(text.replace("\u0001", "%s"));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var match = WRAPPER_ALIAS.matcher(String.join("%s", pieces));
        return match.find() ? match.group(1) : null;
    }

    private record ConstructorData(List<String> delegates, Map<String, Object> assignments) {}
    private record FieldDefaults(Map<String, Object> values) {}

    private static FieldDefaults defaults(byte[] bytes) {
        String owner = new ClassReader(bytes).getClassName();
        var fields = new LinkedHashMap<String, Object>();
        var constructors = new HashMap<String, ConstructorData>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (Set.of("optimizeJoin", "limit", "maxLimit").contains(name))
                    fields.put(name, value != null ? value : descriptor.equals("Z") || descriptor.equals("J") || descriptor.equals("I") ? Integer.valueOf(0) : NULL);
                return null;
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("<init>")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    private Object last = UNKNOWN;
                    private final List<String> delegates = new ArrayList<>();
                    private final Map<String, Object> assignments = new LinkedHashMap<>();
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ACONST_NULL) last = NULL;
                        else if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) last = opcode - Opcodes.ICONST_0;
                        else if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) last = (long) (opcode - Opcodes.LCONST_0);
                    }
                    @Override public void visitIntInsn(int opcode, int operand) { if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) last = operand; }
                    @Override public void visitLdcInsn(Object value) { last = value instanceof Number ? value : UNKNOWN; }
                    @Override public void visitVarInsn(int opcode, int variable) { last = UNKNOWN; }
                    @Override public void visitTypeInsn(int opcode, String type) { if (opcode == Opcodes.NEW) last = UNKNOWN; }
                    @Override public void visitMethodInsn(int opcode, String type, String name, String descriptor, boolean isInterface) {
                        if (type.equals(owner) && name.equals("<init>")) delegates.add(descriptor);
                        if (!Set.of("java/lang/Long", "java/lang/Integer", "java/lang/Boolean").contains(type)
                                || !Set.of("valueOf", "<init>").contains(name)) last = UNKNOWN;
                    }
                    @Override public void visitFieldInsn(int opcode, String type, String name, String descriptor) {
                        if (opcode == Opcodes.PUTFIELD && type.equals(owner) && fields.containsKey(name)) assignments.put(name, last);
                        last = UNKNOWN;
                    }
                    @Override public void visitEnd() { constructors.put(descriptor, new ConstructorData(List.copyOf(delegates), Map.copyOf(assignments))); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        fields.putAll(constructor("()V", constructors, new HashSet<>()));
        return new FieldDefaults(Map.copyOf(fields));
    }

    private static Map<String, Object> constructor(String descriptor, Map<String, ConstructorData> constructors, Set<String> active) {
        var data = constructors.get(descriptor);
        if (data == null || !active.add(descriptor)) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        for (String delegate : data.delegates()) result.putAll(constructor(delegate, constructors, active));
        result.putAll(data.assignments());active.remove(descriptor);return result;
    }
}
