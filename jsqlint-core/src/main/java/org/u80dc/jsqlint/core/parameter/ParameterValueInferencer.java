package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;
import static org.u80dc.jsqlint.core.parameter.InferredParameterValue.InitialValue;

/**
 * 从传播树的叶子向使用点重演纯值操作。分支分别产生值与选中的传播树，不执行目标项目代码。
 * 实例属于单次分析/语句，随机输入缓存避免同一入口变量的重复引用生成互相矛盾的样例。
 * @author u80dc
 */
public final class ParameterValueInferencer {
    private static final int MAX_VALUES = 128, MAX_STEPS = 10000, MAX_DEPTH = 256, MAX_LENGTH = 1_000_000;
    private final ExternalValueGenerator generator;
    private final Runnable checkpoint;
    private final Map<SampleKey, SqlValue> samples = new HashMap<>();

    public ParameterValueInferencer() { this(new RandomExternalValueGenerator(), () -> {}); }
    public ParameterValueInferencer(ExternalValueGenerator generator) { this(generator, () -> {}); }
    public ParameterValueInferencer(ExternalValueGenerator generator, Runnable checkpoint) {
        this.generator = Objects.requireNonNull(generator); this.checkpoint = Objects.requireNonNull(checkpoint);
    }

    public ParameterValueInferenceResult infer(ValueTrace trace) { return infer(variableName(trace.root()), trace); }
    /** 绑定方法的声明类型补足 Jimple 的 int 字面量及无类型 null；其余情况保留变量自身类型。 */
    public ParameterValueInferenceResult infer(ValueTrace trace, String expectedType) {
        if (expectedType.equals(trace.variable().type())
                || !primitiveType(expectedType) && !trace.variable().type().equals("null")) return infer(trace);
        Variable source = trace.variable();
        Variable typed = new Variable(source.method(), source.statementIndex(), source.expression(), expectedType);
        Node converted = new Node(typed, Kind.EXPRESSION, "Binding argument conversion", List.of(trace.root()),
                new Computation(Operation.CAST, expectedType));
        return infer(variableName(trace.root()), new ValueTrace(typed, trace.callChain(), converted));
    }
    public ParameterValueInferenceResult infer(String name, ValueTrace trace) {
        Objects.requireNonNull(name); Objects.requireNonNull(trace);
        var query = new Query();
        var values = query.visit(trace.root(), 0).stream().map(value -> new InferredParameterValue(
                value.value(), new ValueTrace(trace.variable(), trace.callChain(), value.node()),
                value.initials(), value.issues())).toList();
        return new ParameterValueInferenceResult(name, trace.variable().type(), values, new ArrayList<>(query.issues));
    }

    /** 变量名用于展示和槽位关联，源代码调试信息缺失时保留字节码名称/参数编号。 */
    public static String variableName(Node root) {
        Deque<Node> pending = new ArrayDeque<>(); pending.add(root);
        int count = 0;
        while (!pending.isEmpty() && count++ < 256) {
            Node node = pending.removeFirst();
            if (node.kind() == Kind.DEFINITION && node.variable().expression().matches("[\\p{L}_][\\p{L}\\p{N}_]*")
                    && !node.variable().expression().matches("(?:stack|l|r)\\d+")) return node.variable().expression();
            pending.addAll(node.inputs());
        }
        return root.variable().expression();
    }

    private record SampleKey(Variable variable, String type, String element) {}
    private record Evaluation(SqlValue value, Node node, List<InitialValue> initials, List<String> issues, Map<Node, Integer> choices) {}
    private record Arguments(List<Evaluation> values, Map<Node, Integer> choices) {}

    private final class Query {
        private int steps;
        private final Set<String> issues = new LinkedHashSet<>();
        private final Map<Node, List<Evaluation>> cache = new HashMap<>();

