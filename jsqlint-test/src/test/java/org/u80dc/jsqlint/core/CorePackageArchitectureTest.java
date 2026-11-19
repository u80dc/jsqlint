package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** 包级直接依赖约定；读取 class 文件，不加载或执行被引用的项目类型。 */
class CorePackageArchitectureTest {
    private static final String CORE = "org.u80dc.jsqlint.core";
    private static final String PREFIX = CORE + ".";
    private static final Set<String> PACKAGES = Set.of("", "node", "parameter", "dataflow", "semantics",
            "template", "spi", "input", "resource", "bytecode");
    private static final Set<String> FORBIDDEN_FRAMEWORKS = Set.of("org.apache.ibatis.", "org.springframework.",
            "org.hibernate.", "jakarta.persistence.", "javax.persistence.");
    private static final Map<String, Set<String>> ALLOWED_PACKAGES = Map.of(
            "node", Set.of("node"),
            "parameter", Set.of("parameter", "dataflow", "node", "semantics"),
            "dataflow", Set.of("dataflow", "semantics"),
            "semantics", Set.of("semantics"),
            "template", Set.of("template", "node"),
            "spi", Set.of("spi"),
            "input", Set.of("input"),
            "resource", Set.of("resource"),
            "bytecode", Set.of("bytecode"));
    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.of(
            "node", types("ReplayBudgets", "SqlRenderBudgets", "semantics.ValueEvaluationLimitException"),
            "parameter", types("JointBudgets", "ReplayBudgets", "RawSql", "SqlExtraction",
                    "spi.InvocationSite", "spi.SqlCallSite"),
            "dataflow", types("spi.InvocationSite", "spi.SqlCallSite", "spi.SourceLocation"),
            "semantics", types("dataflow.ValueTrace"),
            "template", types("RawSql", "ReplayBudgets", "SqlRecoveryBudgets", "dataflow.ValueTrace",
                    "dataflow.ValueTraceGraphs", "parameter.ParameterValueInferencer",
                    "parameter.ParameterValueInferenceResult", "parameter.InferredParameterValue",
                    "parameter.ExternalValueGenerator", "semantics.ValueEvaluationLimitException", "spi.SqlCallSite"),
            "spi", types("SqlDefinition", "dataflow.DataFlowAnalysis", "dataflow.FlowPoint",
                    "dataflow.ValueTrace", "dataflow.ControlFlowEdge", "node.SqlNode", "node.SqlValueSnapshots",
                    "resource.AnalysisResource", "resource.AnalysisResourceSource"),
            "input", types("spi.AnalysisInputResolver", "spi.PreparedInput", "resource.PathResourceSource"));
    // 仅这些原全限定名可跨越兼容边界；新实现和新调用方仍受普通规则约束。
    private static final Map<String, Set<String>> COMPATIBILITY_TYPES = Map.of(
            PREFIX + "dataflow.RawSqlBuilder", types("RawSql", "SqlRecoveryBudgets", "template.RawSqlBuilder"),
            PREFIX + "dataflow.SqlTemplateProjection", types("RawSql", "node.SqlNode", "template.SqlTemplateProjection"),
            PREFIX + "parameter.ValueTraceAnalyzer", types("RawSql", "spi.SqlInput",
                    "template.RawSqlBuilder", "template.SqlTemplateProjection"));
    private static final Set<String> LEGACY_ENTRIES = types("dataflow.RawSqlBuilder",
            "dataflow.SqlTemplateProjection", "parameter.ValueTraceAnalyzer", "parameter.JavaValueOperations");
    // ValuePoint.input() 的现有输入位置转换；不向整个 dataflow 包开放 SqlInput 或其他输入类型。
    private static final Map<String, Set<String>> POSITION_BRIDGE_TYPES = Map.of(
            PREFIX + "dataflow.FlowPoint$ValuePoint", types("spi.SqlInput", "spi.SqlInput$ValueAt"));

    @Test void coreHasNoDirectDependenciesOnFrameworkAdapters() throws Exception {
        var violations = new TreeSet<String>();
        for (var source : coreClasses().values()) for (String target : source.references())
            if (adapterDependency(target)) violations.add(source.name() + " -> " + target);
        assertTrue(violations.isEmpty(), () -> "core 直接依赖框架适配实现：\n" + String.join("\n", violations));
    }

