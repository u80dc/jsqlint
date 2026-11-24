package org.u80dc.jsqlint.mybatis.plus;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** 泛型继承、实体字段和注解的只读 class 元数据。 */
record PlusClassMetadata(String name, List<String> parameters, List<GenericType> parents,
                         Map<String, Map<String, Object>> annotations, List<Field> fields,
                         Map<String, Object> annotationDefaults) {
    private static final int API = Opcodes.ASM9;

    record GenericType(String name, boolean variable, List<GenericType> arguments) {
        GenericType { arguments = List.copyOf(arguments); }
        static GenericType type(String name) { return new GenericType(name, false, List.of()); }
        GenericType substitute(Map<String, GenericType> bindings) {
            if (variable) return bindings.getOrDefault(name, this);
            return new GenericType(name, false, arguments.stream().map(argument -> argument.substitute(bindings)).toList());
        }
        boolean concrete() { return !variable && !name.isBlank() && !name.equals("*"); }
    }
    record Field(String name, String type, Optional<GenericType> genericType, int access,
                 Map<String, Map<String, Object>> annotations) {}

    static PlusClassMetadata read(String name, JavaView view) throws IOException {
        return read(classBytes(name, view));
    }

    static byte[] classBytes(String name, JavaView view) throws IOException {
        Path source = view.getClass(view.getIdentifierFactory().getClassType(name)).orElseThrow(() ->
                new IOException("MyBatis-Plus class metadata is unavailable: " + name)).getClassSource().getSourcePath();
        if (source.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (var zip = new ZipFile(source.toFile())) {
                String entryName = name.replace('.', '/') + ".class";
                var entry = zip.getEntry(entryName);
                if (entry == null) throw new IOException("MyBatis-Plus class entry is unavailable: " + entryName);
                try (var stream = zip.getInputStream(entry)) { return stream.readAllBytes(); }
            }
        }
        return Files.readAllBytes(source);
    }

    private static PlusClassMetadata read(byte[] bytes) {
        var parameters = new ArrayList<String>();
        var parents = new ArrayList<GenericType>();
        var annotations = new LinkedHashMap<String, Map<String, Object>>();
        var fields = new ArrayList<Field>();
        var defaults = new LinkedHashMap<String, Object>();
        String[] name = {""};
        new ClassReader(bytes).accept(new ClassVisitor(API) {
            @Override public void visit(int version, int access, String className, String signature, String superclass, String[] interfaces) {
                name[0] = className.replace('/', '.');
                if (signature == null) {
                    if (superclass != null) parents.add(GenericType.type(superclass.replace('/', '.')));
                    for (String parent : interfaces) parents.add(GenericType.type(parent.replace('/', '.')));
                } else new SignatureReader(signature).accept(new SignatureVisitor(API) {
                    @Override public void visitFormalTypeParameter(String parameter) { parameters.add(parameter); }
                    @Override public SignatureVisitor visitSuperclass() { return type(parents::add); }
                    @Override public SignatureVisitor visitInterface() { return type(parents::add); }
                });
            }
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return annotation(values -> annotations.put(Type.getType(descriptor).getClassName(), values));
            }
            @Override public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(API) {
                    @Override public AnnotationVisitor visitAnnotationDefault() {
                        return annotation(values -> defaults.put(methodName, values.get(null)));
                    }
                };
            }
            @Override public FieldVisitor visitField(int access, String fieldName, String descriptor, String signature, Object value) {
                var fieldAnnotations = new LinkedHashMap<String, Map<String, Object>>();
                var generic = new ArrayList<GenericType>();
                if (signature != null) new SignatureReader(signature).acceptType(type(generic::add));
                return new FieldVisitor(API) {
                    @Override public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        return annotation(values -> fieldAnnotations.put(Type.getType(annotationDescriptor).getClassName(), values));
                    }
                    @Override public void visitEnd() {
                        fields.add(new Field(fieldName, Type.getType(descriptor).getClassName(), generic.stream().findFirst(), access,
                                Collections.unmodifiableMap(fieldAnnotations)));
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new PlusClassMetadata(name[0], List.copyOf(parameters), List.copyOf(parents),
                Collections.unmodifiableMap(annotations), List.copyOf(fields), Collections.unmodifiableMap(defaults));
    }

    /** 依赖缺少单独的annotation JAR时，从core中实际引用的枚举常量辨别早期注解能力。 */
    static boolean readsStaticField(String name, String owner, String field, JavaView view) throws IOException {
        boolean[] found = {false};
        new ClassReader(classBytes(name, view)).accept(new ClassVisitor(API) {
            @Override public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(API) {
                    @Override public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC && fieldOwner.equals(owner.replace('.', '/')) && fieldName.equals(field)) found[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /** 只接受构造器中直接赋给该字段的目标枚举常量，不执行目标全局配置代码。 */
    static Optional<String> constructorEnumDefault(String name, String field, JavaView view) throws IOException {
        var values = new LinkedHashSet<String>();
        new ClassReader(classBytes(name, view)).accept(new ClassVisitor(API) {
            @Override public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                if (!methodName.equals("<init>")) return null;
                return new MethodVisitor(API) {
                    private String candidate;
                    @Override public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC && fieldDescriptor.equals("L" + owner + ";")) candidate = fieldName;
                        else if (opcode == Opcodes.PUTFIELD) {
                            if (owner.equals(name.replace('.', '/')) && fieldName.equals(field) && candidate != null) values.add(candidate);
                            candidate = null;
                        } else candidate = null;
                    }
                    @Override public void visitInsn(int opcode) { candidate = null; }
                    @Override public void visitVarInsn(int opcode, int variable) { candidate = null; }
                    @Override public void visitIntInsn(int opcode, int operand) { candidate = null; }
                    @Override public void visitTypeInsn(int opcode, String type) { candidate = null; }
                    @Override public void visitLdcInsn(Object value) { candidate = null; }
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) { candidate = null; }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return values.size() == 1 ? values.stream().findFirst() : Optional.empty();
    }

    private static SignatureVisitor type(Consumer<GenericType> sink) {
        return new SignatureVisitor(API) {
            private String name;
            private final List<GenericType> arguments = new ArrayList<>();
            @Override public void visitClassType(String value) { name = value.replace('/', '.'); }
            @Override public void visitInnerClassType(String value) { name += "$" + value; arguments.clear(); }
            @Override public void visitTypeVariable(String value) { sink.accept(new GenericType(value, true, List.of())); }
            @Override public void visitBaseType(char descriptor) { sink.accept(GenericType.type(Type.getType(String.valueOf(descriptor)).getClassName())); }
            @Override public SignatureVisitor visitArrayType() {
                return type(value -> sink.accept(new GenericType(value.name() + "[]", value.variable(), value.arguments())));
            }
            @Override public void visitTypeArgument() { arguments.add(GenericType.type("*")); }
            @Override public SignatureVisitor visitTypeArgument(char wildcard) {
                return type(value -> arguments.add(wildcard == '=' ? value : GenericType.type("*")));
            }
            @Override public void visitEnd() { sink.accept(new GenericType(name, false, arguments)); }
        };
    }

    private static AnnotationVisitor annotation(Consumer<Map<String, Object>> sink) {
        var values = new LinkedHashMap<String, Object>();
        return new AnnotationVisitor(API) {
            @Override public void visit(String name, Object value) {
                values.put(name, value instanceof Type type ? type.getClassName() : value);
            }
            @Override public void visitEnum(String name, String descriptor, String value) { values.put(name, value); }
            @Override public AnnotationVisitor visitArray(String name) {
                var array = new ArrayList<Object>();
                return new AnnotationVisitor(API) {
                    @Override public void visit(String ignored, Object value) { array.add(value); }
                    @Override public void visitEnd() { values.put(name, List.copyOf(array)); }
                };
            }
            @Override public void visitEnd() { sink.accept(Collections.unmodifiableMap(values)); }
        };
    }
}