        List<Evaluation> visit(Node node, int depth) {
            checkpoint.run();
            if (++steps > MAX_STEPS || depth > MAX_DEPTH) return List.of(unknown(node, "Parameter inference budget exceeded"));
            if (cache.containsKey(node)) return cache.get(node);
            List<Evaluation> result = compute(node, depth);
            cache.put(node, result);
            return result;
        }

        private List<Evaluation> compute(Node node, int depth) {
            if (node.kind() == Kind.ENTRY || node.kind() == Kind.NO_CALLER) {
                // RawSqlBuilder 的外部数组槽位仍携带数组根与下标，用元素类型生成样例。
                if (node.computation().operation() == Operation.ARRAY_READ && !node.inputs().isEmpty()) return array(node, depth);
                return List.of(external(node, node.variable(), node.variable().type(), ""));
            }
            return switch (node.computation().operation()) {
                case LITERAL, NULL -> List.of(literal(node));
                case ALTERNATIVES -> alternatives(node, depth);
                case ARRAY_READ -> array(node, depth);
                case OPAQUE -> List.of(unknown(node, "Unmodeled parameter value: " + node.detail()));
                default -> operations(node, depth);
            };
        }

        private Evaluation literal(Node node) {
            try {
                Object value = node.computation().operation() == Operation.NULL ? null
                        : JavaValueOperations.parse(node.computation().text(), node.variable().type());
                if (value instanceof String text && text.length() > MAX_LENGTH) return unknown(node, "Inferred value length limit exceeded");
                SqlValue known = new SqlValue.Known(value);
                return new Evaluation(known, node, List.of(new InitialValue(node.variable(), known, false)), List.of(), Map.of());
            } catch (RuntimeException e) { return unknown(node, failure(e)); }
        }

        private Evaluation external(Node node, Variable source, String type, String element) {
            var key = new SampleKey(source, type, element);
            SqlValue value = samples.computeIfAbsent(key, ignored -> Objects.requireNonNull(generator.generate(type, variableName(node))));
            if (value instanceof SqlValue.Unknown unknown) issues.add(unknown.reason());
            return new Evaluation(value, node, List.of(new InitialValue(node.variable(), value, value instanceof SqlValue.Known)),
                    value instanceof SqlValue.Unknown unknown ? List.of(unknown.reason()) : List.of(), Map.of());
        }

        private List<Evaluation> alternatives(Node node, int depth) {
            if (node.inputs().isEmpty()) return List.of(unknown(node, "No parameter source alternatives"));
            List<Evaluation> result = new ArrayList<>();
            for (int i = 0; i < node.inputs().size(); i++) {
                for (Evaluation value : visit(node.inputs().get(i), depth + 1)) {
                    if (result.size() >= MAX_VALUES) { issues.add("Parameter value limit exceeded"); return List.copyOf(result); }
                    var choices = new HashMap<>(value.choices()); choices.put(node, i);
                    Evaluation wrapped = wrap(node, List.of(value), choices, value.value());
                    result.add(coerce(wrapped, node.variable().type()));
                }
            }
            return List.copyOf(result);
        }

        private List<Evaluation> operations(Node node, int depth) {
            List<List<Evaluation>> inputs = node.inputs().stream().map(input -> visit(input, depth + 1)).toList();
            if (inputs.isEmpty()) return List.of(unknown(node, "Missing operands for " + node.computation().operation()));
            List<Evaluation> result = new ArrayList<>();
            for (Arguments args : combinations(inputs)) {
                var values = args.values();
                Optional<SqlValue.Unknown> unknown = values.stream().map(Evaluation::value)
                        .filter(SqlValue.Unknown.class::isInstance).map(SqlValue.Unknown.class::cast).findFirst();
                if (unknown.isPresent()) {
                    result.add(wrap(node, values, args.choices(), unknown.get())); continue;
                }
                List<Object> operands = values.stream().map(value -> ((SqlValue.Known) value.value()).value()).toList();
                try {
                    Object evaluated = apply(node, operands);
                    if (evaluated instanceof String text && text.length() > MAX_LENGTH)
                        throw new IllegalArgumentException("Inferred value length limit exceeded");
                    result.add(wrap(node, values, args.choices(), new SqlValue.Known(evaluated)));
                } catch (RuntimeException e) {
                    String message = failure(e); issues.add(message);
                    result.add(withIssue(wrap(node, values, args.choices(), new SqlValue.Unknown(message)), message));
                }
            }
            return result.isEmpty() ? List.of(unknown(node, "No replay result within inference budget or compatible choices")) : List.copyOf(result);
        }

