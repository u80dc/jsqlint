package org.u80dc.jsqlint.validation;

import org.objectweb.asm.*;
import org.u80dc.jsqlint.core.AnalysisResult;
import org.u80dc.jsqlint.core.SqlExtraction;
import org.u80dc.jsqlint.core.spi.SqlCallSite;
import sootup.core.signatures.MethodSignature;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * 从编译产物独立枚举 SQL API 调用位置，与 Analyzer 的已发现调用点比较。
 * SQL/绑定为 UNKNOWN 的位置仍算已发现；准备、加入批次、执行位置分别计入。
 * ASM 指令和 Jimple 使用不同坐标，按完整调用者/目标签名分组核对数量，并展示顺序对应证据。
 */
final class JdbcCallSiteInventory {
    private JdbcCallSiteInventory() {}

    record Inventory(int total, int matched, int missing, int extra, List<String> evidence) {
        Inventory {
            if (total < 0 || matched < 0 || missing < 0 || extra < 0 || total != matched + missing)
                throw new IllegalArgumentException("Invalid JDBC call-site inventory counts");
            evidence = List.copyOf(evidence);
        }
    }

    static Inventory compare(Path classes, AnalysisResult result) throws IOException {
        Objects.requireNonNull(classes); Objects.requireNonNull(result);
        if (!Files.isDirectory(classes)) throw new IOException("Class directory is unavailable: " + classes);
        var classFiles = readClasses(classes);
        var hierarchy = new Hierarchy(classFiles);
        var expected = expected(classFiles, hierarchy);
        var actual = actual(result);
        var groups = new TreeSet<Group>(); groups.addAll(expected.keySet()); groups.addAll(actual.keySet());
        int total = 0, matched = 0, missing = 0, extra = 0;
        var evidence = new ArrayList<String>();
        for (var group : groups) {
            var sources = expected.getOrDefault(group, List.of());
            var discovered = actual.getOrDefault(group, List.of()).stream()
                    .sorted(Comparator.comparingInt(JdbcCallSiteInventory::statementIndex)
                            .thenComparing(site -> site.location().toString())).toList();
            int common = Math.min(sources.size(), discovered.size());
            total += sources.size(); matched += common;
            missing += sources.size() - common; extra += discovered.size() - common;
            evidence.add("GROUP " + group.label() + " expected=" + sources.size() + " actual=" + discovered.size()
                    + " matched=" + common + " missing=" + (sources.size() - common) + " extra=" + (discovered.size() - common));
            for (int i = 0; i < common; i++)
                evidence.add("ORDINAL " + group.label() + " #" + (i + 1) + " source=" + sources.get(i)
                        + " analyzer=" + location(discovered.get(i)));
            for (int i = common; i < sources.size(); i++)
                evidence.add("MISSING " + group.label() + " #" + (i + 1) + " source=" + sources.get(i));
            for (int i = common; i < discovered.size(); i++)
                evidence.add("EXTRA " + group.label() + " #" + (i + 1) + " analyzer=" + location(discovered.get(i)));
        }
        return new Inventory(total, matched, missing, extra, evidence);
    }

    private record ClassFile(ClassReader reader, List<String> parents) {}
    private record StandardSignature(String name, String parameters) {}
    private record SourceSite(int sqlOrdinal, int invocationOrdinal, int line, String declaredOwner) {}
    private record Group(String callerClass, String callerName, String callerDescriptor,
                         String apiName, String apiDescriptor) implements Comparable<Group> {
        String label() { return callerClass + "#" + callerName + callerDescriptor + "/" + apiName + apiDescriptor; }
        @Override public int compareTo(Group other) { return label().compareTo(other.label()); }
    }