    @Test void implementationDependenciesRespectPackageBoundaries() throws Exception {
        var violations = new TreeSet<String>();
        for (var source : coreClasses().values()) for (String target : source.references())
            if (!allowed(source.name(), target)) violations.add(source.name() + " -> " + target);
        assertTrue(violations.isEmpty(), () -> "包依赖越过约定边界：\n" + String.join("\n", violations));
    }

    @Test void everyCorePackageHasADocumentedBoundary() throws Exception {
        Path sources = repositoryRoot().resolve("jsqlint-core/src/main/java");
        var found = new TreeSet<String>();
        for (var source : coreClasses().values()) found.add(packageName(source.name()));
        for (String name : found) {
            assertTrue(PACKAGES.contains(relativePackage(name)), "新增包需要先定义依赖约定：" + name);
            Path contract = sources.resolve(name.replace('.', '/')).resolve("package-info.java");
            assertTrue(Files.isRegularFile(contract), "缺少包级职责及依赖说明：" + contract);
            String text = Files.readString(contract);
            assertTrue(text.contains("package " + name + ";"), "包声明与文档位置不一致：" + contract);
            assertTrue(text.contains("/**"), "缺少包级 Javadoc：" + contract);
        }
    }

    @Test void compatibilityAndSharedMetadataExceptionsAreLimitedToNamedTypes() {
        assertTrue(allowed(PREFIX + "dataflow.RawSqlBuilder", PREFIX + "template.RawSqlBuilder"));
        assertFalse(allowed(PREFIX + "dataflow.NewQuery", PREFIX + "template.RawSqlBuilder"));
        assertFalse(allowed(PREFIX + "dataflow.RawSqlBuilder", PREFIX + "template.NewRenderer"));
        assertTrue(allowed(PREFIX + "semantics.JavaValueOperations", PREFIX + "dataflow.ValueTrace$Operation"));
        assertFalse(allowed(PREFIX + "semantics.NewOperation", PREFIX + "dataflow.DataFlowAnalysis"));
        assertFalse(allowed(PREFIX + "node.NewNode", PREFIX + "parameter.ParameterValueInferencer"));
        assertFalse(allowed(PREFIX + "template.NewProjection", PREFIX + "dataflow.ProgramIndex"));
        assertFalse(allowed(PREFIX + "parameter.ParameterValueInferencer", PREFIX + "parameter.JavaValueOperations"));
        assertFalse(allowed(PREFIX + "resource.NewResource", "sootup.java.core.views.JavaView"));
        assertFalse(allowed(PREFIX + "node.NewNode", "sootup.core.signatures.MethodSignature"));
        assertTrue(allowed(PREFIX + "dataflow.FlowPoint$ValuePoint", PREFIX + "spi.SqlInput"));
        assertTrue(allowed(PREFIX + "dataflow.FlowPoint$ValuePoint", PREFIX + "spi.SqlInput$ValueAt"));
        assertFalse(allowed(PREFIX + "dataflow.NewQuery", PREFIX + "spi.SqlInput$ValueAt"));
        assertFalse(allowed(PREFIX + "dataflow.FlowPoint$ValuePoint", PREFIX + "spi.SqlInput$Template"));
    }

