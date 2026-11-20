package org.u80dc.jsqlint.mybatis.plus;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** 泛型继承、实体字段和注解的只读 class 元数据。 */
record PlusClassMetadata(String name, List<String> parameters, List<GenericType> parents,
                         Map<String, Map<String, Object>> annotations, List<Field> fields) {
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
        Path source = view.getClass(view.getIdentifierFactory().getClassType(name)).orElseThrow(() ->
                new IOException("MyBatis-Plus class metadata is unavailable: " + name)).getClassSource().getSourcePath();
        if (source.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (var zip = new ZipFile(source.toFile())) {
                String entryName = name.replace('.', '/') + ".class";
                var entry = zip.getEntry(entryName);
                if (entry == null) throw new IOException("MyBatis-Plus class entry is unavailable: " + entryName);
                try (var stream = zip.getInputStream(entry)) { return read(stream); }
            }
        }
        try (var stream = Files.newInputStream(source)) { return read(stream); }
    }

    private static PlusClassMetadata read(InputStream stream) throws IOException {
        var parameters = new ArrayList<String>();
        var parents = new ArrayList<GenericType>();
        var annotations = new LinkedHashMap<String, Map<String, Object>>();
        var fields = new ArrayList<Field>();
        String[] name = {""};
        new ClassReader(stream).accept(new ClassVisitor(API) {
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
                Collections.unmodifiableMap(annotations), List.copyOf(fields));
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
