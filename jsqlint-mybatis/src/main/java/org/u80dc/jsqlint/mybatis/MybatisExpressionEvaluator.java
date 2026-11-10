package org.u80dc.jsqlint.mybatis;

import org.apache.ibatis.ognl.*;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.u80dc.jsqlint.core.node.*;

import java.lang.reflect.Array;
import java.util.*;

/** 使用 MyBatis 自带 OGNL AST 的受限解释器，只读取值快照，不调用目标对象的方法。 @author u80dc */
final class MybatisExpressionEvaluator implements SqlExpressionEvaluator {
    private static final Set<String> BINARY = Set.of("ASTEq", "ASTNotEq", "ASTGreater", "ASTGreaterEq", "ASTLess", "ASTLessEq",
            "ASTAdd", "ASTSubtract", "ASTMultiply", "ASTDivide", "ASTRemainder");
    private static final Set<String> STRUCTURAL = Set.of("ASTConst", "ASTProperty", "ASTChain", "ASTMethod", "ASTAnd", "ASTOr", "ASTTest", "ASTNot", "ASTNegate", "ASTList");
    private final Optional<String> databaseId;
    MybatisExpressionEvaluator(MybatisOptions options) { databaseId = options.databaseId(); }

    void validate(SqlNode node) {
        switch (node) {
            case ValueSqlNode value -> { if (value.getKind() == ValueSqlNode.Kind.RAW_TEXT) validateExpression(value.getExpression()); }
            case BindSqlNode bind -> validateExpression(bind.expression());
            case IfSqlNode conditional -> { validateExpression(conditional.test()); validate(conditional.contents()); }
            case ChooseSqlNode choose -> {
                choose.whenContents().forEach(this::validate);
                if (choose.otherwiseContents() != null) validate(choose.otherwiseContents());
            }
            case ForEachSqlNode loop -> { validateExpression(loop.collection()); validate(loop.contents()); }
            case MixedSqlNode mixed -> mixed.contents().forEach(this::validate);
            case WhereSqlNode where -> validate(where.contents());
            case SetSqlNode set -> validate(set.contents());
            case TrimSqlNode trim -> validate(trim.contents());
            default -> { }
        }
    }
    private static void validateExpression(String expression) {
        if (expression.length() > 4096) throw new IllegalArgumentException("MyBatis expression length exceeds 4096");
        try { validateAst((Node) Ognl.parseExpression(expression), 0, new int[]{0}); }
        catch (OgnlException e) { throw new IllegalArgumentException("Invalid MyBatis expression: " + expression, e); }
    }
    private static void validateAst(Node node, int depth, int[] steps) {
        if (depth > 128 || ++steps[0] > 2048) throw new IllegalArgumentException("MyBatis expression budget exceeded");
        String kind = node.getClass().getSimpleName();
        if (!BINARY.contains(kind) && !STRUCTURAL.contains(kind)) throw new IllegalArgumentException("Unsupported MyBatis expression node: " + kind);
        if (node instanceof ASTMethod method && (method.jjtGetNumChildren() != 0 || !Set.of("size", "length", "isEmpty").contains(method.getMethodName())))
            throw new IllegalArgumentException("Unsupported MyBatis method: " + method.getMethodName());
        for (int i = 0; i < node.jjtGetNumChildren(); i++) validateAst(node.jjtGetChild(i), depth + 1, steps);
    }

    @Override public SqlValue evaluate(String expression, Map<String, ?> parameters) {
        if (expression.length() > 4096) return unknown("MyBatis expression length exceeds 4096");
        try {
            var node = (Node) Ognl.parseExpression(expression);
            var state = new Evaluation(parameters);
            return safe(state.visit(node, known(parameters), 0));
        } catch (OgnlException | RuntimeException e) {
            return unknown("Cannot evaluate MyBatis expression: " + e.getMessage());
        }
    }

