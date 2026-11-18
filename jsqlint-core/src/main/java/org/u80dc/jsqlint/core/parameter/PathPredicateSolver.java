package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.semantics.JavaValueOperations;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import java.math.BigInteger;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.*;

/** 有界条件求解：精确收缩整数/布尔区间和 null 条件，复杂表达式仅在找到可验证模型时判为可满足。 @author u80dc */
final class PathPredicateSolver {
    private final int maxModels, maxDepth, maxLength;
    private enum Unknown { VALUE }
    private record Expression(String operator, String type, Object value, List<Expression> inputs) {}
    private record Predicate(Expression expression, boolean expected) {}
    private record Problem(Map<Variable, Domain> domains, List<Predicate> predicates, Set<Object> hints) {}
    record Solution(Status status, Map<Variable, SqlValue> inputs, List<String> issues) {}
    private final Runnable checkpoint;

    PathPredicateSolver(Runnable checkpoint) { this(checkpoint, 2048); }

    /** 模型搜索步数来自 AnalyzerConfig 的联合求解预算；独立使用时保持默认上限。 */
    PathPredicateSolver(Runnable checkpoint, int maxModels) {
        this(checkpoint, maxModels, 256);
    }

    PathPredicateSolver(Runnable checkpoint, int maxModels, int maxDepth) {
        this(checkpoint, maxModels, maxDepth, 1_000_000);
    }

