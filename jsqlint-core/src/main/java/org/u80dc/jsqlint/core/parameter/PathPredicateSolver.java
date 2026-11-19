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
    private final int maxModels, maxDepth, maxLength, maxPredicateSteps;
    private enum Unknown { VALUE }
    private enum EvaluationState { COMPUTE }
    /** 表达式对象按身份缓存；等价性单独通过浅层结构编号判断，不递归 hash 子图。 */
    private static final class Expression {
        final int id;
        final String operator, type;
        final Object value;
        final List<Expression> inputs;
        final boolean supported;
        final Variable referenceSymbol;
        Expression(int id, String operator, String type, Object value, List<Expression> inputs, boolean supported, Variable referenceSymbol) {
            this.id = id; this.operator = operator; this.type = type; this.value = value;
            this.inputs = List.copyOf(inputs); this.supported = supported; this.referenceSymbol = referenceSymbol;
        }
    }
    private record OperationKey(Operation operation, String text, Optional<sootup.core.signatures.MethodSignature> method) {}
    private record ExpressionShape(String operator, String type, Object value, Variable referenceSource, List<Integer> inputs) {}
    private record Predicate(Expression expression, boolean expected) {}
    private record Problem(Map<Variable, Domain> domains, List<Predicate> predicates, Set<Object> hints, WorkBudget budget) {}
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
        this(checkpoint, maxModels, maxDepth, maxLength, 20_000);
    }

    /** 前处理与每个候选模型分别限制节点和输入边的工作量，旧构造器保留默认容量。 */
    PathPredicateSolver(Runnable checkpoint, int maxModels, int maxDepth, int maxLength, int maxPredicateSteps) {
        this.checkpoint = Objects.requireNonNull(checkpoint);
        if (maxModels < 1 || maxDepth < 1 || maxLength < 1 || maxPredicateSteps < 1)
            throw new IllegalArgumentException("Joint model budgets must be positive");
        this.maxModels = maxModels; this.maxDepth = maxDepth; this.maxLength = maxLength;
        this.maxPredicateSteps = maxPredicateSteps;
    }

    Solution solve(List<Condition> conditions) {
        try {
            Problem problem = prepare(conditions);
            if (problem == null) return unsat();
            List<Variable> variables = new ArrayList<>(problem.domains().keySet());
            // 防止独立条件参数很多时产生无界笛卡尔积。无模型不等于无解。
            if (variables.size() > 32) return unknown("Joint predicate variable limit exceeded");
            var candidates = variables.stream().map(variable ->
                    problem.domains().get(variable).candidates(problem.hints(), problem.budget())).toList();
            Map<Variable, Object> model = new LinkedHashMap<>();
            Map<Variable, Object> found = search(problem.predicates(), variables, candidates, 0, model, new int[]{0});
            if (found == null) return unknown("No verified model for unsupported predicates or within joint search budget");
            Map<Variable, SqlValue> inputs = new LinkedHashMap<>();
            found.forEach((variable, value) -> inputs.put(variable, new SqlValue.Known(value)));
            return new Solution(Status.SATISFIABLE, inputs, List.of());
        } catch (PredicateLimitException limit) { return unknown(limit.getMessage()); }
    }

    /** 枚举中只做可证明的矛盾检查，不为每个前缀运行候选模型搜索；未知条件必须保留。 */
    boolean contradicts(List<Condition> conditions) {
        try { return prepare(conditions) == null; }
        catch (PredicateLimitException limit) { return false; }
    }

    private final class WorkBudget {
        private final String issue;
        private int steps;
        WorkBudget(String issue) { this.issue = issue; }
        void step() {
            // 取消异常在语义求值的 catch 之外传播，只有本地预算截断转为 UNKNOWN。
            checkpoint.run();
            if (++steps > maxPredicateSteps) throw new PredicateLimitException(issue);
        }
    }
    private static final class PredicateLimitException extends RuntimeException {
        PredicateLimitException(String message) { super(message); }
    }

    private Problem prepare(List<Condition> conditions) {
        Map<Variable, Domain> domains = new LinkedHashMap<>();
        List<Predicate> predicates = new ArrayList<>();
        LinkedHashSet<Object> hints = new LinkedHashSet<>();
        WorkBudget budget = new WorkBudget("Joint predicate preparation limit exceeded");
        ExpressionBuilder builder = new ExpressionBuilder(budget);
        Evaluator constants = new Evaluator(Map.of(), budget);
        Set<Expression> collected = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Integer, Boolean> expected = new HashMap<>();
        for (Condition condition : conditions) {
            budget.step();
            Expression expression = builder.expression(condition.expression().root());
            Predicate predicate = new Predicate(expression, condition.expected()); predicates.add(predicate);
            collect(expression, domains, hints, collected, budget);
            // 仅对已建模的纯表达式复用逻辑身份，未知外部调用不能假设两次结果相同。
            if (expression.supported) {
                Boolean old = expected.putIfAbsent(expression.id, condition.expected());
                if (old != null && old != condition.expected()) return null;
            }
            Object constant = constants.evaluate(expression);
            if (constant instanceof Boolean value && value != condition.expected()) return null;
        }
        for (Predicate predicate : predicates) {
            budget.step();
            if (!constrain(predicate, domains, budget)) return null;
        }
        for (Domain domain : domains.values()) { budget.step(); if (domain.empty(budget)) return null; }
        return new Problem(domains, predicates, hints, budget);
    }

    private Map<Variable, Object> search(List<Predicate> predicates, List<Variable> variables, List<List<Object>> candidates,
                                         int index, Map<Variable, Object> model, int[] count) {
        checkpoint.run();
        if (++count[0] > maxModels) return null;
        Evaluator evaluator = new Evaluator(model, new WorkBudget("Joint predicate evaluation limit exceeded"));
        for (Predicate predicate : predicates) {
            Object value = evaluator.evaluate(predicate.expression());
            if (value instanceof Boolean truth && truth != predicate.expected()) return null;
        }
        if (index == variables.size()) {
            return predicates.stream().allMatch(predicate -> Objects.equals(evaluator.evaluate(predicate.expression()), predicate.expected()))
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

    private boolean constrain(Predicate predicate, Map<Variable, Domain> domains, WorkBudget budget) {
        Expression expression = predicate.expression();
        if (!comparison(expression.operator) || expression.inputs.size() != 2) return true;
        Expression left = expression.inputs.getFirst(), right = expression.inputs.get(1);
        String operator = predicate.expected() ? expression.operator : negate(expression.operator);
        if (left.operator.equals("constant") && right.operator.equals("symbol")) {
            Expression swap = left; left = right; right = swap; operator = reverse(operator);
        }
        if (!left.operator.equals("symbol") || !right.operator.equals("constant")) return true;
        Domain domain = domains.get((Variable) left.value);
        if (domain.integral && integralType(right.type)) {
            BigInteger number = integral(right.value);
            if (number == null) return true;
            switch (operator) {
                case "==" -> { domain.min = domain.min.max(number); domain.max = domain.max.min(number); }
                case "!=" -> domain.excluded.add(number);
                case ">" -> domain.min = domain.min.max(number.add(BigInteger.ONE));
                case ">=" -> domain.min = domain.min.max(number);
                case "<" -> domain.max = domain.max.min(number.subtract(BigInteger.ONE));
                case "<=" -> domain.max = domain.max.min(number);
            }
        } else if (!primitiveType(left.type) && !primitiveType(right.type)
                && right.value == null && Set.of("==", "!=").contains(operator)) {
            domain.mustNull |= operator.equals("=="); domain.nonNull |= operator.equals("!=");
        }
        return !domain.empty(budget);
    }

    private void collect(Expression root, Map<Variable, Domain> domains, Set<Object> hints,
                         Set<Expression> seen, WorkBudget budget) {
        Deque<Expression> pending = new ArrayDeque<>(); pending.add(root);
        while (!pending.isEmpty()) {
            Expression expression = pending.removeLast();
            if (!seen.add(expression)) continue;
            budget.step();
            if (expression.operator.equals("symbol")) domains.computeIfAbsent((Variable) expression.value, Domain::new);
            if (expression.operator.equals("constant")) hints.add(expression.value);
            for (Expression input : expression.inputs) { budget.step(); pending.add(input); }
        }
    }

    private static final class BuildFrame {
        final Node node;
        int input;
        BuildFrame(Node node) { this.node = node; }
    }

    private final class ExpressionBuilder {
        private final WorkBudget budget;
        private final Map<Node, Expression> expressions = new IdentityHashMap<>();
        private final Map<Node, Integer> heights = new IdentityHashMap<>();
        private final Map<ExpressionShape, Integer> structures = new HashMap<>();
        ExpressionBuilder(WorkBudget budget) { this.budget = budget; }

        Expression expression(Node root) {
            Expression cached = expressions.get(root);
            if (cached != null) return cached;
            Set<Node> active = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<BuildFrame> pending = new ArrayDeque<>();
            pending.add(new BuildFrame(root)); active.add(root); budget.step();
            while (!pending.isEmpty()) {
                BuildFrame frame = pending.getLast();
                if (frame.input < frame.node.inputs().size()) {
                    Node child = frame.node.inputs().get(frame.input++); budget.step();
                    if (!expressions.containsKey(child)) {
                        if (!active.add(child)) throw new PredicateLimitException("Joint predicate dependency cycle is unresolved");
                        budget.step(); pending.add(new BuildFrame(child));
                    }
                    continue;
                }
                List<Expression> inputs = new ArrayList<>();
                int height = 0;
                for (Node child : frame.node.inputs()) {
                    height = Math.max(height, heights.get(child) + 1); inputs.add(expressions.get(child));
                }
                if (height > maxDepth) throw new PredicateLimitException("Joint predicate dependency depth limit exceeded");
                budget.step();
                expressions.put(frame.node, build(frame.node, inputs)); heights.put(frame.node, height);
                active.remove(frame.node); pending.removeLast();
            }
            return expressions.get(root);
        }

        private Expression build(Node node, List<Expression> inputs) {
            if (node.kind() == Kind.ENTRY || node.kind() == Kind.NO_CALLER)
                return node.inputs().isEmpty() ? make(node, "symbol", node.variable(), List.of()) : unknownExpression(node);
            try {
                return switch (node.computation().operation()) {
                    case LITERAL -> make(node, "constant",
                            JavaValueOperations.evaluate(node.computation(), node.variable().type(), List.of(), maxLength), List.of());
                    case NULL -> make(node, "constant", null, List.of());
                    case IDENTITY, ALTERNATIVES -> inputs.size() != 1 ? unknownExpression(node)
                            : node.variable().type().equals(inputs.getFirst().type) ? inputs.getFirst()
                            : make(node, node.computation().operation().name(), node.computation(), inputs);
                    case BINARY -> binary(node, inputs);
                    case CALL -> JavaValueOperations.supports(node.computation().method().orElseThrow())
                            ? make(node, "CALL", node.computation(), inputs) : unknownExpression(node);
                    case REQUIRE_NON_NULL, STRING_COPY, STRING_CAST, CAST, STRINGIFY, CONCAT, STRING_CONCAT, REPLACE, NEGATE, ARRAY_ACCESS_CHECK, NORMAL_COMPLETION ->
                            make(node, node.computation().operation().name(), node.computation(), inputs);
                    default -> unknownExpression(node);
                };
            } catch (RuntimeException e) { return unknownExpression(node); }
        }

        private Expression unknownExpression(Node node) { return make(node, "unknown", node.variable(), List.of()); }

        private Expression binary(Node node, List<Expression> inputs) {
            String operator = node.computation().text();
            // javac 的 long 比较先生成 lcmp，再与 0 比较；只展开整数 cmp。
            if (comparison(operator) && inputs.size() == 2 && inputs.getFirst().operator.equals("cmp")
                    && inputs.get(1).operator.equals("constant") && BigInteger.ZERO.equals(integral(inputs.get(1).value)))
                return make(node, operator, node.computation(), inputs.getFirst().inputs);
            return make(node, operator, node.computation(), inputs);
        }

        private Expression make(Node node, String operator, Object value, List<Expression> inputs) {
            Object semanticValue = value instanceof Computation operation
                    ? new OperationKey(operation.operation(), operation.text(), operation.method()) : value;
            // 不同调用/构造位置即使数值相同，也不表示返回了同一个引用。
            Variable source = !primitiveType(node.variable().type()) && value instanceof Computation ? node.variable() : null;
            var shape = new ExpressionShape(operator, node.variable().type(), semanticValue, source,
                    inputs.stream().map(input -> input.id).toList());
            int id = structures.computeIfAbsent(shape, ignored -> structures.size());
            Variable reference = null;
            if (!primitiveType(node.variable().type())) {
                if (operator.equals("symbol")) reference = (Variable) value;
                else if (inputs.size() == 1 && Set.of("IDENTITY", "ALTERNATIVES", "CAST", "STRING_CAST", "REQUIRE_NON_NULL")
                        .contains(operator)) reference = inputs.getFirst().referenceSymbol;
            }
            boolean supported = !operator.equals("unknown") && inputs.stream().allMatch(input -> input.supported);
            // Map 查询可读取两次调用之间改变的内容；受支持的执行模型不等于可复用的纯表达式。
            if (Set.of("CALL", "NORMAL_COMPLETION").contains(operator) && value instanceof Computation operation
                    && operation.method().orElseThrow().getDeclClassType().toString().equals("java.util.Map")) supported = false;
            if (comparison(operator) && inputs.size() == 2
                    && !primitiveType(inputs.getFirst().type) && !primitiveType(inputs.get(1).type)) {
                Expression left = inputs.getFirst(), right = inputs.get(1);
                boolean nullComparison = left.operator.equals("constant") && left.value == null
                        || right.operator.equals("constant") && right.value == null;
                boolean sameSymbol = left.referenceSymbol != null && left.referenceSymbol.equals(right.referenceSymbol);
                supported &= Set.of("==", "!=").contains(operator) && (nullComparison || sameSymbol);
            }
            return new Expression(id, operator, node.variable().type(), value, inputs, supported, reference);
        }
    }

    private static final class EvaluationFrame {
        final Expression expression;
        int input;
        EvaluationFrame(Expression expression) { this.expression = expression; }
    }

    /** 每个模型共享一份身份缓存，重复操作数只计算一次；输入边仍消耗预算。 */
    private final class Evaluator {
        private final Map<Variable, Object> model;
        private final WorkBudget budget;
        private final Map<Expression, Object> values = new IdentityHashMap<>();
        Evaluator(Map<Variable, Object> model, WorkBudget budget) { this.model = model; this.budget = budget; }

        Object evaluate(Expression root) {
            if (values.containsKey(root)) return values.get(root);
            Deque<EvaluationFrame> pending = new ArrayDeque<>();
            if (simple(root)) return values.get(root);
            pending.add(new EvaluationFrame(root));
            while (!pending.isEmpty()) {
                EvaluationFrame frame = pending.getLast();
                if (frame.input < frame.expression.inputs.size()) {
                    Expression child = frame.expression.inputs.get(frame.input++); budget.step();
                    if (!values.containsKey(child) && !simple(child)) pending.add(new EvaluationFrame(child));
                    continue;
                }
                List<Object> inputs = frame.expression.inputs.stream().map(values::get).toList();
                values.put(frame.expression, inputs.contains(Unknown.VALUE) ? Unknown.VALUE : evaluateKnown(frame.expression, inputs));
                pending.removeLast();
            }
            return values.get(root);
        }

        private boolean simple(Expression expression) {
            budget.step();
            Object value = switch (expression.operator) {
                case "constant" -> expression.value;
                case "symbol" -> model.containsKey(expression.value) ? model.get(expression.value) : Unknown.VALUE;
                case "unknown" -> Unknown.VALUE;
                default -> sameReferenceSymbol(expression) && expression.inputs.stream().allMatch(input -> input.operator.equals("symbol"))
                        ? expression.operator.equals("==") : EvaluationState.COMPUTE;
            };
            if (value == EvaluationState.COMPUTE) return false;
            values.put(expression, value); return true;
        }
    }

    private Object evaluateKnown(Expression expression, List<Object> inputs) {
        try {
            String op = expression.operator;
            if (comparison(op)) {
                Expression leftExpression = expression.inputs.getFirst(), rightExpression = expression.inputs.get(1);
                Object left = inputs.getFirst(), right = inputs.get(1);
                if (!primitiveType(leftExpression.type) && !primitiveType(rightExpression.type)) {
                    if (sameReferenceSymbol(expression)) return op.equals("==");
                    if (left == null || right == null) return switch (op) {
                        case "==" -> left == right; case "!=" -> left != right; default -> Unknown.VALUE;
                    };
                    // 包装值和字符串内容不能证明目标 JVM 中的引用身份。
                    return Unknown.VALUE;
                }
                if (!integralType(leftExpression.type) || !integralType(rightExpression.type)) return Unknown.VALUE;
                BigInteger a = integral(left), b = integral(right);
                return a != null && b != null ? compare(op, a.compareTo(b)) : Unknown.VALUE;
            }
            if (op.equals("cmp")) {
                if (!expression.inputs.stream().allMatch(input -> integralType(input.type))) return Unknown.VALUE;
                return integral(inputs.getFirst()).compareTo(integral(inputs.get(1)));
            }
            return JavaValueOperations.evaluate((Computation) expression.value, expression.type, inputs, maxLength);
        } catch (RuntimeException e) { return Unknown.VALUE; }
    }

    private static boolean sameReferenceSymbol(Expression expression) {
        if (!Set.of("==", "!=").contains(expression.operator) || expression.inputs.size() != 2) return false;
        Variable left = expression.inputs.getFirst().referenceSymbol, right = expression.inputs.get(1).referenceSymbol;
        return left != null && left.equals(right);
    }
    private static boolean comparison(String operator) { return Set.of("==", "!=", ">", ">=", "<", "<=").contains(operator); }
    private static boolean integralType(String type) { return Set.of("boolean", "byte", "short", "char", "int", "long").contains(type); }
    private static boolean primitiveType(String type) { return integralType(type) || Set.of("float", "double").contains(type); }

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
        boolean empty(WorkBudget budget) {
            if (!integral) return mustNull && nonNull;
            if (min.compareTo(max) > 0) return true;
            BigInteger value = min;
            while (excluded.contains(value)) { budget.step(); value = value.add(BigInteger.ONE); }
            return value.compareTo(max) > 0;
        }
        List<Object> candidates(Set<Object> hints, WorkBudget budget) {
            budget.step();
            LinkedHashSet<Object> values = new LinkedHashSet<>();
            if (integral) {
                var numbers = new LinkedHashSet<BigInteger>(); numbers.add(BigInteger.ZERO); numbers.add(BigInteger.ONE);
                for (Object hint : hints) {
                    budget.step();
                    BigInteger value = integral(hint);
                    if (value != null) { numbers.add(value); numbers.add(value.subtract(BigInteger.ONE)); numbers.add(value.add(BigInteger.ONE)); }
                }
                BigInteger first = min;
                while (excluded.contains(first)) { budget.step(); first = first.add(BigInteger.ONE); }
                numbers.add(first); numbers.add(max);
                for (BigInteger value : numbers) {
                    budget.step();
                    if (value.compareTo(min) >= 0 && value.compareTo(max) <= 0 && !excluded.contains(value))
                        values.add(JavaValueOperations.cast(value.longValue(), variable.type()));
                }
            } else if (Set.of("float", "double").contains(variable.type())) {
                // 浮点条件暂不证明无解；即使搜索失败，也不能为基本类型构造 null 模型。
                values.add(JavaValueOperations.cast(0, variable.type()));
                values.add(JavaValueOperations.cast(1, variable.type()));
            } else {
                if (!nonNull) values.add(null);
                if (!mustNull && variable.type().equals("java.lang.String")) {
                    values.add(""); values.add("sample");
                    for (Object hint : hints) { budget.step(); if (hint instanceof String) values.add(hint); }
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