    @Test void dependencyInspectionIncludesSignaturesAnnotationsAndMethodBodies() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/References", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "generic", "Ljava/util/List;",
                "Ljava/util/List<Lfixture/GenericOnly;>;", null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "inner", "Ljava/lang/Object;",
                "Lfixture/Outer<Lfixture/OuterArgument;>.Inner<Lfixture/InnerArgument;>;", null).visitEnd();
        writer.visitAnnotation("Lfixture/Annotation;", false)
                .visit("type", Type.getObjectType("fixture/AnnotationValue"));
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "run", "(Lfixture/Argument;)Lfixture/Result;", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "fixture/BodyOnly");
        method.visitInsn(Opcodes.POP);
        method.visitInvokeDynamicInsn("create", "()Lfixture/DynamicResult;",
                new Handle(Opcodes.H_INVOKESTATIC, "fixture/Bootstrap", "link", "()Ljava/lang/Object;", false),
                Type.getObjectType("fixture/BootstrapArgument"));
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn(Type.getMethodType("(Lfixture/MethodTypeArgument;)V"));
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn(Type.getObjectType("fixture/ClassLiteral"));
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("org.u80dc.jsqlint.mybatis.OrdinaryString");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        var references = inspect(writer.toByteArray()).references();
        assertTrue(references.containsAll(Set.of("fixture.GenericOnly", "fixture.Annotation",
                "fixture.AnnotationValue", "fixture.Argument", "fixture.Result", "fixture.BodyOnly",
                "fixture.DynamicResult", "fixture.Bootstrap", "fixture.BootstrapArgument",
                "fixture.MethodTypeArgument", "fixture.ClassLiteral", "fixture.Outer",
                "fixture.OuterArgument", "fixture.Outer$Inner", "fixture.InnerArgument")));
        assertFalse(references.contains("org.u80dc.jsqlint.mybatis.OrdinaryString"), "普通字符串不是类型依赖");
    }

    private static boolean allowed(String source, String target) {
        if (adapterDependency(target)) return false;
        String owner = relativePackage(packageName(source));
        if (!PACKAGES.contains(owner)) return false;
        if (LEGACY_ENTRIES.stream().anyMatch(name -> matchesType(Set.of(name), target)
                && !matchesType(Set.of(name), source))) return false;
        if (!target.startsWith(PREFIX)) {
            if (target.startsWith("sootup.")) return Set.of("", "dataflow", "parameter", "spi", "input", "bytecode").contains(owner)
                    || owner.equals("semantics") && (target.startsWith("sootup.core.jimple.")
                        || target.startsWith("sootup.core.signatures.") || target.startsWith("sootup.core.types."))
                    || owner.equals("template") && (target.startsWith("sootup.core.signatures.")
                        || target.startsWith("sootup.core.types."));
            if (owner.equals("resource")) return target.startsWith("java.");
            return true;
        }
        if (owner.isEmpty()) return true; // 公共入口/结果/会话协调包可组合各能力包。
        if (ALLOWED_PACKAGES.getOrDefault(owner, Set.of()).contains(relativePackage(packageName(target)))) return true;
        return matchesType(ALLOWED_TYPES.getOrDefault(owner, Set.of()), target)
                || matchesType(COMPATIBILITY_TYPES.getOrDefault(source, Set.of()), target)
                || POSITION_BRIDGE_TYPES.getOrDefault(source, Set.of()).contains(target);
    }

    private static boolean adapterDependency(String name) {
        return name.startsWith("org.u80dc.jsqlint.") && !name.startsWith(PREFIX)
                || FORBIDDEN_FRAMEWORKS.stream().anyMatch(name::startsWith);
    }

    private static boolean matchesType(Set<String> allowed, String target) {
        return allowed.stream().anyMatch(name -> target.equals(name) || target.startsWith(name + "$"));
    }
    private static Set<String> types(String... names) {
        var result = new HashSet<String>();
        for (String name : names) result.add(PREFIX + name);
        return Set.copyOf(result);
    }
    private static String packageName(String name) { return name.substring(0, name.lastIndexOf('.')); }
    private static String relativePackage(String name) { return name.equals(CORE) ? "" : name.substring(PREFIX.length()); }

    private static Map<String, ClassDependencies> coreClasses() throws IOException, URISyntaxException {
        Path location = Path.of(Analyzer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var classes = new TreeMap<String, ClassDependencies>();
        if (Files.isDirectory(location)) {
            try (var paths = Files.walk(location.resolve(CORE.replace('.', '/')))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList())
                    addClass(classes, Files.readAllBytes(path));
            }
        } else {
            try (var jar = new JarFile(location.toFile())) {
                for (var entry : jar.stream().filter(e -> e.getName().startsWith(CORE.replace('.', '/') + "/")
                        && e.getName().endsWith(".class")).toList())
                    try (var input = jar.getInputStream(entry)) { addClass(classes, input.readAllBytes()); }
            }
        }
        assertFalse(classes.isEmpty(), "没有读取到 core 编译产物，依赖检查不能空运行");
        return classes;
    }

    private static void addClass(Map<String, ClassDependencies> classes, byte[] bytes) {
        var source = inspect(bytes);
        classes.put(source.name(), source);
    }
    private static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent())
            if (Files.isDirectory(path.resolve("jsqlint-core/src/main/java"))) return path;
        throw new AssertionError("Cannot locate jsqlint-core sources");
    }

    private record ClassDependencies(String name, Set<String> references) {}

    private static ClassDependencies inspect(byte[] bytes) {
        var reader = new ClassReader(bytes);
        var references = new TreeSet<String>();
        char[] buffer = new char[reader.getMaxStringLength()];
        // Class / NameAndType / MethodType 项覆盖指令、catch、类字面量及 bootstrap 的类型引用。
        // 不扫描任意 UTF8 或 String 项，避免把配置文本当成类依赖。
        for (int i = 1; i < reader.getItemCount(); i++) {
            int item = reader.getItem(i);
            if (item == 0) continue; // long/double 占两个常量池位置。
            switch (reader.readByte(item - 1)) {
                case 7 -> {
                    String name = reader.readUTF8(item, buffer);
                    if (name.startsWith("[")) descriptor(name, references); else references.add(name.replace('/', '.'));
                }
                case 12 -> descriptor(reader.readUTF8(item + 2, buffer), references);
                case 16 -> descriptor(reader.readUTF8(item, buffer), references);
                default -> { }
            }
        }
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                signature(signature, references);
            }
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return annotation(descriptor, references);
            }
            @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) {
                return annotation(descriptor, references);
            }
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                descriptor(descriptor, references); signature(signature, references);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return annotation(descriptor, references); }
                    @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) { return annotation(descriptor, references); }
                };
            }
            @Override public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                descriptor(descriptor, references); signature(signature, references);
                return new RecordComponentVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return annotation(descriptor, references); }
                    @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) { return annotation(descriptor, references); }
                };
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                descriptor(descriptor, references); signature(signature, references);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotationDefault() { return annotationValues(references); }
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return annotation(descriptor, references); }
                    @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) { return annotation(descriptor, references); }
                    @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) { return annotation(descriptor, references); }
                    @Override public AnnotationVisitor visitInsnAnnotation(int ref, TypePath path, String descriptor, boolean visible) { return annotation(descriptor, references); }
                    @Override public AnnotationVisitor visitTryCatchAnnotation(int ref, TypePath path, String descriptor, boolean visible) { return annotation(descriptor, references); }
                    @Override public AnnotationVisitor visitLocalVariableAnnotation(int ref, TypePath path, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) { return annotation(descriptor, references); }
                    @Override public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
                        descriptor(descriptor, references); signature(signature, references);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        references.remove(reader.getClassName().replace('/', '.'));
        return new ClassDependencies(reader.getClassName().replace('/', '.'), Set.copyOf(references));
    }

    private static void descriptor(String descriptor, Set<String> references) {
        type(Type.getType(descriptor), references);
    }
    private static void type(Type type, Set<String> references) {
        if (type.getSort() == Type.OBJECT) references.add(type.getClassName());
        else if (type.getSort() == Type.ARRAY) type(type.getElementType(), references);
        else if (type.getSort() == Type.METHOD) {
            type(type.getReturnType(), references);
            for (Type argument : type.getArgumentTypes()) type(argument, references);
        }
    }
    private static void signature(String signature, Set<String> references) {
        if (signature != null) new SignatureReader(signature).accept(signatureVisitor(references));
    }
    private static SignatureVisitor signatureVisitor(Set<String> references) {
        return new SignatureVisitor(Opcodes.ASM9) {
            private String enclosingType;
            @Override public void visitClassType(String name) {
                enclosingType = name;
                references.add(name.replace('/', '.'));
            }
            @Override public void visitInnerClassType(String name) {
                enclosingType += "$" + name;
                references.add(enclosingType.replace('/', '.'));
            }
            @Override public SignatureVisitor visitTypeArgument(char wildcard) {
                // 泛型实参的类名不能覆盖外部类链，否则 Outer<Argument>.Inner 会被误记为 Argument$Inner。
                return signatureVisitor(references);
            }
        };
    }
    private static AnnotationVisitor annotation(String descriptor, Set<String> references) {
        descriptor(descriptor, references);
        return annotationValues(references);
    }
    private static AnnotationVisitor annotationValues(Set<String> references) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) { if (value instanceof Type type) type(type, references); }
            @Override public void visitEnum(String name, String descriptor, String value) { descriptor(descriptor, references); }
            @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) { return annotation(descriptor, references); }
            @Override public AnnotationVisitor visitArray(String name) { return this; }
        };
    }
}