    PathPredicateSolver(Runnable checkpoint, int maxModels, int maxDepth, int maxLength) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        if (maxModels < 1 || maxDepth < 1 || maxLength < 1) throw new IllegalArgumentException("Joint model budgets must be positive");
        this.maxModels = maxModels; this.maxDepth = maxDepth; this.maxLength = maxLength;
    }

    Solution solve(List<Condition> conditions) {
        Problem problem = prepare(conditions);
        if (problem == null) return unsat();
        List<Variable> variables = new ArrayList<>(problem.domains().keySet());
        // 防止独立条件参数很多时产生无界笛卡尔积。无模型不等于无解。
        if (variables.size() > 32) return unknown("Joint predicate variable limit exceeded");
        var candidates = variables.stream().map(variable -> problem.domains().get(variable).candidates(problem.hints())).toList();
        Map<Variable, Object> model = new LinkedHashMap<>();
        Map<Variable, Object> found = search(problem.predicates(), variables, candidates, 0, model, new int[]{0});
        if (found == null) return unknown("No verified model for unsupported predicates or within joint search budget");
        Map<Variable, SqlValue> inputs = new LinkedHashMap<>();
        found.forEach((variable, value) -> inputs.put(variable, new SqlValue.Known(value)));
        return new Solution(Status.SATISFIABLE, inputs, List.of());
    }

    /** 枚举中只做可证明的矛盾检查，不为每个前缀运行候选模型搜索；未知条件必须保留。 */
    boolean contradicts(List<Condition> conditions) { return prepare(conditions) == null; }

    private Problem prepare(List<Condition> conditions) {
        Map<Variable, Domain> domains = new LinkedHashMap<>();
        List<Predicate> predicates = new ArrayList<>();
        LinkedHashSet<Object> hints = new LinkedHashSet<>();
        Map<Expression, Boolean> expected = new HashMap<>();
        for (Condition condition : conditions) {
            Expression expression = expression(condition.expression().root(), 0);
            Predicate predicate = new Predicate(expression, condition.expected()); predicates.add(predicate);
            collect(expression, domains, hints);
            // 仅对已建模的纯表达式复用逻辑身份，未知外部调用不能假设两次结果相同。
            if (supported(expression)) {
                Boolean old = expected.putIfAbsent(expression, condition.expected());
                if (old != null && old != condition.expected()) return null;
            }
            Object constant = evaluate(expression, Map.of());
            if (constant instanceof Boolean value && value != condition.expected()) return null;
        }
        for (Predicate predicate : predicates) {
            if (!constrain(predicate, domains)) return null;
        }
        for (Domain domain : domains.values()) if (domain.empty()) return null;
        return new Problem(domains, predicates, hints);
    }

    private Map<Variable, Object> search(List<Predicate> predicates, List<Variable> variables, List<List<Object>> candidates,
                                         int index, Map<Variable, Object> model, int[] count) {
        checkpoint.run();
        if (++count[0] > maxModels) return null;
        for (Predicate predicate : predicates) {
            Object value = evaluate(predicate.expression(), model);
            if (value instanceof Boolean truth && truth != predicate.expected()) return null;
        }
        if (index == variables.size()) {
            return predicates.stream().allMatch(predicate -> Objects.equals(evaluate(predicate.expression(), model), predicate.expected()))
                    ? new LinkedHashMap<>(model) : null;
        }
        for (Object value : candidates.get(index)) {
            model.put(variables.get(index), value);
            var found = search(predicates, variables, candidates, index + 1, model, count);
            if (found != null) return found;
            if (count[0] > maxModels) break;
        }
        model.remove(variables.get(index));
        return null;
    }

    private boolean constrain(Predicate predicate, Map<Variable, Domain> domains) {
        Expression expression = predicate.expression();
        if (!Set.of("==", "!=", ">", ">=", "<", "<=").contains(expression.operator())) return true;
        Expression left = expression.inputs().getFirst(), right = expression.inputs().get(1);
        String operator = predicate.expected() ? expression.operator() : negate(expression.operator());
        if (left.operator().equals("constant") && right.operator().equals("symbol")) {
            Expression swap = left; left = right; right = swap; operator = reverse(operator);
        }
        if (!left.operator().equals("symbol") || !right.operator().equals("constant")) return true;
        Domain domain = domains.get((Variable) left.value());
        if (domain.integral) {
            BigInteger number = integral(right.value());
            if (number == null) return true;
            switch (operator) {
                case "==" -> { domain.min = domain.min.max(number); domain.max = domain.max.min(number); }
                case "!=" -> domain.excluded.add(number);
                case ">" -> domain.min = domain.min.max(number.add(BigInteger.ONE));
                case ">=" -> domain.min = domain.min.max(number);
                case "<" -> domain.max = domain.max.min(number.subtract(BigInteger.ONE));
                case "<=" -> domain.max = domain.max.min(number);
            }
        } else if (right.value() == null && Set.of("==", "!=").contains(operator)) {
            domain.mustNull |= operator.equals("=="); domain.nonNull |= operator.equals("!=");
        }
        return !domain.empty();
    }

    private void collect(Expression expression, Map<Variable, Domain> domains, Set<Object> hints) {
        if (expression.operator().equals("symbol")) domains.computeIfAbsent((Variable) expression.value(), Domain::new);
        if (expression.operator().equals("constant")) hints.add(expression.value());
        expression.inputs().forEach(input -> collect(input, domains, hints));
    }

    private Expression expression(Node node, int depth) {
        checkpoint.run();
        if (depth > maxDepth) return unknownExpression(node);
        if (node.kind() == Kind.ENTRY || node.kind() == Kind.NO_CALLER) {
            if (!node.inputs().isEmpty()) return unknownExpression(node);
            return new Expression("symbol", node.variable().type(), node.variable(), List.of());
        }
        List<Expression> inputs = node.inputs().stream().map(input -> expression(input, depth + 1)).toList();
        try {
            return switch (node.computation().operation()) {
                case LITERAL -> new Expression("constant", node.variable().type(),
                        JavaValueOperations.evaluate(node.computation(), node.variable().type(), List.of(), maxLength), List.of());
                case NULL -> new Expression("constant", node.variable().type(), null, List.of());
                case IDENTITY, ALTERNATIVES -> inputs.size() != 1 ? unknownExpression(node)
                        : node.variable().type().equals(inputs.getFirst().type()) ? inputs.getFirst()
                        : new Expression(node.computation().operation().name(), node.variable().type(), node.computation(), inputs);
                case BINARY -> binary(node, inputs);
                case CALL -> JavaValueOperations.supports(node.computation().method().orElseThrow())
                        ? new Expression("CALL", node.variable().type(), node.computation(), inputs) : unknownExpression(node);
                case REQUIRE_NON_NULL, STRING_COPY, STRING_CAST, CAST, STRINGIFY, CONCAT, STRING_CONCAT, REPLACE, NEGATE -> new Expression(node.computation().operation().name(),
                        node.variable().type(), node.computation(), inputs);
                default -> unknownExpression(node);
            };
        } catch (RuntimeException e) { return unknownExpression(node); }
    }

    private Expression unknownExpression(Node node) { return new Expression("unknown", node.variable().type(), node.variable(), List.of()); }
    private Expression binary(Node node, List<Expression> inputs) {
        String operator = node.computation().text();
        // javac 的 long 比较先生成 lcmp，再与 0 比较；只展开整数 cmp，浮点 cmpl/cmpg 的 NaN 语义不同。
        if (Set.of("==", "!=", ">", ">=", "<", "<=").contains(operator) && inputs.size() == 2
                && inputs.getFirst().operator().equals("cmp") && inputs.get(1).operator().equals("constant")
                && BigInteger.ZERO.equals(integral(inputs.get(1).value())))
            return new Expression(operator, node.variable().type(), node.computation(), inputs.getFirst().inputs());
        return new Expression(operator, node.variable().type(), node.computation(), inputs);
    }
    private boolean supported(Expression expression) {
        return !expression.operator().equals("unknown") && expression.inputs().stream().allMatch(this::supported);
    }

    private Object evaluate(Expression expression, Map<Variable, Object> model) {
        if (expression.operator().equals("constant")) return expression.value();
        if (expression.operator().equals("symbol")) return model.containsKey(expression.value()) ? model.get(expression.value()) : Unknown.VALUE;
        if (expression.operator().equals("unknown")) return Unknown.VALUE;
        List<Object> inputs = expression.inputs().stream().map(input -> evaluate(input, model)).toList();
        if (inputs.contains(Unknown.VALUE)) return Unknown.VALUE;
        try {
            String op = expression.operator();
            if (Set.of("==", "!=", ">", ">=", "<", "<=").contains(op)) {
                Object left = inputs.getFirst(), right = inputs.get(1);
                if (left == null || right == null) return switch (op) {
                    case "==" -> left == right; case "!=" -> left != right; default -> Unknown.VALUE;
                };
                BigInteger a = integral(left), b = integral(right);
                if (a != null && b != null) return compare(op, a.compareTo(b));
                // 不把浮点 NaN 或引用身份比较错误地转换为字符串/十进制值比较。
                return Unknown.VALUE;
            }
            // 比较仍由条件模型处理；其余已知运算与独立重演共享相同类型和长度语义。
            if (op.equals("cmp")) return integral(inputs.getFirst()).compareTo(integral(inputs.get(1)));
            return JavaValueOperations.evaluate((Computation) expression.value(), expression.type(), inputs, maxLength);
        } catch (RuntimeException e) { return Unknown.VALUE; }
    }

    private static boolean compare(String op, int compared) {
        return switch (op) { case "==" -> compared == 0; case "!=" -> compared != 0; case ">" -> compared > 0;
            case ">=" -> compared >= 0; case "<" -> compared < 0; case "<=" -> compared <= 0; default -> false; };
    }
    private static String negate(String op) {
        return switch (op) { case "==" -> "!="; case "!=" -> "=="; case ">" -> "<="; case ">=" -> "<"; case "<" -> ">="; case "<=" -> ">"; default -> op; };
    }
    private static String reverse(String op) {
        return switch (op) { case ">" -> "<"; case ">=" -> "<="; case "<" -> ">"; case "<=" -> ">="; default -> op; };
    }
    private static BigInteger integral(Object value) {
        if (value instanceof Boolean bool) return bool ? BigInteger.ONE : BigInteger.ZERO;
        if (value instanceof Character character) return BigInteger.valueOf(character);
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                ? BigInteger.valueOf(((Number) value).longValue()) : null;
    }
    private static Solution unsat() { return new Solution(Status.UNSATISFIABLE, Map.of(), List.of()); }
    private static Solution unknown(String issue) { return new Solution(Status.UNKNOWN, Map.of(), List.of(issue)); }

    private static final class Domain {
        final Variable variable;
        final boolean integral;
        BigInteger min, max;
        final Set<BigInteger> excluded = new HashSet<>();
        boolean mustNull, nonNull;
        Domain(Variable variable) {
            this.variable = variable;
            integral = Set.of("boolean", "byte", "short", "char", "int", "long").contains(variable.type());
            long low = switch (variable.type()) { case "boolean", "char" -> 0; case "byte" -> Byte.MIN_VALUE;
                case "short" -> Short.MIN_VALUE; case "int" -> Integer.MIN_VALUE; default -> Long.MIN_VALUE; };
            long high = switch (variable.type()) { case "boolean" -> 1; case "char" -> Character.MAX_VALUE;
                case "byte" -> Byte.MAX_VALUE; case "short" -> Short.MAX_VALUE; case "int" -> Integer.MAX_VALUE; default -> Long.MAX_VALUE; };
            min = BigInteger.valueOf(low); max = BigInteger.valueOf(high);
        }
        boolean empty() {
            if (!integral) return mustNull && nonNull;
            if (min.compareTo(max) > 0) return true;
            BigInteger value = min;
            while (excluded.contains(value)) value = value.add(BigInteger.ONE);
            return value.compareTo(max) > 0;
        }
        List<Object> candidates(Set<Object> hints) {
            LinkedHashSet<Object> values = new LinkedHashSet<>();
            if (integral) {
                var numbers = new LinkedHashSet<BigInteger>(); numbers.add(BigInteger.ZERO); numbers.add(BigInteger.ONE);
                for (Object hint : hints) {
                    BigInteger value = integral(hint);
                    if (value != null) { numbers.add(value); numbers.add(value.subtract(BigInteger.ONE)); numbers.add(value.add(BigInteger.ONE)); }
                }
                BigInteger first = min;
                while (excluded.contains(first)) first = first.add(BigInteger.ONE);
                numbers.add(first); numbers.add(max);
                for (BigInteger value : numbers) if (value.compareTo(min) >= 0 && value.compareTo(max) <= 0 && !excluded.contains(value))
                    values.add(JavaValueOperations.cast(value.longValue(), variable.type()));
            } else if (Set.of("float", "double").contains(variable.type())) {
                // 浮点条件暂不证明无解；即使搜索失败，也不能为基本类型构造 null 模型。
                values.add(JavaValueOperations.cast(0, variable.type()));
                values.add(JavaValueOperations.cast(1, variable.type()));
            } else {
                if (!nonNull) values.add(null);
                if (!mustNull && variable.type().equals("java.lang.String")) {
                    values.add(""); values.add("sample");
                    hints.stream().filter(String.class::isInstance).forEach(values::add);
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
