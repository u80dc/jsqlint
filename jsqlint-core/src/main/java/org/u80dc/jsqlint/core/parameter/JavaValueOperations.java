package org.u80dc.jsqlint.core.parameter;

import sootup.core.signatures.MethodSignature;
import java.util.*;

/** 显式的 Java 纯值语义模型；从不反射调用目标项目的方法。 @author u80dc */
public final class JavaValueOperations {
    private JavaValueOperations() {}
    private static final Map<String, String> WRAPPERS = Map.of(
            "java.lang.Boolean", "boolean", "java.lang.Byte", "byte", "java.lang.Short", "short",
            "java.lang.Integer", "int", "java.lang.Long", "long", "java.lang.Float", "float",
            "java.lang.Double", "double", "java.lang.Character", "char");

    public static boolean supports(MethodSignature signature) {
        String owner = signature.getDeclClassType().toString(), name = signature.getName();
        var types = signature.getParameterTypes().stream().map(Object::toString).toList();
        if (owner.equals("java.lang.String")) return switch (name) {
            case "equals" -> types.equals(List.of("java.lang.Object")) && signature.getType().toString().equals("boolean");
            case "trim", "strip", "stripLeading", "stripTrailing", "toLowerCase", "toUpperCase", "length", "isEmpty", "isBlank" -> types.isEmpty();
            case "substring" -> types.equals(List.of("int")) || types.equals(List.of("int", "int"));
            case "replace" -> types.equals(List.of("char", "char")) || types.equals(List.of("java.lang.CharSequence", "java.lang.CharSequence"));
            case "charAt", "repeat" -> types.equals(List.of("int"));
            default -> false;
        };
        if (!WRAPPERS.containsKey(owner)) return false;
        String primitive = WRAPPERS.get(owner);
        if (name.equals("valueOf")) return types.equals(List.of(primitive))
                || (!primitive.equals("char") && types.equals(List.of("java.lang.String")));
        if (name.equals("parse" + (primitive.equals("int") ? "Int" : Character.toUpperCase(primitive.charAt(0)) + primitive.substring(1))))
            return !primitive.equals("char") && types.equals(List.of("java.lang.String"));
        if (name.equals("toString")) return types.isEmpty() || types.equals(List.of(primitive));
        return types.isEmpty() && (name.equals(primitive + "Value")
                || (!Set.of("boolean", "char").contains(primitive) && Set.of("byteValue", "shortValue", "intValue", "longValue", "floatValue", "doubleValue").contains(name)));
    }

    static Object call(MethodSignature method, List<Object> operands, int maxLength) {
        if (!supports(method)) throw new IllegalArgumentException("Unmodeled method: " + method);
        String owner = method.getDeclClassType().toString(), name = method.getName();
        if (owner.equals("java.lang.String")) {
            String value = (String) Objects.requireNonNull(operands.getFirst(), "String receiver is null");
            return switch (name) {
                // 保留 String.equals(Object) 语义：区分大小写，null/非字符串参数为 false；null 接收者仍抛异常。
                case "equals" -> value.equals(operands.get(1));
                case "trim" -> value.trim(); case "strip" -> value.strip();
                case "stripLeading" -> value.stripLeading(); case "stripTrailing" -> value.stripTrailing();
                case "toLowerCase" -> value.toLowerCase(); case "toUpperCase" -> value.toUpperCase();
                case "length" -> value.length(); case "isEmpty" -> value.isEmpty(); case "isBlank" -> value.isBlank();
                case "substring" -> operands.size() == 2 ? value.substring(integer(operands.get(1)))
                        : value.substring(integer(operands.get(1)), integer(operands.get(2)));
                case "charAt" -> value.charAt(integer(operands.get(1)));
                case "repeat" -> {
                    int count = integer(operands.get(1));
                    if ((long) value.length() * count > maxLength) throw new InferenceLimitException("Inferred value length limit exceeded");
                    yield value.repeat(count);
                }
                case "replace" -> method.getParameterTypes().getFirst().toString().equals("char")
                        ? value.replace((Character) cast(operands.get(1), "char"), (Character) cast(operands.get(2), "char"))
                        : replace(value, (String) operands.get(1), (String) operands.get(2), maxLength);
                default -> throw new IllegalArgumentException("Unmodeled String operation");
            };
        }
        Object value = operands.getFirst();
        String primitive = WRAPPERS.get(owner);
        if (name.equals("toString")) return stringify(Objects.requireNonNull(value), primitive);
        if (name.startsWith("parse") || name.equals("valueOf")
                && method.getParameterTypes().getFirst().toString().equals("java.lang.String"))
            return parse((String) value, primitive);
        if (name.endsWith("Value")) return cast(Objects.requireNonNull(value), name.substring(0, name.length() - 5));
        return cast(Objects.requireNonNull(value), primitive);
    }

    static Object parse(String text, String type) {
        return switch (type) {
            case "java.lang.String" -> text;
            case "boolean", "java.lang.Boolean" -> Boolean.parseBoolean(text);
            case "byte", "java.lang.Byte" -> Byte.valueOf(text);
            case "short", "java.lang.Short" -> Short.valueOf(text);
            case "int", "java.lang.Integer" -> Integer.valueOf(text);
            case "long", "java.lang.Long" -> Long.valueOf(text);
            case "float", "java.lang.Float" -> Float.valueOf(text);
            case "double", "java.lang.Double" -> Double.valueOf(text);
            case "char", "java.lang.Character" -> (char) Integer.parseInt(text);
            default -> throw new IllegalArgumentException("Unmodeled literal type: " + type);
        };
    }

