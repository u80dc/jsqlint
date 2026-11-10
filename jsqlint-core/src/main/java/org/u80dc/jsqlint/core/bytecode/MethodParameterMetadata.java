package org.u80dc.jsqlint.core.bytecode;

import org.objectweb.asm.*;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** 从 class/JAR 读取参数名称和注解；不通过 ClassLoader 加载目标类。 @author u80dc */
public record MethodParameterMetadata(List<Parameter> parameters) {
    public MethodParameterMetadata { parameters = List.copyOf(parameters); }

    public record Parameter(Optional<String> name, List<Annotation> annotations) {
        public Parameter { Objects.requireNonNull(name); annotations = List.copyOf(annotations); }
    }
    public record Annotation(String type, Map<String, Object> values) {
        public Annotation { Objects.requireNonNull(type); values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    }
    public record EnumValue(String type, String value) {}

    public static MethodParameterMetadata read(JavaSootMethod method, JavaView view) throws IOException {
        Path source = view.getClass(method.getDeclClassType()).orElseThrow(() ->
                new IOException("Declaring class is unavailable: " + method.getSignature())).getClassSource().getSourcePath();
        if (source.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (var archive = new ZipFile(source.toFile())) {
                String name = method.getDeclClassType().toString().replace('.', '/') + ".class";
                var entry = archive.getEntry(name);
                if (entry == null) throw new IOException("Class entry is unavailable: " + name);
                try (var input = archive.getInputStream(entry)) { return read(input, method.getName(), descriptor(method)); }
            }
        }
        // SootUp 的归档前端也可能直接提供 zip filesystem 中的 class Path。
        try (var input = Files.newInputStream(source)) { return read(input, method.getName(), descriptor(method)); }
    }

    public static MethodParameterMetadata read(InputStream input, String name, String descriptor) throws IOException {
        int count = org.objectweb.asm.Type.getArgumentTypes(descriptor).length;
        List<Optional<String>> names = new ArrayList<>(Collections.nCopies(count, Optional.empty()));
        List<List<Annotation>> annotations = new ArrayList<>();
        for (int i = 0; i < count; i++) annotations.add(new ArrayList<>());
        boolean[] found = {false};
        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor, String signature, String[] exceptions) {
                if (!name.equals(methodName) || !descriptor.equals(methodDescriptor)) return null;
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    private int nextName, visibleOffset, invisibleOffset;
                    @Override public void visitParameter(String parameterName, int parameterAccess) {
                        if (nextName < count) names.set(nextName++, Optional.ofNullable(parameterName));
                    }
                    @Override public void visitAnnotableParameterCount(int annotableCount, boolean visible) {
                        if (visible) visibleOffset = count - annotableCount; else invisibleOffset = count - annotableCount;
                    }
                    @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String type, boolean visible) {
                        int index = parameter + (visible ? visibleOffset : invisibleOffset);
                        if (index < 0 || index >= count) return null;
                        return annotation(type, value -> annotations.get(index).add(value));
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        if (!found[0]) throw new IOException("Method metadata is unavailable: " + name + descriptor);
        List<Parameter> parameters = new ArrayList<>();
        for (int i = 0; i < count; i++) parameters.add(new Parameter(names.get(i), annotations.get(i)));
        return new MethodParameterMetadata(parameters);
    }

    private static AnnotationVisitor annotation(String descriptor, Consumer<Annotation> result) {
        Map<String, Object> values = new LinkedHashMap<>();
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) {
                values.put(name, value instanceof org.objectweb.asm.Type type ? type.getClassName() : value);
            }
            @Override public void visitEnum(String name, String type, String value) {
                values.put(name, new EnumValue(org.objectweb.asm.Type.getType(type).getClassName(), value));
            }
            @Override public AnnotationVisitor visitAnnotation(String name, String type) {
                return annotation(type, value -> values.put(name, value));
            }
            @Override public AnnotationVisitor visitArray(String name) { return array(value -> values.put(name, value)); }
            @Override public void visitEnd() { result.accept(new Annotation(org.objectweb.asm.Type.getType(descriptor).getClassName(), values)); }
        };
    }
    private static AnnotationVisitor array(Consumer<List<Object>> result) {
        List<Object> values = new ArrayList<>();
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String ignored, Object value) {
                values.add(value instanceof org.objectweb.asm.Type type ? type.getClassName() : value);
            }
            @Override public void visitEnum(String ignored, String type, String value) {
                values.add(new EnumValue(org.objectweb.asm.Type.getType(type).getClassName(), value));
            }
            @Override public AnnotationVisitor visitAnnotation(String ignored, String type) { return annotation(type, values::add); }
            @Override public AnnotationVisitor visitArray(String ignored) { return array(values::add); }
            @Override public void visitEnd() { result.accept(List.copyOf(values)); }
        };
    }
    private static String descriptor(JavaSootMethod method) {
        return "(" + String.join("", method.getParameterTypes().stream().map(type -> descriptor(type.toString())).toList())
                + ")" + descriptor(method.getReturnType().toString());
    }
    private static String descriptor(String type) {
        if (type.endsWith("[]")) return "[" + descriptor(type.substring(0, type.length() - 2));
        return switch (type) {
            case "void" -> "V"; case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C";
            case "short" -> "S"; case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
            default -> "L" + type.replace('.', '/') + ";";
        };
    }
}
