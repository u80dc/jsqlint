package org.u80dc.jsqlint.core.semantics;

import org.u80dc.jsqlint.core.dataflow.ValueTrace.Computation;
import org.u80dc.jsqlint.core.dataflow.ValueTrace.Operation;
import sootup.core.signatures.MethodSignature;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.JDynamicInvokeExpr;
import sootup.core.jimple.common.expr.AbstractBinopExpr;
import sootup.core.jimple.common.expr.JDivExpr;
import sootup.core.jimple.common.expr.JRemExpr;
import java.util.*;

/** 显式的 Java 纯值语义模型；从不反射调用目标项目的方法。 @author u80dc */
public final class JavaValueOperations {
    private JavaValueOperations() {}
    private static final Map<String, String> WRAPPERS = Map.of(
            "java.lang.Boolean", "boolean", "java.lang.Byte", "byte", "java.lang.Short", "short",
            "java.lang.Integer", "int", "java.lang.Long", "long", "java.lang.Float", "float",
            "java.lang.Double", "double", "java.lang.Character", "char");

    /** 执行已知操作数上的带类型运算；分支选择、符号约束及未知值由调用方处理。 */
    public static Object evaluate(Computation computation, String type, List<Object> operands, int maxLength) {
        Objects.requireNonNull(computation); Objects.requireNonNull(type); Objects.requireNonNull(operands);
        if (maxLength < 1) throw new IllegalArgumentException("Value length budget must be positive");
        Object result = switch (computation.operation()) {
            case LITERAL -> parse(computation.text(), type);
            case NULL -> null;
            case IDENTITY, ALTERNATIVES -> {
                requireOperands(operands, 1);
                Object value = operands.getFirst();
                yield WRAPPERS.containsValue(type) ? cast(value, type) : value;
            }
            case REQUIRE_NON_NULL, STRING_COPY -> {
                requireOperands(operands, 1);
                yield Objects.requireNonNull(operands.getFirst(), "Null input on normal-return path");
            }
            case STRING_CAST -> {
                requireOperands(operands, 1);
                yield cast(operands.getFirst(), "java.lang.String");
            }
            case STRINGIFY -> {
                requireOperands(operands, 1);
                yield stringify(operands.getFirst(), computation.text());
            }
            case CONCAT, STRING_CONCAT -> {
                StringBuilder text = new StringBuilder();
                for (Object value : operands) {
                    if (computation.operation() == Operation.STRING_CONCAT)
                        Objects.requireNonNull(value, "String.concat null operand");
                    String part = String.valueOf(value);
                    if ((long) text.length() + part.length() > maxLength)
                        throw new ValueEvaluationLimitException("Inferred value length limit exceeded");
                    text.append(part);
                }
                yield text.toString();
            }
            case REPLACE -> {
                requireOperands(operands, 3);
                yield replace((String) operands.get(0), (String) operands.get(1), (String) operands.get(2), maxLength);
            }
            case BINARY -> {
                requireOperands(operands, 2);
                yield binary(computation.text(), operands.get(0), operands.get(1), type);
            }
            case NEGATE -> {
                requireOperands(operands, 1);
                yield negate(operands.getFirst(), type);
            }
            case CAST -> {
                requireOperands(operands, 1);
                yield cast(operands.getFirst(), computation.text());
            }
            case CALL -> call(computation.method().orElseThrow(), operands, maxLength);
            case NORMAL_COMPLETION -> normalCompletion(computation.method().orElseThrow(), operands, maxLength);
            case ARRAY_ACCESS_CHECK -> {
                requireOperands(operands, 3);
                int index = arrayInt(operands.get(1)), length = arrayInt(operands.get(2));
                if (length < 0) throw new NegativeArraySizeException("Negative allocated array length");
                if (index < 0 || index >= length) throw new ArrayIndexOutOfBoundsException("Index " + index + " outside array length " + length);
                yield operands.getFirst();
            }
            case MAP_LITERAL -> {
                if (operands.size() % 2 != 0) throw new IllegalArgumentException("Invalid map snapshot operands");
                var map = new LinkedHashMap<Object, Object>();
                for (int i = 0; i < operands.size(); i += 2) map.put(operands.get(i), operands.get(i + 1));
                yield Collections.unmodifiableMap(map);
            }
            default -> throw new IllegalArgumentException("Unmodeled operation: " + computation.operation());
        };
        if (result instanceof String text && text.length() > maxLength)
            throw new ValueEvaluationLimitException("Inferred value length limit exceeded");
        return result;
    }

    private static void requireOperands(List<Object> operands, int expected) {
        if (operands.size() != expected) throw new IllegalArgumentException("Invalid value operation operands");
    }