        private Object apply(Node node, List<Object> values) {
            return switch (node.computation().operation()) {
                case IDENTITY -> {
                    if (values.size() != 1) throw new IllegalArgumentException("Invalid identity operands");
                    Object value = values.getFirst();
                    if (node.computation().text().equals("Objects.requireNonNull") || node.computation().text().equals("String constructor"))
                        Objects.requireNonNull(value, "Null input on normal-return path");
                    if (node.computation().text().equals("String cast")) yield JavaValueOperations.cast(value, "java.lang.String");
                    yield primitiveType(node.variable().type()) ? JavaValueOperations.cast(value, node.variable().type()) : value;
                }
                case STRINGIFY -> JavaValueOperations.stringify(values.getFirst(), node.computation().text());
                case CONCAT -> {
                    StringBuilder text = new StringBuilder();
                    for (Object value : values) {
                        if (node.computation().text().equals("String.concat")) Objects.requireNonNull(value, "String.concat null operand");
                        String part = String.valueOf(value);
                        if ((long) text.length() + part.length() > MAX_LENGTH) throw new IllegalArgumentException("Inferred value length limit exceeded");
                        text.append(part);
                    }
                    yield text.toString();
                }
                case REPLACE -> JavaValueOperations.replace((String) values.get(0), (String) values.get(1), (String) values.get(2), MAX_LENGTH);
                case BINARY -> JavaValueOperations.binary(node.computation().text(), values.get(0), values.get(1), node.variable().type());
                case NEGATE -> JavaValueOperations.negate(values.getFirst(), node.variable().type());
                case CAST -> JavaValueOperations.cast(values.getFirst(), node.computation().text());
                case CALL -> JavaValueOperations.call(node.computation().method().orElseThrow(), values, MAX_LENGTH);
                default -> throw new IllegalArgumentException("Unmodeled operation: " + node.computation().operation());
            };
        }

        private List<Evaluation> array(Node node, int depth) {
            if (node.inputs().size() < 2) return List.of(unknown(node, "Array element source is unavailable"));
            List<Evaluation> result = new ArrayList<>();
            var indexes = visit(node.inputs().getFirst(), depth + 1);
            for (int i = 1; i < node.inputs().size(); i++) {
                Node source = node.inputs().get(i);
                boolean external = (source.kind() == Kind.ENTRY || source.kind() == Kind.NO_CALLER) && source.variable().type().endsWith("[]");
                for (Evaluation index : indexes) {
                    List<Evaluation> values;
                    if (external) {
                        if (index.value() instanceof SqlValue.Known known && known.value() instanceof Number number && number.intValue() >= 0) {
                            // 保存元素自己的类型与下标，避免把 String 样例记成 String[] 的初始值。
                            String element = Integer.toString(number.intValue());
                            Variable variable = new Variable(source.variable().method(), source.variable().statementIndex(),
                                    source.variable().expression() + "[" + element + "]", node.variable().type());
                            Node input = new Node(variable, source.kind(), "external array element", List.of(source));
                            values = List.of(external(input, source.variable(), variable.type(), element));
                        } else values = List.of(unknown(source, "External array index is unknown or negative"));
                    } else values = visit(source, depth + 1);
                    for (Evaluation value : values) {
                        if (result.size() >= MAX_VALUES) { issues.add("Parameter value limit exceeded"); return List.copyOf(result); }
                        Map<Node, Integer> choices = merge(index.choices(), value.choices());
                        if (choices == null) continue;
                        choices.put(node, i);
                        SqlValue actual = index.value() instanceof SqlValue.Unknown ? index.value() : value.value();
                        Evaluation wrapped = wrap(node, List.of(index, value), choices, actual);
                        if (index.value() instanceof SqlValue.Known known && known.value() instanceof Number number && number.intValue() < 0) {
                            String message = "Negative array index on replay path"; issues.add(message);
                            wrapped = withIssue(new Evaluation(new SqlValue.Unknown(message), wrapped.node(), wrapped.initials(), wrapped.issues(), wrapped.choices()), message);
                        }
                        result.add(coerce(wrapped, node.variable().type()));
                    }
                }
            }
            return result.isEmpty() ? List.of(unknown(node, "No compatible array element replay result")) : List.copyOf(result);
        }