    private static Map<String, ClassFile> readClasses(Path root) throws IOException {
        var classes = new TreeMap<String, ClassFile>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(value -> value.toString().endsWith(".class")).sorted().toList()) {
                var reader = new ClassReader(Files.readAllBytes(path));
                var parents = new ArrayList<>(Arrays.asList(reader.getInterfaces()));
                if (reader.getSuperName() != null) parents.add(reader.getSuperName());
                classes.put(reader.getClassName(), new ClassFile(reader, List.copyOf(parents)));
            }
        }
        return classes;
    }

    private static Map<Group, List<SourceSite>> expected(Map<String, ClassFile> classes, Hierarchy hierarchy) throws IOException {
        var groups = new TreeMap<Group, List<SourceSite>>();
        var rules = standardRules();
        for (var entry : classes.entrySet()) {
            String callerClass = entry.getKey().replace('/', '.');
            // 先为方法引用涉及的声明类型读取继承元数据，visitor 不产生检查异常。
            entry.getValue().reader().accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            if (opcode != Opcodes.INVOKESTATIC && rules.containsKey(new StandardSignature(method, parameters(descriptor))))
                                hierarchy.collect(owner);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (hierarchy.failure != null) throw hierarchy.failure;
            entry.getValue().reader().accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private int line = -1, invocationOrdinal, sqlOrdinal;
                        @Override public void visitLineNumber(int number, Label start) { line = number; }
                        @Override public void visitMethodInsn(int opcode, String owner, String api, String apiDescriptor, boolean isInterface) {
                            invocationOrdinal++;
                            if (opcode == Opcodes.INVOKESTATIC) return;
                            var required = rules.getOrDefault(new StandardSignature(api, parameters(apiDescriptor)), Set.of());
                            if (required.stream().noneMatch(type -> hierarchy.isSubtype(owner, type))) return;
                            sqlOrdinal++;
                            var group = new Group(callerClass, method, descriptor, api, apiDescriptor);
                            groups.computeIfAbsent(group, ignored -> new ArrayList<>())
                                    .add(new SourceSite(sqlOrdinal, invocationOrdinal, line, owner.replace('/', '.')));
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return groups;
    }

    /** 与生产 matcher 独立构造目录；忽略返回类型以允许工厂协变返回，排除 createStatement 与 setter/clear。 */
    private static Map<StandardSignature, Set<String>> standardRules() {
        var rules = new HashMap<StandardSignature, Set<String>>();
        var executions = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate");
        for (Class<?> api : List.of(Connection.class, Statement.class, PreparedStatement.class)) {
            for (Method method : api.getDeclaredMethods()) {
                String name = method.getName();
                boolean selected = api == Connection.class
                        ? Set.of("prepareStatement", "prepareCall", "nativeSQL").contains(name)
                        : api == Statement.class
                        ? Set.of("addBatch", "executeBatch", "executeLargeBatch").contains(name) || executions.contains(name)
                        : method.getParameterCount() == 0 && (name.equals("addBatch") || executions.contains(name));
                if (selected) {
                    var key = new StandardSignature(name, parameters(org.objectweb.asm.Type.getMethodDescriptor(method)));
                    rules.computeIfAbsent(key, ignored -> new HashSet<>()).add(api.getName().replace('.', '/'));
                }
            }
        }
        return rules;
    }

    private static Map<Group, List<SqlCallSite>> actual(AnalysisResult result) {
        var groups = new TreeMap<Group, List<SqlCallSite>>();
        result.calls().stream().filter(value -> value.moduleId().equals("jdbc")).map(SqlExtraction::callSite).distinct()
                .filter(site -> !site.target().getName().equals("createStatement"))
                .forEach(site -> {
                    var caller = site.caller();
                    // 非预期的无调用者输出保留为 extra，避免将它静默删除。
                    var group = new Group(caller.map(value -> value.getDeclClassType().toString()).orElse("<no-caller>"),
                            caller.map(MethodSignature::getName).orElse("<declaration>"), caller.map(JdbcCallSiteInventory::descriptor).orElse("()V"),
                            site.target().getName(), descriptor(site.target()));
                    groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(site);
                });
        return groups;
    }

    private static String parameters(String descriptor) { return descriptor.substring(0, descriptor.indexOf(')') + 1); }
    private static String descriptor(MethodSignature method) {
        return "(" + String.join("", method.getParameterTypes().stream().map(value -> descriptor(value.toString())).toList())
                + ")" + descriptor(method.getType().toString());
    }
    private static String descriptor(String type) {
        if (type.endsWith("[]")) return "[" + descriptor(type.substring(0, type.length() - 2));
        return switch (type) {
            case "void" -> "V"; case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C";
            case "short" -> "S"; case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
            default -> "L" + type.replace('.', '/') + ";";
        };
    }
    private static int statementIndex(SqlCallSite site) {
        String element = site.location().element(); int separator = element.lastIndexOf('@');
        if (separator < 0) return Integer.MAX_VALUE;
        try { return Integer.parseInt(element.substring(separator + 1).strip()); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }
    private static String location(SqlCallSite site) {
        return site.location() + " targetOwner=" + site.target().getDeclClassType();
    }

    /** 只读取 class 元数据，不加载或执行目标类；外部类型需在本测试进程的资源类路径上。 */
    private static final class Hierarchy {
        private final Map<String, ClassFile> source;
        private final Map<String, List<String>> external = new HashMap<>();
        private IOException failure;
        private Hierarchy(Map<String, ClassFile> source) { this.source = source; }
        private void collect(String owner) {
            if (failure != null) return;
            var pending = new ArrayDeque<String>(); pending.add(owner);
            var seen = new HashSet<String>();
            while (!pending.isEmpty()) {
                String type = pending.removeFirst(); if (!seen.add(type)) continue;
                if (source.containsKey(type)) { pending.addAll(source.get(type).parents()); continue; }
                if (external.containsKey(type)) { pending.addAll(external.get(type)); continue; }
                try (var input = JdbcCallSiteInventory.class.getClassLoader().getResourceAsStream(type + ".class")) {
                    if (input == null) { external.put(type, List.of()); continue; }
                    var reader = new ClassReader(input);
                    var parents = new ArrayList<>(Arrays.asList(reader.getInterfaces()));
                    if (reader.getSuperName() != null) parents.add(reader.getSuperName());
                    external.put(type, List.copyOf(parents)); pending.addAll(parents);
                } catch (IOException error) { failure = error; return; }
            }
        }
        private boolean isSubtype(String owner, String required) {
            var pending = new ArrayDeque<String>(); pending.add(owner); var seen = new HashSet<String>();
            while (!pending.isEmpty()) {
                String type = pending.removeFirst(); if (!seen.add(type)) continue;
                if (type.equals(required)) return true;
                pending.addAll(source.containsKey(type) ? source.get(type).parents() : external.getOrDefault(type, List.of()));
            }
            return false;
        }
    }
}
