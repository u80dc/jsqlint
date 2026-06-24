package sootup.java.core;

import org.jspecify.annotations.NonNull;
import org.objectweb.asm.ClassReader;
import sootup.core.signatures.MethodSignature;
import sootup.core.signatures.MethodSubSignature;
import sootup.core.types.*;
import sootup.java.core.types.JavaClassType;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SootUp扩展工具类
 *
 * @author zhangcs
 */
public class JavaSootUtils {

    /**
     * 判断指定方法是否为类的构造方法
     *
     * @param method 方法对象
     * @return 是构造方法时返回true，否则返回false
     */
    public static boolean isConstructor(JavaSootMethod method) {
        // 不能是静态的
        if (method.isStatic()) {
            return false;
        }
        // 不能有返回值
        if (method.getReturnType() != VoidType.getInstance()) {
            return false;
        }
        String name = method.getName();
        return name.equals("<init>");
    }

    /**
     * 判断指定类是否有指定的注解
     *
     * @param clazz   类对象
     * @param targets 注解名称
     * @return 包含指定注解时返回true，否则返回false
     */
    public static boolean hasAnnotation(JavaSootClass clazz, Collection<String> targets) {
        Iterable<AnnotationUsage> annotations = clazz.getAnnotations();
        return hasAnnotation(annotations, targets);
    }

    /**
     * 判断指定方法是否有指定的注解
     *
     * @param method  方法对象
     * @param targets 注解名称
     * @return 包含指定注解时返回true，否则返回false
     */
    public static boolean hasAnnotation(JavaSootMethod method, Collection<String> targets) {
        Iterable<AnnotationUsage> annotations = method.getAnnotations();
        return hasAnnotation(annotations, targets);
    }

    public static boolean hasAnnotation(Iterable<AnnotationUsage> annotations, Collection<String> targets) {
        for (AnnotationUsage au : annotations) {
            ClassType annotation = au.getAnnotation();
            String qualifiedName = annotation.getFullyQualifiedName();
            if (targets.contains(qualifiedName)) {
                return true;
            }
        }
        return false;
    }

    public static String method2AsmDescriptor(JavaSootMethod method) {
        // 方法参数
        List<Type> parameterTypes = method.getParameterTypes();
        String params = parameterTypes.stream().map(JavaSootUtils::jimple2AsmType).collect(Collectors.joining(""));
        params = "(" + params + ")";
        // 方法返回值
        String returnType = jimple2AsmType(method.getReturnType());
        return params + returnType;
    }

    private static String jimple2AsmType(Type type) {
        // VoidType 不是 PrimitiveType 的子类，需要单独处理
        if (type.getClass().getSimpleName().equals("VoidType")) {
            return "V";
        }
        switch (type) {
            case PrimitiveType primitiveType -> {
                return jimple2AsmType(primitiveType);
            }
            case ArrayType arrayType -> {
                Type elementType = arrayType.getElementType();
                int dimension = arrayType.getDimension();
                StringBuilder tmp = new StringBuilder();
                tmp.repeat("[", Math.max(0, dimension));
                tmp.append(jimple2AsmType(elementType));
                return tmp.toString();
            }
            case ReferenceType referenceType -> {
                return String.format("L%s;", type.toString().replace(".", "/"));
            }
            default -> {
            }
        }
        return type.toString();
    }

    private static String jimple2AsmType(PrimitiveType type) {
        if (type instanceof PrimitiveType.BooleanType) {
            return "Z";  // boolean
        }
        if (type instanceof PrimitiveType.ByteType) {
            return "B";  // byte
        }
        if (type instanceof PrimitiveType.CharType) {
            return "C";  // char
        }
        if (type instanceof PrimitiveType.ShortType) {
            return "S";  // short
        }
        if (type instanceof PrimitiveType.FloatType) {
            return "F";  // float
        }
        if (type instanceof PrimitiveType.DoubleType) {
            return "D";  // double
        }
        if (type instanceof PrimitiveType.LongType) {
            return "J";  // long
        }
        if (type instanceof PrimitiveType.IntType) {
            return "I";  // int
        }
        // 兜底：如果无法识别，返回类型名称
        return type.toString();
    }

    public static int getJavaVersion(JavaSootClass clazz) {
        if (clazz == null) {
            // Java21
            return 65;
        }

        JavaSootClassSource source = clazz.getClassSource();
        Path path = source.getSourcePath();
        try (InputStream is = Files.newInputStream(path)) {
            ClassReader reader = new ClassReader(is);
            byte[] buf = reader.b;
            if (buf != null) {
                // 0-3: magic (0xCAFEBABE)
                // 4-5: minor_version
                // 6-7: major_version
                int ignore = ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
                return ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
            }
        } catch (Exception ignore) {
        }
        // Java21
        return 65;
    }