    /** #{...} 是 MyBatis 属性路径；数字名称不能误当作 OGNL 数值字面量。 */
    @Override public SqlValue evaluateParameter(String expression, Map<String, ?> parameters) {
        String path = expression.strip();
        if (path.length() > 4096) return unknown("MyBatis parameter path length exceeds 4096");
        if (!path.contains(".") && !path.contains("[") && parameters.containsKey(path)) return safe(known(parameters.get(path)));
        if (parameters.containsKey("_parameter")) {
            var root = known(parameters.get("_parameter"));
            if (root instanceof SqlValue.Known value && scalar(value.value())) return root;
        }
        SqlValue target = known(parameters);
        try {
            var token = new PropertyTokenizer(path);
            int depth = 0;
            while (true) {
                if (++depth > 128) return unknown("MyBatis parameter path depth exceeds 128");
                target = property(target, token.getName(), true);
                if (target instanceof SqlValue.Unknown) return target;
                if (token.getIndex() != null) target = indexed(target, token.getIndex());
                if (target instanceof SqlValue.Unknown || !token.hasNext()) return safe(target);
                token = token.next();
            }
        } catch (RuntimeException e) { return unknown("Cannot read MyBatis parameter path: " + e.getMessage()); }
    }

    private static SqlValue indexed(SqlValue base, String index) {
        if (base instanceof SqlValue.Unknown) return base;
        if (((SqlValue.Known) base).value() instanceof Map<?, ?>) return property(base, index, true);
        try { return property(base, Integer.parseInt(index), true); }
        catch (NumberFormatException e) { return unknown("Invalid MyBatis parameter index: " + index); }
    }
    private static SqlValue safe(SqlValue value) {
        if (value instanceof SqlValue.Unknown) return value;
        Object raw = ((SqlValue.Known) value).value();
        return scalar(raw) || raw instanceof Map<?, ?> || raw instanceof List<?> || raw instanceof Set<?>
                || raw != null && raw.getClass().isArray() ? value : unknown("Unsupported MyBatis parameter object: " + raw.getClass().getName());
    }

