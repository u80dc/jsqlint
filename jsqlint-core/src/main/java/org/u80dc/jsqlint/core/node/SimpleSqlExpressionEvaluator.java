package org.u80dc.jsqlint.core.node;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 有界语法子集：字面量、属性/下标、比较、加减、布尔运算、size/length/isEmpty。
 * 不执行任意 OGNL 方法、静态方法或构造器；无法求值时显式返回 Unknown。
 * 对象属性读取会调用 getter/record 访问器，其无副作用约束由提供参数对象的调用方保证。
 * 中间值保留输入身份，只有最终结果形成快照。数字按数值比较，其他值遵循 Objects.equals；
 * 数组按引用比较，集合沿用自身的 equals，不以快照副本的身份决定表达式结果。
 *
 * @author u80dc
 */
final class SimpleSqlExpressionEvaluator implements SqlExpressionEvaluator {
    /** Activity values are private to one evaluation; persisted Known values are created only at its boundary. */
    private record ActiveValue(Object value, SqlValue.Unknown failure, Reference reference) {
        boolean isUnknown() { return failure != null; }
        SqlValue snapshot() { return isUnknown() ? failure : new SqlValue.Known(value); }
        SqlValue.Truth truth() {
            if (isUnknown()) return SqlValue.Truth.UNKNOWN;
            return switch (value) {
                case null -> SqlValue.Truth.FALSE;
                case Boolean bool -> bool ? SqlValue.Truth.TRUE : SqlValue.Truth.FALSE;
                case BigDecimal decimal -> decimal.signum() == 0 ? SqlValue.Truth.FALSE : SqlValue.Truth.TRUE;
                case BigInteger integer -> integer.signum() == 0 ? SqlValue.Truth.FALSE : SqlValue.Truth.TRUE;
                case Number number -> number.doubleValue() == 0 ? SqlValue.Truth.FALSE : SqlValue.Truth.TRUE;
                default -> SqlValue.Truth.TRUE;
            };
        }
    }

    private static ActiveValue value(Object value) {
        return value(value, null);
    }

    private static ActiveValue value(Object value, Reference reference) {
        if (value instanceof SqlValue.Unknown unknown) return new ActiveValue(null, unknown, null);
        return new ActiveValue(value instanceof SqlValue.Known known ? known.snapshotValue() : value, null, reference);
    }

    private static Reference accessed(ActiveValue base, Object key) {
        if (base.reference() == null) return null;
        var path = new java.util.ArrayList<>(base.reference().path()); path.add(key);
        return new Reference(base.reference().name(), path);
    }

    private static ActiveValue unknown(String reason) {
        return new ActiveValue(null, new SqlValue.Unknown(reason), null);
    }

    private static ActiveValue booleanOp(ActiveValue a, ActiveValue b, boolean and) {
        var x = a.truth();
        var y = b.truth();
        if (and && (x == SqlValue.Truth.FALSE || y == SqlValue.Truth.FALSE)) {
            return value(false);
        }
        if (!and && (x == SqlValue.Truth.TRUE || y == SqlValue.Truth.TRUE)) {
            return value(true);
        }
        if (a.isUnknown()) {
            return a;
        }
        if (b.isUnknown()) {
            return b;
        }
        return value(and);
    }

    private static ActiveValue binary(ActiveValue a, String op, ActiveValue b) {
        if (a.isUnknown()) {
            return a;
        }
        if (b.isUnknown()) {
            return b;
        }
        Object x = a.value();
        Object y = b.value();
        if (op.equals("==") || op.equals("!=")) {
            boolean equal = x instanceof Number && y instanceof Number
                    ? decimal(x).compareTo(decimal(y)) == 0 : Objects.equals(x, y);
            return value(op.equals("==") == equal);
        }
        if (op.equals("+") && (x instanceof String || y instanceof String)) {
            return value(String.valueOf(x) + y);
        }
        if (x instanceof Number && y instanceof Number) {
            BigDecimal left = decimal(x), right = decimal(y);
            return switch (op) {
                case "+" -> value(left.add(right));
                case "-" -> value(left.subtract(right));
                case ">" -> value(left.compareTo(right) > 0);
                case ">=" -> value(left.compareTo(right) >= 0);
                case "<" -> value(left.compareTo(right) < 0);
                case "<=" -> value(left.compareTo(right) <= 0);
                default -> unknown("Unsupported operator: " + op);
            };
        }
        return unknown("Unsupported operands for " + op);
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }

