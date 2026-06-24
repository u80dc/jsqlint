package cn.jnu.zhangcs.jsqlint.analysis.ibatis.impl;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootClassSource;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.JavaSootUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static sootup.java.core.JavaSootUtils.getJavaVersion;

/**
 * Mapper接口实现构建器
 * <p>
 *     为每个Mapper接口方法创建空实现，主要用于调用图的构建
 * </p>
 *
 * @author zhangcs
 */
@Slf4j
public class MapperImplBuilder {

    public static final String CLASS_NAME_SUFFIX = "$Impl";

    /**
     * 创建实现类
     *
     * @param mappers Mapper接口
     * @return 返回实现类文件路径
     */
    @NonNull
    public static Set<Path> newInstance(Collection<JavaSootClass> mappers) {
        if (mappers == null) {
            return Collections.emptySet();
        }
        return mappers.stream()
                .map(MapperImplBuilder::newInstance)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * 创建实现类
     *
     * @param mapper Mapper接口
     * @return 返回实现类文件路径
     */
    @Nullable
    public static Path newInstance(@NonNull JavaSootClass mapper) {
        String className = mapper.getName();
        if (!mapper.isInterface() && !mapper.isAbstract()) {
            log.debug("Skip `{}`: Must be an interface or an abstract class", className);
            return null;
        }

        Set<JavaSootMethod> methods = mapper.getMethods();
        if (methods.isEmpty()) {
            log.debug("Skip `{}`: No valid methods defined.", className);
            return null;
        }

        String implFileName = buildImplClassFileName(mapper);

        // 自动计算栈帧和局部变量表大小
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(getJavaVersion(mapper), Opcodes.ACC_PUBLIC,
                implFileName, null, "java/lang/Object", new String[]{className});

        // 生成默认的构造方法 <init>
        createInitMethod(writer);

        // 遍历生成方法实现
        for (JavaSootMethod method : methods) {
            createMethod(method, writer);
        }

        // 类定义完成，生成最终的字节码数组
        writer.visitEnd();
        byte[] bytecode = writer.toByteArray();

        // 写文件
        JavaSootClassSource source = mapper.getClassSource();
        Path parent = source.getSourcePath().getParent();
        try {
            // 去除路径信息，获取含后缀的文件名
            String simpleFileName = implFileName.substring(implFileName.lastIndexOf("/") + 1) + ".class";
            Path file = parent.resolve(simpleFileName);
            Files.write(file, bytecode, StandardOpenOption.CREATE);
            log.debug("接口实现类`{}`创建完成.", implFileName);
            return file;
        } catch (Exception e) {
            log.warn("创建接口实现类失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取指定方法对应的实现类方法
     *
     * @param method 指定方法
     * @return 返回对应的实现方法
     */
    @NonNull
    public static MethodSignature getImpl(JavaSootMethod method) {
        MethodSignature signature = method.getSignature();
        return getImpl(signature);
    }

    /**
     * 获取指定方法对应的代理类方法
     *
     * @param signature 指定方法
     * @return 返回对应的实现方法
     */
    @NonNull
    public static MethodSignature getImpl(MethodSignature signature) {
        String fullyQualifiedName = signature.getDeclClassType().getFullyQualifiedName();
        String className = fullyQualifiedName + CLASS_NAME_SUFFIX;
        return JavaSootUtils.getMethodSignature(className, signature.getSubSignature());
    }

    /**
     * 创建默认构造方法
     *
     * @param writer 类生成器对象
     */
    private static void createInitMethod(ClassWriter writer) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        visitor.visitCode();
        // 将this引用加载到操作数栈
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        // 调用父类(Object)构造方法
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // 方法返回
        visitor.visitInsn(Opcodes.RETURN);
        // 设置栈帧和局部变量表大小（或使用COMPUTE_MAXS自动计算）
        visitor.visitMaxs(1, 1);
        visitor.visitEnd();
    }

    /**
     * 创建空实现方法
     *
     * @param method 接口方法
     * @param writer 类生成器对象
     */
    private static void createMethod(JavaSootMethod method, ClassWriter writer) {
        if (method.isNative() || method.hasBody()) {
            return;
        }

        // 方法名
        String methodName = method.getName();
        // 访问标识
        int access = Opcodes.ACC_PUBLIC;
        if (method.isPrivate()) {
            access = Opcodes.ACC_PRIVATE;
        } else if (method.isProtected()) {
            access = Opcodes.ACC_PROTECTED;
        }
        // 方法描述符（参数+返回值类型）
        String descriptor = JavaSootUtils.method2AsmDescriptor(method);
        // 异常
        String[] exceptions = method.getExceptionSignatures()
                .stream()
                .map(ClassType::getFullyQualifiedName)
                .toArray(String[]::new);

        // 返回null的空方法
        MethodVisitor visitor = writer.visitMethod(access, methodName, descriptor, null, exceptions);
        visitor.visitCode();

        Type returnType = Type.getReturnType(descriptor);
        if (returnType.getSort() == Type.VOID) {
            visitor.visitInsn(Opcodes.RETURN);
        } else if (returnType.getSort() == Type.BOOLEAN) {
            visitor.visitInsn(Opcodes.ICONST_0);
            visitor.visitInsn(Opcodes.IRETURN);
        } else if (returnType.getSort() == Type.BYTE || returnType.getSort() == Type.CHAR ||
                returnType.getSort() == Type.INT || returnType.getSort() == Type.SHORT) {
            visitor.visitInsn(Opcodes.ICONST_0);
            visitor.visitInsn(Opcodes.IRETURN);
        } else if (returnType.getSort() == Type.LONG) {
            visitor.visitInsn(Opcodes.LCONST_0);
            visitor.visitInsn(Opcodes.LRETURN);
        } else if (returnType.getSort() == Type.FLOAT) {
            visitor.visitInsn(Opcodes.FCONST_0);
            visitor.visitInsn(Opcodes.FRETURN);
        } else if (returnType.getSort() == Type.DOUBLE) {
            visitor.visitInsn(Opcodes.DCONST_0);
            visitor.visitInsn(Opcodes.DRETURN);
        } else {
            // 对象类型返回 null
            visitor.visitInsn(Opcodes.ACONST_NULL);
            visitor.visitInsn(Opcodes.ARETURN);
        }

        visitor.visitMaxs(1, 1);
        visitor.visitEnd();
    }

    /**
     * 构造实现类文件名称
     *
     * @param mapper 接口类
     * @return 返回文件名（不含文件后缀）
     */
    private static String buildImplClassFileName(JavaSootClass mapper) {
        String className = mapper.getName();
        return String.format("%s%s", className.replaceAll("\\.", "/"), CLASS_NAME_SUFFIX);
    }

}