    static Object cast(Object value, String type) {
        if (value == null) {
            if (Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char").contains(type))
                throw new NullPointerException("Cannot unbox null");
            return null;
        }
        // 包装类型的 checkcast 不能当作数值转换，例如 (Integer) (Object) Long 会抛异常。
        if (WRAPPERS.containsKey(type)) {
            if (type.equals(value.getClass().getName())) return value;
            throw new ClassCastException("Cannot cast " + value.getClass().getName() + " to " + type);
        }
        String primitive = type;
        if (primitive.equals("boolean")) return value instanceof Boolean ? value : number(value).intValue() != 0;
        return switch (primitive) {
            case "byte" -> number(value).byteValue(); case "short" -> number(value).shortValue();
            case "int" -> number(value).intValue(); case "long" -> number(value).longValue();
            case "float" -> number(value).floatValue(); case "double" -> number(value).doubleValue();
            case "char" -> (char) number(value).intValue();
            default -> {
                if (type.equals("java.lang.Object") || type.equals(value.getClass().getName())
                        || type.equals("java.lang.Number") && value instanceof Number
                        || type.equals("java.lang.CharSequence") && value instanceof String) yield value;
                throw new IllegalArgumentException("Unmodeled reference cast to " + type);
            }
        };
    }

    static String stringify(Object value, String declaredType) {
        if (value == null) return "null";
        if (declaredType.equals("boolean") || declaredType.equals("char")) value = cast(value, declaredType);
        return String.valueOf(value);
    }

    static Object negate(Object value, String type) {
        Number number = number(value);
        return switch (type) {
            case "double" -> -number.doubleValue(); case "float" -> -number.floatValue();
            case "long" -> -number.longValue(); default -> -number.intValue();
        };
    }

    /** 遵守 JVM 的整数溢出、整数除法、移位掩码与浮点语义，不统一转成 double。 */
    static Object binary(String operator, Object left, Object right, String type) {
        Number a = number(left), b = number(right);
        if (type.equals("double") || type.equals("float")) {
            if (type.equals("float")) return switch (operator) {
                case "+" -> a.floatValue() + b.floatValue(); case "-" -> a.floatValue() - b.floatValue();
                case "*" -> a.floatValue() * b.floatValue(); case "/" -> a.floatValue() / b.floatValue();
                case "%" -> a.floatValue() % b.floatValue(); default -> throw unsupported(operator);
            };
            return switch (operator) {
                case "+" -> a.doubleValue() + b.doubleValue(); case "-" -> a.doubleValue() - b.doubleValue();
                case "*" -> a.doubleValue() * b.doubleValue(); case "/" -> a.doubleValue() / b.doubleValue();
                case "%" -> a.doubleValue() % b.doubleValue(); default -> throw unsupported(operator);
            };
        }
        if (type.equals("long")) return switch (operator) {
            case "+" -> a.longValue() + b.longValue(); case "-" -> a.longValue() - b.longValue();
            case "*" -> a.longValue() * b.longValue(); case "/" -> a.longValue() / b.longValue();
            case "%" -> a.longValue() % b.longValue(); case "&" -> a.longValue() & b.longValue();
            case "|" -> a.longValue() | b.longValue(); case "^" -> a.longValue() ^ b.longValue();
            case "<<" -> a.longValue() << b.intValue(); case ">>" -> a.longValue() >> b.intValue();
            case ">>>" -> a.longValue() >>> b.intValue(); default -> throw unsupported(operator);
        };
        int result = switch (operator) {
            case "+" -> a.intValue() + b.intValue(); case "-" -> a.intValue() - b.intValue();
            case "*" -> a.intValue() * b.intValue(); case "/" -> a.intValue() / b.intValue();
            case "%" -> a.intValue() % b.intValue(); case "&" -> a.intValue() & b.intValue();
            case "|" -> a.intValue() | b.intValue(); case "^" -> a.intValue() ^ b.intValue();
            case "<<" -> a.intValue() << b.intValue(); case ">>" -> a.intValue() >> b.intValue();
            case ">>>" -> a.intValue() >>> b.intValue(); default -> throw unsupported(operator);
        };
        return type.equals("boolean") ? (Object) (result != 0) : result;
    }

    static String replace(String value, String target, String replacement, int maxLength) {
        Objects.requireNonNull(value); Objects.requireNonNull(target); Objects.requireNonNull(replacement);
        long count = target.isEmpty() ? (long) value.length() + 1 : 0;
        if (!target.isEmpty() && replacement.length() > target.length())
            for (int offset = value.indexOf(target); offset >= 0; offset = value.indexOf(target, offset + target.length())) count++;
        if (value.length() + count * (replacement.length() - target.length()) > maxLength)
            throw new InferenceLimitException("Inferred value length limit exceeded");
        return value.replace(target, replacement);
    }
    private static Number number(Object value) {
        if (value instanceof Character c) return (int) c;
        if (value instanceof Boolean b) return b ? 1 : 0;
        return (Number) Objects.requireNonNull(value, "Numeric operand is null");
    }
    private static int integer(Object value) { return number(value).intValue(); }
    private static IllegalArgumentException unsupported(String operator) { return new IllegalArgumentException("Unmodeled binary operator: " + operator); }
}
