package org.u80dc.jsqlint.mybatis.plus;

import org.objectweb.asm.*;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** 从目标框架字节码读取默认 SQL 和插入能力；不执行 SqlMethod 或 SQL 注入器。 */
record PlusSqlDefaults(Optional<Count> count, Optional<Boolean> conditionalAutoIdInsert, Optional<Boolean> defaultLogicDelete,
                       Optional<Boolean> wrapperGrouped) {
    record Count(String expression, String suffix, boolean selectedColumns) {}
    private record MethodData(List<String> literals, List<String> calls) {}
    private static final String SQL_METHOD = "com.baomidou.mybatisplus.core.enums.SqlMethod";
    private static final String ABSTRACT_METHOD = "com.baomidou.mybatisplus.core.injector.AbstractMethod";
    private static final String TABLE_INFO = "com.baomidou.mybatisplus.core.metadata.TableInfo";
    private static final Pattern COUNT = Pattern.compile("SELECT\\s+COUNT\\s*\\(\\s*(%s|1|\\*)\\s*\\)(.*?)\\s+FROM\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static PlusSqlDefaults read(JavaView view) {
        return new PlusSqlDefaults(count(view), autoId(view), logicalDelete(view), grouping(view));
    }

    private static Optional<Count> count(JavaView view) {
        try {
            var sql = enumSql(view, "SELECT_COUNT");
            if (sql.isEmpty()) return Optional.empty();
            var matcher = COUNT.matcher(sql.get());
            if (!matcher.find()) return Optional.empty();
            String suffix = matcher.group(2).strip();
            var literals = countLiterals(view);
            String expression = matcher.group(1).equals("%s")
                    ? literals.stream().filter(text -> text.equals("1") || text.equals("*")).findFirst().orElse("") : matcher.group(1);
            if (expression.isEmpty()) return Optional.empty();
            boolean selected = matcher.group(1).equals("%s") && literals.stream().anyMatch(text -> text.contains("sqlSelect"));
            return Optional.of(new Count(expression, suffix.isEmpty() ? "" : " " + suffix, selected));
        } catch (IOException | RuntimeException unavailable) { return Optional.empty(); }
    }

    /** sqlCount 的同类委托 helper 也属于计数规则；不混入 selectObjs 等无关方法。 */
    private static List<String> countLiterals(JavaView view) throws IOException {
        var methods = new LinkedHashMap<String, MethodData>();
        new ClassReader(PlusClassMetadata.classBytes(ABSTRACT_METHOD, view)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                String key = name + descriptor;
                return new MethodVisitor(Opcodes.ASM9) {
                    private final List<String> literals = new ArrayList<>(), calls = new ArrayList<>();
                    @Override public void visitLdcInsn(Object value) { if (value instanceof String text) literals.add(text); }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (owner.equals(ABSTRACT_METHOD.replace('.', '/'))) calls.add(name + descriptor);
                    }
                    @Override public void visitEnd() { methods.put(key, new MethodData(List.copyOf(literals), List.copyOf(calls))); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var pending = new ArrayDeque<String>();
        methods.keySet().stream().filter(key -> key.startsWith("sqlCount(")).forEach(pending::add);
        var visited = new HashSet<String>();
        var literals = new ArrayList<String>();
        while (!pending.isEmpty()) {
            String key = pending.removeFirst();
            if (!visited.add(key)) continue;
            var method = methods.get(key);
            if (method == null) continue;
            literals.addAll(method.literals());
            pending.addAll(method.calls());
        }
        return List.copyOf(literals);
    }

    private static Optional<String> enumSql(JavaView view, String constant) throws IOException {
        String[] result = {null};
        String[] delegate = {null};
        var templates = new HashMap<String, String>();
        new ClassReader(PlusClassMetadata.classBytes(SQL_METHOD, view)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("<clinit>")) {
                    if ((access & Opcodes.ACC_SYNTHETIC) == 0 || (access & Opcodes.ACC_STATIC) == 0) return null;
                    Type[] arguments = Type.getArgumentTypes(descriptor);
                    if (Arrays.stream(arguments).anyMatch(type -> !type.equals(Type.getType(String.class)))) return null;
                    String key = name + descriptor;
                    return new MethodVisitor(Opcodes.ASM9) {
                        private final StringBuilder template = new StringBuilder();
                        @Override public void visitLdcInsn(Object value) { if (value instanceof String text) template.append(text); }
                        @Override public void visitVarInsn(int opcode, int variable) {
                            if (opcode == Opcodes.ALOAD && variable < arguments.length) template.append("%s");
                        }
                        @Override public void visitEnd() { templates.put(key, template.toString()); }
                    };
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private final List<String> values = new ArrayList<>();
                    private String function;
                    @Override public void visitLdcInsn(Object value) { if (value instanceof String text) values.add(text); }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                        for (Object value : arguments) if (value instanceof Handle handle && handle.getOwner().equals(SQL_METHOD.replace('.', '/')))
                            function = handle.getName() + handle.getDesc();
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode != Opcodes.PUTSTATIC || !owner.equals(SQL_METHOD.replace('.', '/'))) return;
                        if (name.equals(constant)) {
                            result[0] = values.stream().filter(text -> COUNT.matcher(text).find()).reduce((first, last) -> last).orElse(null);
                            delegate[0] = function;
                        }
                        values.clear();
                        function = null;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return Optional.ofNullable(result[0] != null ? result[0] : templates.get(delegate[0]));
    }

    private static Optional<Boolean> autoId(JavaView view) {
        try {
            boolean[] found = {false}, conditional = {false};
            new ClassReader(PlusClassMetadata.classBytes(TABLE_INFO, view)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("getKeyInsertSqlProperty")) return null;
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (owner.equals("com/baomidou/mybatisplus/core/toolkit/sql/SqlScriptUtils") && name.equals("convertIf")) conditional[0] = true;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0] ? Optional.of(conditional[0]) : Optional.empty();
        } catch (IOException | RuntimeException unavailable) { return Optional.empty(); }
    }

    private static Optional<Boolean> logicalDelete(JavaView view) {
        try {
            boolean[] found = {false}, logical = {false};
            new ClassReader(PlusClassMetadata.classBytes("com.baomidou.mybatisplus.core.injector.methods.DeleteById", view))
                    .accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("injectMappedStatement")) return null;
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (owner.equals(TABLE_INFO.replace('.', '/')) && name.toLowerCase(java.util.Locale.ROOT).contains("logicdelete")
                                    || name.equals("sqlLogicSet")) logical[0] = true;
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (owner.equals(SQL_METHOD.replace('.', '/')) && name.startsWith("LOGIC_")) logical[0] = true;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0] ? Optional.of(logical[0]) : Optional.empty();
        } catch (IOException | RuntimeException unavailable) { return Optional.empty(); }
    }

    private static Optional<Boolean> grouping(JavaView view) {
        try {
            boolean[] found = {false};
            var literals = new ArrayList<String>();
            new ClassReader(PlusClassMetadata.classBytes("com.baomidou.mybatisplus.core.conditions.segments.NormalSegmentList", view))
                    .accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("childrenSqlSegment") && !name.equals("getSqlSegment")) return null;
                    found[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitLdcInsn(Object value) { if (value instanceof String text) literals.add(text); }
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                            for (Object value : arguments) if (value instanceof String text) literals.add(text);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0] ? Optional.of(literals.stream().anyMatch(text -> text.contains("("))
                    && literals.stream().anyMatch(text -> text.contains(")"))) : Optional.empty();
        } catch (IOException | RuntimeException unavailable) { return Optional.empty(); }
    }
}