        private List<Arguments> combinations(List<List<Evaluation>> inputs) {
            List<Arguments> result = List.of(new Arguments(List.of(), Map.of()));
            for (List<Evaluation> input : inputs) {
                List<Arguments> next = new ArrayList<>();
                outer: for (Arguments prefix : result) for (Evaluation suffix : input) {
                    checkpoint.run();
                    if (++steps > MAX_STEPS) { issues.add("Parameter inference budget exceeded"); return List.of(); }
                    Map<Node, Integer> choices = merge(prefix.choices(), suffix.choices());
                    if (choices == null) continue;
                    if (next.size() >= MAX_VALUES) { issues.add("Parameter value limit exceeded"); break outer; }
                    var values = new ArrayList<>(prefix.values()); values.add(suffix);
                    next.add(new Arguments(List.copyOf(values), choices));
                }
                result = List.copyOf(next);
            }
            return result;
        }

        private Evaluation coerce(Evaluation value, String type) {
            if (!(value.value() instanceof SqlValue.Known known) || !primitiveType(type)) return value;
            try { return new Evaluation(new SqlValue.Known(JavaValueOperations.cast(known.value(), type)), value.node(), value.initials(), value.issues(), value.choices()); }
            catch (RuntimeException e) {
                String message = failure(e); issues.add(message);
                return withIssue(new Evaluation(new SqlValue.Unknown(message), value.node(), value.initials(), value.issues(), value.choices()), message);
            }
        }

        private Evaluation unknown(Node node, String reason) {
            issues.add(reason);
            return new Evaluation(new SqlValue.Unknown(reason), node, List.of(), List.of(reason), Map.of());
        }
    }

    private static Evaluation wrap(Node node, List<Evaluation> operands, Map<Node, Integer> choices, SqlValue value) {
        Node selected = new Node(node.variable(), node.kind(), node.detail(), operands.stream().map(Evaluation::node).toList(), node.computation());
        return new Evaluation(value, selected, operands.stream().flatMap(v -> v.initials().stream()).distinct().toList(),
                operands.stream().flatMap(v -> v.issues().stream()).distinct().toList(), Map.copyOf(choices));
    }
    private static Evaluation withIssue(Evaluation value, String issue) {
        var issues = new ArrayList<>(value.issues()); issues.add(issue);
        return new Evaluation(value.value(), value.node(), value.initials(), List.copyOf(issues), value.choices());
    }
    private static Map<Node, Integer> merge(Map<Node, Integer> left, Map<Node, Integer> right) {
        var merged = new HashMap<>(left);
        for (var entry : right.entrySet()) {
            Integer existing = merged.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing != null && !existing.equals(entry.getValue())) return null;
        }
        return merged;
    }
    private static boolean primitiveType(String type) { return Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char").contains(type); }
    private static String failure(RuntimeException error) { return "Replay failed: " + error.getClass().getSimpleName() + ": " + error.getMessage(); }
}