    /**
     * 获取 Jimple 格式的方法签名
     * <p>格式: &lt;class.Name: returnType methodName(param1, param2)&gt;
     *
     * @param fullyQualifiedClassName 类名
     * @param methodName              方法名
     * @param returnType              返回类型
     * @param paramTypes              参数类型数组
     * @return Jimple 格式签名
     */
    public static String getJimpleSignature(String fullyQualifiedClassName, String methodName,
                                           String returnType, String... paramTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(fullyQualifiedClassName).append(": ");
        sb.append(returnType).append(" ");
        sb.append(methodName).append("(");
        if (paramTypes != null && paramTypes.length > 0) {
            sb.append(String.join(", ", paramTypes));
        }
        sb.append(")>");
        return sb.toString();
    }

    /**
     * 获取指定的方法签名
     *
     * @param fullyQualifiedClassName 类名
     * @param methodName              方法名
     * @param returnType              返回类型
     * @param paramType               参数类型数组
     * @return 返回方法签名
     */
    @NonNull
    public static MethodSignature getMethodSignature(String fullyQualifiedClassName, String methodName, String returnType, String... paramType) {
        JavaIdentifierFactory factory = JavaIdentifierFactory.getInstance();
        List<JavaClassType> paramList = Arrays.stream(paramType).map(factory::getClassType).toList();
        MethodSubSignature subSignature = factory.getMethodSubSignature(methodName,
                factory.getClassType(returnType),
                paramList);
        return getMethodSignature(fullyQualifiedClassName, subSignature);
    }

    /**
     * 获取指定的方法签名
     *
     * @param fullyQualifiedClassName 类名
     * @param subSignature            方法名
     * @return 返回方法签名
     */
    @NonNull
    public static MethodSignature getMethodSignature(String fullyQualifiedClassName, MethodSubSignature subSignature) {
        JavaIdentifierFactory factory = JavaIdentifierFactory.getInstance();
        return factory.getMethodSignature(
                factory.getClassType(fullyQualifiedClassName),
                subSignature
        );
    }

    public static boolean equals(MethodSignature s1, MethodSignature s2) {
        if (s1 == s2) {
            return true;
        }
        // 方法名
        if (!s1.getName().equals(s2.getName())) {
            return false;
        }
        // 所属类全名
        String c1 = s1.getDeclClassType().getFullyQualifiedName();
        String c2 = s2.getDeclClassType().getFullyQualifiedName();
        if (!c1.equals(c2)) {
            return false;
        }
        // 方法参数、返回值
        String subSignature1 = s1.getSubSignature().toString();
        String subSignature2 = s2.getSubSignature().toString();
        return subSignature1.equals(subSignature2);
    }

    /**
     * 检查两个类型是否兼容
     */
    public static boolean isCompatibleType(Type type1, String type2) {
        if (type1 == null || type2 == null) {
            return false;
        }

        String type1Str = type1.toString();

        // 基本类型兼容性检查
        if (type1Str.equals(type2)) {
            return true;
        }

        // 处理包装类型兼容性
        Map<String, String> primitiveToWrapper = Map.of(
                "int", "java.lang.Integer",
                "long", "java.lang.Long",
                "boolean", "java.lang.Boolean",
                "char", "java.lang.Character",
                "float", "java.lang.Float",
                "double", "java.lang.Double",
                "byte", "java.lang.Byte",
                "short", "java.lang.Short"
        );

        // 检查是否为对应的包装类型
        if (primitiveToWrapper.containsKey(type1Str) &&
                primitiveToWrapper.get(type1Str).equals(type2)) {
            return true;
        }

        if (primitiveToWrapper.containsKey(type2) &&
                primitiveToWrapper.get(type2).equals(type1Str)) {
            return true;
        }

        // 检查继承关系（简化版本）
        if (type2.equals("java.lang.Object") &&
                !type1Str.startsWith("java.lang.") &&
                !type1Str.equals("void")) {
            return true;
        }

        return false;
    }

    /**
     * 检查是否为 LambdaMetafactory.metafactory 调用
     */
    public static boolean isLambdaMetafactory(MethodSignature bootstrapMethod) {
        return "java.lang.invoke.LambdaMetafactory".equals(bootstrapMethod.getDeclClassType().getFullyQualifiedName()) &&
                "metafactory".equals(bootstrapMethod.getName());
    }

}