    private final class Evaluation {
        private final Map<String, ?> parameters;
        private int steps;
        Evaluation(Map<String, ?> parameters) { this.parameters = parameters; }
        SqlValue visit(Node node, SqlValue target, int depth) {
            if (depth > 128 || ++steps > 2048) return unknown("MyBatis expression budget exceeded");
            String kind = node.getClass().getSimpleName();
            if (node instanceof ASTConst constant) return known(constant.getValue());
            if (node instanceof ASTProperty property) {
                var key = visit(property.jjtGetChild(0), known(parameters), depth + 1);
                if (key instanceof SqlValue.Unknown) return key;
                Object name = ((SqlValue.Known) key).value();
                if (target instanceof SqlValue.Known value && value.value() == parameters && name instanceof String reserved
                        && !parameters.containsKey(reserved)) {
                    if (reserved.equals("_databaseId")) return known(databaseId.orElse(null));
                    if (reserved.equals("_parameter")) return known(parameters);
                }
                return property(target, name, property.isIndexedAccess());
            }
            if (kind.equals("ASTChain")) {
                SqlValue value = target;
                for (int i = 0; i < node.jjtGetNumChildren(); i++) {
                    value = visit(node.jjtGetChild(i), value, depth + 1);
                    if (value instanceof SqlValue.Unknown) return value;
                }
                return value;
            }
            if (node instanceof ASTMethod method) {
                if (method.jjtGetNumChildren() != 0) return unknown("Unsupported MyBatis method: " + method.getMethodName());
                return size(target, method.getMethodName());
            }
            if (kind.equals("ASTAnd") || kind.equals("ASTOr")) {
                boolean and = kind.equals("ASTAnd");
                SqlValue value = known(and);
                for (int i = 0; i < node.jjtGetNumChildren(); i++) {
                    value = visit(node.jjtGetChild(i), target, depth + 1);
                    if (value instanceof SqlValue.Unknown) return value;
                    if (OgnlOps.booleanValue(((SqlValue.Known) value).value()) != and) return value;
                }
                return value;
            }
            if (kind.equals("ASTTest")) {
                var condition = visit(node.jjtGetChild(0), target, depth + 1);
                if (condition instanceof SqlValue.Unknown) return condition;
                return visit(node.jjtGetChild(OgnlOps.booleanValue(((SqlValue.Known) condition).value()) ? 1 : 2), target, depth + 1);
            }
            if (kind.equals("ASTNot") || kind.equals("ASTNegate")) {
                var value = visit(node.jjtGetChild(0), target, depth + 1);
                if (value instanceof SqlValue.Unknown) return value;
                Object raw = ((SqlValue.Known) value).value();
                return known(kind.equals("ASTNot") ? !OgnlOps.booleanValue(raw) : OgnlOps.negate(raw));
            }
            if (kind.equals("ASTList")) {
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < node.jjtGetNumChildren(); i++) {
                    var value = visit(node.jjtGetChild(i), target, depth + 1);
                    if (value instanceof SqlValue.Unknown) return value;
                    values.add(((SqlValue.Known) value).value());
                }
                return known(Collections.unmodifiableList(values));
            }
            if (!BINARY.contains(kind))
                return unknown("Unsupported MyBatis expression node: " + kind);
            SqlValue result = visit(node.jjtGetChild(0), target, depth + 1);
            for (int i = 1; i < node.jjtGetNumChildren(); i++) {
                if (result instanceof SqlValue.Unknown) return result;
                var right = visit(node.jjtGetChild(i), target, depth + 1);
                if (right instanceof SqlValue.Unknown) return right;
                Object leftValue = ((SqlValue.Known) result).value(), rightValue = ((SqlValue.Known) right).value();
                if ((kind.equals("ASTEq") || kind.equals("ASTNotEq")) && (leftValue == null || rightValue == null)) {
                    result = known(kind.equals("ASTEq") == (leftValue == rightValue)); continue;
                }
                if (!scalar(leftValue) || !scalar(rightValue)) return unknown("Non-scalar MyBatis expression operand");
                result = known(switch (kind) {
                    case "ASTEq" -> OgnlOps.equal(leftValue, rightValue);
                    case "ASTNotEq" -> !OgnlOps.equal(leftValue, rightValue);
                    case "ASTGreater" -> OgnlOps.greater(leftValue, rightValue);
                    case "ASTGreaterEq" -> !OgnlOps.less(leftValue, rightValue);
                    case "ASTLess" -> OgnlOps.less(leftValue, rightValue);
                    case "ASTLessEq" -> !OgnlOps.greater(leftValue, rightValue);
                    case "ASTAdd" -> OgnlOps.add(leftValue, rightValue);
                    case "ASTSubtract" -> OgnlOps.subtract(leftValue, rightValue);
                    case "ASTMultiply" -> OgnlOps.multiply(leftValue, rightValue);
                    case "ASTDivide" -> OgnlOps.divide(leftValue, rightValue);
                    case "ASTRemainder" -> OgnlOps.remainder(leftValue, rightValue);
                    default -> throw new IllegalStateException(kind);
                });
            }
            return result;
        }
    }

    private static SqlValue property(SqlValue base, Object key, boolean indexed) {
        if (base instanceof SqlValue.Unknown) return base;
        Object value = ((SqlValue.Known) base).value();
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey(key)) return known(map.get(key));
            if (!indexed && key.equals("size")) return known(map.size());
            if (!indexed && key.equals("isEmpty")) return known(map.isEmpty());
            return unknown("Missing MyBatis parameter: " + key);
        }
        if (indexed && key instanceof Number number) {
            int index = number.intValue();
            if (number.doubleValue() != index || index < 0) return unknown("Invalid MyBatis collection index: " + key);
            if (value instanceof List<?> list && index < list.size()) return known(list.get(index));
            if (value != null && value.getClass().isArray() && index < Array.getLength(value)) return known(Array.get(value, index));
            return unknown("MyBatis collection index is unavailable: " + key);
        }
        if (key.equals("length") && value != null && value.getClass().isArray()) return known(Array.getLength(value));
        if (key.equals("size") || key.equals("isEmpty")) return size(base, key.toString());
        return unknown("MyBatis property requires a saved map or collection: " + key);
    }
    private static SqlValue size(SqlValue base, String method) {
        if (!Set.of("size", "length", "isEmpty").contains(method)) return unknown("Unsupported MyBatis method: " + method);
        if (base instanceof SqlValue.Unknown) return base;
        Object value = ((SqlValue.Known) base).value();
        int size;
        if (value instanceof Collection<?> collection) size = collection.size();
        else if (value instanceof Map<?, ?> map) size = map.size();
        else if (value instanceof String string) size = string.length();
        else if (value != null && value.getClass().isArray()) size = Array.getLength(value);
        else return unknown("MyBatis " + method + " requires a saved collection or string");
        return known(method.equals("isEmpty") ? size == 0 : size);
    }
    private static boolean scalar(Object value) {
        return value == null || value instanceof String || value instanceof Character || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger;
    }
    private static SqlValue known(Object value) { return value instanceof SqlValue sqlValue ? sqlValue : new SqlValue.Known(value); }
    private static SqlValue unknown(String issue) { return new SqlValue.Unknown(issue); }
}
