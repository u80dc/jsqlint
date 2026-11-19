package org.u80dc.jsqlint.core.semantics;

import java.util.*;

/** 入口事实的声明类型校验；合法赋值转换与目标程序的显式 CAST 分开处理。 */
public final class JavaValueFacts {
    private JavaValueFacts() {}

    public static Object assignment(Object value, String declaredType) {
        Objects.requireNonNull(declaredType);
        boolean primitive = Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double").contains(declaredType);
        if (value == null) {
            if (primitive) throw invalid(declaredType);
            return null;
        }
        if (!primitive) {
            if (!compatible(value.getClass(), declaredType)) throw invalid(declaredType);
            return value;
        }
        boolean accepted = switch (declaredType) {
            case "boolean" -> value instanceof Boolean;
            case "byte" -> value instanceof Byte;
            case "short" -> value instanceof Byte || value instanceof Short;
            case "char" -> value instanceof Character;
            case "int" -> integral(value) && !(value instanceof Long);
            case "long" -> integral(value);
            case "float" -> integral(value) || value instanceof Float;
            case "double" -> integral(value) || value instanceof Float || value instanceof Double;
            default -> false;
        };
        if (!accepted) throw invalid(declaredType);
        return JavaValueOperations.cast(value, declaredType);
    }

    private static boolean integral(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Character
                || value instanceof Integer || value instanceof Long;
    }

    /** 查询已提供对象的类型元数据，不加载或运行目标项目的类型。 */
    private static boolean compatible(Class<?> actual, String declaredType) {
        if (declaredType.endsWith("[]"))
            return actual.isArray() && compatible(actual.getComponentType(), declaredType.substring(0, declaredType.length() - 2));
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Class<?>> pending = new ArrayDeque<>(); pending.add(actual);
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (!seen.add(type)) continue;
            if (type.getName().equals(declaredType)) return true;
            if (type.getSuperclass() != null) pending.add(type.getSuperclass());
            Collections.addAll(pending, type.getInterfaces());
        }
        return false;
    }

    private static IllegalArgumentException invalid(String type) {
        return new IllegalArgumentException("External input does not have Java " + type + " assignment semantics");
    }
}