    /** 整数除法和余数的正常完成要求除数非零；浮点零除遵循 IEEE 语义。 */
    public static Optional<Value> nonZeroDivisor(Value expression) {
        if (expression instanceof AbstractBinopExpr binary && (expression instanceof JDivExpr || expression instanceof JRemExpr)
                && Set.of("int", "long").contains(expression.getType().toString())) return Optional.of(binary.getOp2());
        return Optional.empty();
    }

    /** 方法的值运算及其有序依赖；追踪返回值和检查丢弃返回值的调用共用同一识别入口。 */
    public record InvocationOperation(Computation computation, List<Value> operands) {
        public InvocationOperation { operands = List.copyOf(operands); }
    }

    public static Optional<InvocationOperation> invocation(AbstractInvokeExpr invocation) {
        var modeled = methodComputation(invocation.getMethodSignature());
        if (modeled.isEmpty()) return Optional.empty();
        Computation computation = modeled.get();
        List<Value> operands = new ArrayList<>();
        if (computation.operation() == Operation.REQUIRE_NON_NULL || computation.operation() == Operation.STRING_COPY)
            operands.add(invocation.getArg(0));
        else {
            if (invocation instanceof AbstractInstanceInvokeExpr instance) operands.add(instance.getBase());
            operands.addAll(invocation.getArgs());
        }
        // 保留已有 SQL 投影所用的显式 replace 运算，其求值仍与 CALL 共用实现。
        if (invocation instanceof AbstractInstanceInvokeExpr && invocation.getMethodSignature().getDeclClassType().toString().equals("java.lang.String")
                && invocation.getMethodSignature().getName().equals("replace") && invocation.getArgCount() == 2
                && invocation.getArg(0) instanceof StringConstant && invocation.getArg(1) instanceof StringConstant)
            computation = new Computation(Operation.REPLACE, "String.replace");
        return Optional.of(new InvocationOperation(computation, operands));
    }

    private static Optional<Computation> methodComputation(MethodSignature method) {
        String owner = method.getDeclClassType().toString(), name = method.getName();
        var types = method.getParameterTypes().stream().map(Object::toString).toList();
        if (owner.equals("java.util.Objects") && name.equals("requireNonNull")
                && (types.equals(List.of("java.lang.Object")) || types.equals(List.of("java.lang.Object", "java.lang.String"))
                || types.equals(List.of("java.lang.Object", "java.util.function.Supplier"))))
            return Optional.of(new Computation(Operation.REQUIRE_NON_NULL, "Objects.requireNonNull"));
        if (owner.equals("java.lang.String")) {
            if (name.equals("valueOf") && types.size() == 1 && !types.getFirst().equals("char[]"))
                return Optional.of(new Computation(Operation.STRINGIFY, types.getFirst()));
            if (name.equals("concat") && types.equals(List.of("java.lang.String")))
                return Optional.of(new Computation(Operation.STRING_CONCAT, "String.concat"));
            if (name.equals("<init>") && types.equals(List.of("java.lang.String")))
                return Optional.of(new Computation(Operation.STRING_COPY, "String constructor"));
        }
        return supports(method) ? Optional.of(new Computation(Operation.CALL, name, Optional.of(method))) : Optional.empty();
    }

    private static boolean normalCompletion(MethodSignature method, List<Object> operands, int maxLength) {
        Computation computation = methodComputation(method)
                .orElseThrow(() -> new IllegalArgumentException("Unmodeled normal completion: " + method));
        try {
            if (method.getDeclClassType().toString().equals("java.lang.String") && method.getName().equals("repeat")
                    && integer(operands.get(1)) < 0) return false;
            evaluate(computation, method.getType().toString(), operands, maxLength);
            return true;
        } catch (NullPointerException | NumberFormatException | IndexOutOfBoundsException | ArithmeticException failure) {
            return false;
        }
        // 容量限制、错误模型类型和未支持的语义不能被解释为目标程序必然抛异常。
    }

    /** 只有标量和 String 的 JVM 拼接不会调用目标对象的 toString。 */
    public static boolean scalarStringConcat(AbstractInvokeExpr invocation) {
        return invocation instanceof JDynamicInvokeExpr dynamic
                && dynamic.getBootstrapMethodSignature().getDeclClassType().toString().equals("java.lang.invoke.StringConcatFactory")
                && Set.of("makeConcat", "makeConcatWithConstants").contains(dynamic.getBootstrapMethodSignature().getName())
                && invocation.getMethodSignature().getParameterTypes().stream().map(Object::toString)
                    .allMatch(type -> type.equals("java.lang.String") || WRAPPERS.containsValue(type) || WRAPPERS.containsKey(type));
    }

