package org.u80dc.jsqlint.core.node;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 有界语法子集：字面量、属性/下标、比较、加减、布尔运算、size/length/isEmpty。
 * 不执行任意 OGNL 方法、静态方法或构造器；无法求值时显式返回 Unknown。
 * 对象属性读取会调用 getter/record 访问器，其无副作用约束由提供参数对象的调用方保证。
 *
 * @author u80dc
 */
final class SimpleSqlExpressionEvaluator implements SqlExpressionEvaluator {
    private static SqlValue value(Object value) {
        return value instanceof SqlValue sqlValue ? sqlValue : new SqlValue.Known(value);
    }

    private static SqlValue booleanOp(SqlValue a, SqlValue b, boolean and) {
        var x = a.truth();
        var y = b.truth();
        if (and && (x == SqlValue.Truth.FALSE || y == SqlValue.Truth.FALSE)) {
            return value(false);
        }
        if (!and && (x == SqlValue.Truth.TRUE || y == SqlValue.Truth.TRUE)) {
            return value(true);
        }
        if (a instanceof SqlValue.Unknown) {
            return a;
        }
        if (b instanceof SqlValue.Unknown) {
            return b;
        }
        return value(and);
    }

    private static SqlValue binary(SqlValue a, String op, SqlValue b) {
        if (a instanceof SqlValue.Unknown) {
            return a;
        }
        if (b instanceof SqlValue.Unknown) {
            return b;
        }
        Object x = ((SqlValue.Known) a).value();
        Object y = ((SqlValue.Known) b).value();
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
                default -> new SqlValue.Unknown("Unsupported operator: " + op);
            };
        }
        return new SqlValue.Unknown("Unsupported operands for " + op);
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }

    private static SqlValue property(SqlValue base, Object key) {
        if (base instanceof SqlValue.Unknown) {
            return base;
        }
        Object target = ((SqlValue.Known) base).value();
        if (target == null) {
            return new SqlValue.Unknown("Cannot read property " + key + " of null");
        }
        if (target instanceof Map<?, ?> map) {
            return map.containsKey(key) ? value(map.get(key)) : new SqlValue.Unknown("Missing property: " + key);
        }
        if (key instanceof Number index) {
            int i;
            try {
                i = decimal(index).intValueExact();
            } catch (ArithmeticException e) {
                return new SqlValue.Unknown("Invalid index: " + key);
            }
            if (target instanceof List<?> list && i >= 0 && i < list.size()) {
                return value(list.get(i));
            }
            if (target.getClass().isArray() && i >= 0 && i < Array.getLength(target)) {
                return value(Array.get(target, i));
            }
            return new SqlValue.Unknown("Index out of bounds or unsupported target: " + key);
        }
        String name = String.valueOf(key);
        if (name.equals("length") && target.getClass().isArray()) {
            return value(Array.getLength(target));
        }
        if (name.isEmpty()) {
            return new SqlValue.Unknown("Empty property");
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
                return value(method.invoke(target));
            } catch (NoSuchMethodException ignored) {
                // Try the next property accessor.
            } catch (ReflectiveOperationException | RuntimeException e) {
                return new SqlValue.Unknown("Cannot read property: " + name);
            }
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.canAccess(target) && !field.trySetAccessible()) {
                    break;
                }
                return value(field.get(target));
            } catch (NoSuchFieldException ignored) {
                // Also support inherited fields.
            } catch (ReflectiveOperationException | RuntimeException e) {
                return new SqlValue.Unknown("Cannot read property: " + name);
            }
        }
        return new SqlValue.Unknown("Missing property: " + name);
    }

    private static SqlValue size(SqlValue input, String operation) {
        if (input instanceof SqlValue.Unknown) {
            return input;
        }
        Object target = ((SqlValue.Known) input).value();
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
            return new SqlValue.Unknown("Cannot call " + operation + " on this value");
        }
        return value(operation.equals("isEmpty") ? size == 0 : size);
    }

    @Override
    public SqlValue evaluate(String expression, Map<String, ?> parameters) {
        try {
            Parser parser = new Parser(expression, parameters);
            SqlValue result = parser.or();
            parser.expect("");
            return result;
        } catch (RuntimeException e) {
            return new SqlValue.Unknown(e.getMessage() == null ? "Cannot evaluate expression" : e.getMessage());
        }
    }

    private record Token(String text, Object literal, boolean literalToken, boolean identifier) {
    }

    private static final class Parser {
        private final String source;
        private final Map<String, ?> parameters;
        private int position;
        private Token token;
        private int depth;

        private Parser(String source, Map<String, ?> parameters) {
            this.source = Objects.requireNonNull(source, "expression");
            this.parameters = Objects.requireNonNull(parameters, "parameters");
            if (source.length() > 16384) {
                throw new IllegalArgumentException("Expression exceeds 16384 characters");
            }
            next();
        }

        private SqlValue or() {
            SqlValue result = and();
            while (accept("or") || accept("||")) {
                result = booleanOp(result, and(), false);
            }
            return result;
        }

        private SqlValue and() {
            SqlValue result = comparison();
            while (accept("and") || accept("&&")) {
                result = booleanOp(result, comparison(), true);
            }
            return result;
        }

        private SqlValue comparison() {
            SqlValue result = sum();
            while (!token.literalToken() && List.of("==", "!=", ">", ">=", "<", "<=").contains(token.text())) {
                String operator = token.text();
                next();
                result = binary(result, operator, sum());
            }
            return result;
        }

        private SqlValue sum() {
            SqlValue result = unary();
            while (is("+") || is("-")) {
                String operator = token.text();
                next();
                result = binary(result, operator, unary());
            }
            return result;
        }

        private SqlValue unary() {
            if (++depth > 128) {
                throw new IllegalArgumentException("Expression nesting exceeds 128 levels");
            }
            try {
                if (accept("!") || accept("not")) {
                    SqlValue operand = unary();
                    return operand instanceof SqlValue.Unknown ? operand : value(operand.truth() != SqlValue.Truth.TRUE);
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

        private SqlValue primary() {
            SqlValue result;
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
                    default -> parameters.containsKey(name) ? value(parameters.get(name))
                            : new SqlValue.Unknown("Missing parameter: " + name);
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
                    SqlValue key = or();
                    expect("]");
                    result = key instanceof SqlValue.Unknown ? key : property(result, ((SqlValue.Known) key).value());
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
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
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
                    position++;
                }
                token = new Token(source.substring(start, position), null, false, true);
                return;
            }
            if (Character.isDigit(c)) {
                while (position < source.length() && Character.isDigit(source.charAt(position))) {
                    position++;
                }
                if (position < source.length() && source.charAt(position) == '.') {
                    position++;
                    while (position < source.length() && Character.isDigit(source.charAt(position))) {
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
    }
}