    private static ActiveValue property(ActiveValue base, Object key) {
        if (base.isUnknown()) {
            return base;
        }
        Object target = base.value();
        if (target == null) {
            return unknown("Cannot read property " + key + " of null");
        }
        if (target instanceof Map<?, ?> map) {
            return map.containsKey(key) ? value(map.get(key), accessed(base, key)) : unknown("Missing property: " + key);
        }
        if (key instanceof Number index) {
            int i;
            try {
                i = decimal(index).intValueExact();
            } catch (ArithmeticException e) {
                return unknown("Invalid index: " + key);
            }
            if (target instanceof List<?> list && i >= 0 && i < list.size()) {
                return value(list.get(i), accessed(base, i));
            }
            if (target.getClass().isArray() && i >= 0 && i < Array.getLength(target)) {
                return value(Array.get(target, i), accessed(base, i));
            }
            return unknown("Index out of bounds or unsupported target: " + key);
        }
        String name = String.valueOf(key);
        if (name.equals("length") && target.getClass().isArray()) {
            return value(Array.getLength(target));
        }
        if (name.isEmpty()) {
            return unknown("Empty property");
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String getter : List.of("get" + suffix, "is" + suffix, name)) {
            try {
                Method method = target.getClass().getMethod(getter);
                if (getter.equals(name) && !target.getClass().isRecord()) {
                    continue;
                }
                if (!method.canAccess(target) && !method.trySetAccessible()) {
                    continue;
                }
                return value(method.invoke(target), accessed(base, key));
            } catch (NoSuchMethodException ignored) {
                // Try the next property accessor.
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (e instanceof RuntimeException runtime) SqlEvaluationContext.rethrowControl(runtime);
                return unknown("Cannot read property: " + name);
            }
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.canAccess(target) && !field.trySetAccessible()) {
                    break;
                }
                return value(field.get(target), accessed(base, key));
            } catch (NoSuchFieldException ignored) {
                // Also support inherited fields.
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (e instanceof RuntimeException runtime) SqlEvaluationContext.rethrowControl(runtime);
                return unknown("Cannot read property: " + name);
            }
        }
        return unknown("Missing property: " + name);
    }

    private static ActiveValue size(ActiveValue input, String operation) {
        if (input.isUnknown()) {
            return input;
        }
        Object target = input.value();
        int size;
        if (target instanceof Collection<?> collection) {
            size = collection.size();
        } else if (target instanceof Map<?, ?> map) {
            size = map.size();
        } else if (target instanceof CharSequence chars) {
            size = chars.length();
        } else if (target != null && target.getClass().isArray()) {
            size = Array.getLength(target);
        } else {
            return unknown("Cannot call " + operation + " on this value");
        }
        return value(operation.equals("isEmpty") ? size == 0 : size);
    }

    @Override
    public SqlValue evaluate(String expression, Map<String, ?> parameters) {
        var context = SqlEvaluationContext.current();
        try {
            Parser parser = new Parser(expression, parameters);
            ActiveValue result = parser.or();
            parser.expect("");
            return context.result(result.snapshot(), result.value(), result.reference(), java.util.Optional.empty());
        } catch (RuntimeException e) {
            SqlEvaluationContext.rethrowControl(e);
            String reason = e.getMessage() == null ? "Cannot evaluate expression" : e.getMessage();
            return context.result(new SqlValue.Unknown(reason), null, null,
                    e instanceof EvaluationLimit ? java.util.Optional.of(reason) : java.util.Optional.empty());
        }
    }

    private static final class EvaluationLimit extends RuntimeException {
        EvaluationLimit(String message) { super(message, null, false, false); }
    }

    private record Token(String text, Object literal, boolean literalToken, boolean identifier) {
    }

    private static final class Parser {
        private final String source;
        private final Map<String, ?> parameters;
        private int position;
        private Token token;
        private int depth;
        private int steps;
        private final SqlEvaluationContext context = SqlEvaluationContext.current();

        private Parser(String source, Map<String, ?> parameters) {
            this.source = Objects.requireNonNull(source, "expression");
            this.parameters = Objects.requireNonNull(parameters, "parameters");
            if (source.length() > Math.min(16384, context.replayBudgets().length())) {
                fail("Expression text length budget exceeded");
            }
            next();
        }

        private ActiveValue or() {
            ActiveValue result = and();
            while (accept("or") || accept("||")) {
                result = booleanOp(result, and(), false);
            }
            return result;
        }

        private ActiveValue and() {
            ActiveValue result = comparison();
            while (accept("and") || accept("&&")) {
                result = booleanOp(result, comparison(), true);
            }
            return result;
        }

        private ActiveValue comparison() {
            ActiveValue result = sum();
            while (!token.literalToken() && List.of("==", "!=", ">", ">=", "<", "<=").contains(token.text())) {
                String operator = token.text();
                next();
                result = binary(result, operator, sum());
            }
            return result;
        }

        private ActiveValue sum() {
            ActiveValue result = unary();
            while (is("+") || is("-")) {
                String operator = token.text();
                next();
                result = binary(result, operator, unary());
            }
            return result;
        }

        private ActiveValue unary() {
            if (++depth > Math.min(128, context.replayBudgets().depth())) {
                fail("Expression nesting depth budget exceeded");
            }
            try {
                if (accept("!") || accept("not")) {
                    ActiveValue operand = unary();
                    return operand.isUnknown() ? operand : value(operand.truth() != SqlValue.Truth.TRUE);
                }
                if (accept("-")) {
                    return binary(value(0), "-", unary());
                }
                if (accept("+")) {
                    return unary();
                }
                return primary();
            } finally {
                depth--;
            }
        }

        private ActiveValue primary() {
            ActiveValue result;
            if (accept("(")) {
                result = or();
                expect(")");
            } else if (token.literalToken()) {
                result = value(token.literal());
                next();
            } else if (token.identifier()) {
                String name = token.text();
                next();
                result = switch (name) {
                    case "null" -> value(null);
                    case "true" -> value(true);
                    case "false" -> value(false);
                    default -> parameters.containsKey(name) ? value(parameters.get(name), new Reference(name, List.of()))
                            : unknown("Missing parameter: " + name);
                };
            } else {
                throw new IllegalArgumentException("Expected a value near offset " + position);
            }
            while (true) {
                if (accept(".")) {
                    if (!token.identifier()) {
                        throw new IllegalArgumentException("Expected a property name");
                    }
                    String property = token.text();
                    next();
                    if (accept("(")) {
                        if (!List.of("size", "length", "isEmpty").contains(property)) {
                            throw new IllegalArgumentException("Unsupported method: " + property);
                        }
                        expect(")");
                        result = size(result, property);
                    } else {
                        result = property(result, property);
                    }
                } else if (accept("[")) {
                    ActiveValue key = or();
                    expect("]");
                    result = key.isUnknown() ? key : property(result, key.value());
                } else {
                    return result;
                }
            }
        }

        private boolean is(String text) {
            return !token.literalToken() && token.text().equals(text);
        }

        private boolean accept(String text) {
            if (!is(text)) {
                return false;
            }
            next();
            return true;
        }

        private void expect(String text) {
            if (!accept(text)) {
                throw new IllegalArgumentException("Expected '" + text + "' near offset " + position);
            }
        }

        private void next() {
            context.check();
            if (++steps > context.replayBudgets().steps()) fail("Expression parser work budget exceeded");
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                context.check();
                position++;
            }
            if (position == source.length()) {
                token = new Token("", null, false, false);
                return;
            }
            int start = position;
            char c = source.charAt(position++);
            if (c == '\'' || c == '"') {
                StringBuilder text = new StringBuilder();
                while (position < source.length()) {
                    context.check();
                    char current = source.charAt(position++);
                    if (current == c) {
                        token = new Token(text.toString(), text.toString(), true, false);
                        return;
                    }
                    if (current == '\\') {
                        if (position == source.length()) {
                            break;
                        }
                        current = source.charAt(position++);
                        current = switch (current) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            default -> current;
                        };
                    }
                    text.append(current);
                }
                throw new IllegalArgumentException("Unclosed string at offset " + start);
            }
            if (Character.isJavaIdentifierStart(c)) {
                while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) {
                    context.check();
                    position++;
                }
                token = new Token(source.substring(start, position), null, false, true);
                return;
            }
            if (Character.isDigit(c)) {
                while (position < source.length() && Character.isDigit(source.charAt(position))) {
                    context.check();
                    position++;
                }
                if (position < source.length() && source.charAt(position) == '.') {
                    position++;
                    while (position < source.length() && Character.isDigit(source.charAt(position))) {
                        context.check();
                        position++;
                    }
                }
                token = new Token(source.substring(start, position),
                        new BigDecimal(source.substring(start, position)), true, false);
                return;
            }
            String operator = String.valueOf(c);
            if (position < source.length()) {
                String pair = operator + source.charAt(position);
                if (List.of("==", "!=", ">=", "<=", "&&", "||").contains(pair)) {
                    operator = pair;
                    position++;
                }
            }
            if (!List.of("==", "!=", ">", ">=", "<", "<=", "&&", "||", "!", "+", "-", ".", "(", ")", "[", "]").contains(operator)) {
                throw new IllegalArgumentException("Unsupported token at offset " + start + ": " + operator);
            }
            token = new Token(operator, null, false, false);
        }

        private void fail(String message) {
            context.limit(message);
            throw new EvaluationLimit(message);
        }
    }
}