    public static boolean supports(MethodSignature signature) {
        String owner = signature.getDeclClassType().toString(), name = signature.getName();
        var types = signature.getParameterTypes().stream().map(Object::toString).toList();
        if (owner.equals("java.util.Map")) return switch (name) {
            case "get", "containsKey" -> types.equals(List.of("java.lang.Object"));
            case "getOrDefault" -> types.equals(List.of("java.lang.Object", "java.lang.Object"));
            case "size", "isEmpty" -> types.isEmpty();
            default -> false;
        };
        if (owner.equals("java.lang.String")) return switch (name) {
            case "equals" -> types.equals(List.of("java.lang.Object")) && signature.getType().toString().equals("boolean");
            // 无参大小写转换读取目标 JVM 的默认 Locale；分析器没有这一运行时事实。
            case "trim", "strip", "stripLeading", "stripTrailing", "length", "isEmpty", "isBlank" -> types.isEmpty();
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

    public static Object call(MethodSignature method, List<Object> operands, int maxLength) {
        if (!supports(method)) throw new IllegalArgumentException("Unmodeled method: " + method);
        String owner = method.getDeclClassType().toString(), name = method.getName();
        if (owner.equals("java.util.Map")) {
            var map = (Map<?, ?>) Objects.requireNonNull(operands.getFirst(), "Map receiver is null");
            return switch (name) {
                case "get" -> map.get(operands.get(1));
                case "getOrDefault" -> map.containsKey(operands.get(1)) ? map.get(operands.get(1)) : operands.get(2);
                case "containsKey" -> map.containsKey(operands.get(1));
                case "size" -> map.size(); case "isEmpty" -> map.isEmpty();
                default -> throw new IllegalArgumentException("Unmodeled Map operation");
            };
        }
        if (owner.equals("java.lang.String")) {
            String value = (String) Objects.requireNonNull(operands.getFirst(), "String receiver is null");
            return switch (name) {
                // 保留 String.equals(Object) 语义：区分大小写，null/非字符串参数为 false；null 接收者仍抛异常。
                case "equals" -> value.equals(operands.get(1));
                case "trim" -> value.trim(); case "strip" -> value.strip();
                case "stripLeading" -> value.stripLeading(); case "stripTrailing" -> value.stripTrailing();
                case "length" -> value.length(); case "isEmpty" -> value.isEmpty(); case "isBlank" -> value.isBlank();
                case "substring" -> operands.size() == 2 ? value.substring(integer(operands.get(1)))
                        : value.substring(integer(operands.get(1)), integer(operands.get(2)));
                case "charAt" -> value.charAt(integer(operands.get(1)));
                case "repeat" -> {
                    int count = integer(operands.get(1));
                    if ((long) value.length() * count > maxLength) throw new ValueEvaluationLimitException("Inferred value length limit exceeded");
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

    public static Object parse(String text, String type) {
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

    public static Object cast(Object value, String type) {
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
                        || type.equals("java.util.Map") && value instanceof Map<?, ?>
                        || type.equals("java.lang.Number") && value instanceof Number
                        || type.equals("java.lang.CharSequence") && value instanceof String) yield value;
                throw new IllegalArgumentException("Unmodeled reference cast to " + type);
            }
        };
    }

    public static String stringify(Object value, String declaredType) {
        if (value == null) return "null";
        if (declaredType.equals("boolean") || declaredType.equals("char")) value = cast(value, declaredType);
        return String.valueOf(value);
    }

    public static Object negate(Object value, String type) {
        Number number = number(value);
        return switch (type) {
            case "double" -> -number.doubleValue(); case "float" -> -number.floatValue();
            case "long" -> -number.longValue(); default -> -number.intValue();
        };
    }

    /** 遵守 JVM 的整数溢出、整数除法、移位掩码与浮点语义，不统一转成 double。 */
    public static Object binary(String operator, Object left, Object right, String type) {
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

    public static String replace(String value, String target, String replacement, int maxLength) {
        Objects.requireNonNull(value); Objects.requireNonNull(target); Objects.requireNonNull(replacement);
        long count = target.isEmpty() ? (long) value.length() + 1 : 0;
        if (!target.isEmpty() && replacement.length() > target.length())
            for (int offset = value.indexOf(target); offset >= 0; offset = value.indexOf(target, offset + target.length())) count++;
        if (value.length() + count * (replacement.length() - target.length()) > maxLength)
            throw new ValueEvaluationLimitException("Inferred value length limit exceeded");
        return value.replace(target, replacement);
    }
    private static Number number(Object value) {
        if (value instanceof Character c) return (int) c;
        if (value instanceof Boolean b) return b ? 1 : 0;
        return (Number) Objects.requireNonNull(value, "Numeric operand is null");
    }
    private static int integer(Object value) { return number(value).intValue(); }
    /** JVM 数组下标和长度接受 int 及其窄整数来源，不能截断 long/浮点值。 */
    private static int arrayInt(Object value) {
        if (value instanceof Character character) return character;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) return ((Number) value).intValue();
        throw new IllegalArgumentException("Array index and length must have Java int semantics");
    }
    private static IllegalArgumentException unsupported(String operator) { return new IllegalArgumentException("Unmodeled binary operator: " + operator); }
}
