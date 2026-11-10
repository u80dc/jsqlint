package org.u80dc.jsqlint.core.node;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Function;

/**
 * 部分求值的 SQL 模板渲染器。render 提供字面量预览，renderBound 分离 SQL 与绑定值。
 * 未知条件或文本表达式保留在 residual 中；参数化模式的未知绑定仍输出占位符。
 *
 * @author u80dc
 */
public final class SqlNodeRenderer {
    private SqlNodeRenderer() {
    }

    public static RenderedSql render(SqlNode node, Map<String, ?> parameters) {
        return render(node, parameters, SqlExpressionEvaluator.simple());
    }

    public static RenderedSql render(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator) {
        return render(node, parameters, evaluator, () -> {});
    }

    public static RenderedSql render(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator, Runnable checkpoint) {
        Objects.requireNonNull(node, "node");
        Context context = new Context(parameters, evaluator, false, SqlNodeRenderer::inputOrigin, new RenderBudget(checkpoint));
        Fragment fragment = renderFragment(node, context);
        return new RenderedSql(fragment.complete ? Optional.of(flatText(fragment.node)) : Optional.empty(),
                fragment.node, context.values, List.copyOf(context.diagnostics));
    }

    /** 参数节点输出占位符；未知绑定值不会否定已经确定的 SQL 文本。 */
    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator) {
        return renderBound(node, parameters, evaluator, () -> {});
    }

    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator, Runnable checkpoint) {
        return renderBound(node, parameters, evaluator, SqlNodeRenderer::inputOrigin, checkpoint);
    }

    /** 适配后的输入引用由调用方关联到通用来源；局部 bind/foreach 来源由渲染器记录。 */
    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator,
                                      Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs, Runnable checkpoint) {
        Objects.requireNonNull(node);
        Context context = new Context(parameters, evaluator, true, inputs, new RenderBudget(checkpoint));
        Fragment fragment = renderFragment(node, context);
        return new BoundSql(new RenderedSql(fragment.complete ? Optional.of(flatText(fragment.node)) : Optional.empty(),
                fragment.node, context.values, List.copyOf(context.diagnostics)), context.bindings);
    }

    private static Fragment renderFragment(SqlNode node, Context context) {
        try { return visit(node, context); }
        catch (RenderLimit limit) {
            context.diagnostics.add(new SqlDiagnostic("RENDER_LIMIT", "SQL template rendering budget exceeded"));
            context.bindings.clear(); context.origins.clear(); context.values.clear(); context.values.putAll(context.initial);
            return new Fragment(node, false);
        }
    }

    public record BoundSql(RenderedSql rendered, List<BoundParameter> bindings) {
        public BoundSql { Objects.requireNonNull(rendered); bindings = List.copyOf(bindings); }
        public boolean valuesComplete() {
            return rendered.complete() && bindings.stream().allMatch(binding -> binding.value() instanceof SqlValue.Known);
        }
    }
    /** 位置按模板实际输出顺序编号；表达式、类型提示及模块绑定选项原样保留。 */
    public record BoundParameter(int position, String expression, String type, String options, SqlValue value, SqlValueOrigin origin) {
        public BoundParameter {
            if (position < 1) throw new IllegalArgumentException("Binding positions start at one");
            Objects.requireNonNull(expression); Objects.requireNonNull(type); Objects.requireNonNull(options); Objects.requireNonNull(value);
            Objects.requireNonNull(origin);
        }
        public BoundParameter(int position, String expression, String type, String options, SqlValue value) {
            this(position, expression, type, options, value, new SqlValueOrigin.Input(expression));
        }
    }

    /**
     * 样例由调用方提供，core 不依赖参数推断实现，也不隐式制造笛卡尔积。
     */
    public static List<RenderedSql> renderSamples(SqlNode node, Collection<? extends Map<String, ?>> samples) {
        return samples.stream().map(sample -> render(node, sample)).toList();
    }

    private static Fragment visit(SqlNode node, Context context) {
        context.checkpoint();
        return switch (node) {
            case StaticTextSqlNode text -> new Fragment(text, true);
            case TextSqlNode text -> text(text, context);
            case ValueSqlNode value -> value(value, context);
            case MixedSqlNode mixed -> mixed(mixed, context);
            case IfSqlNode conditional -> {
                SqlValue value = context.evaluate(conditional.test());
                yield switch (value.truth()) {
                    case TRUE -> selected(node, conditional.contents(), context);
                    case FALSE -> literal("");
                    case UNKNOWN -> unresolved(node, context, conditional.test(), value);
                };
            }
            case ChooseSqlNode choose -> choose(choose, context);
            case BindSqlNode bind -> {
                var evaluated = context.evaluated(bind.expression(), false);
                SqlValue value = evaluated.value();
                if (value instanceof SqlValue.Known known) {
                    context.values.put(bind.name(), known.value());
                    var inputs = evaluated.origin() instanceof SqlValueOrigin.Expression expression
                            && expression.expression().equals(bind.expression()) ? expression.inputs() : List.of(evaluated.origin());
                    context.origins.put(bind.name(), new SqlValueOrigin.Expression(bind.expression(), inputs));
                    yield literal("");
                }
                yield unresolved(node, context, bind.expression(), value);
            }
            case ForEachSqlNode foreach -> foreach(foreach, context);
            case WhereSqlNode where -> wrapped(node, where.contents(), context, "WHERE", "", true, false, "", "");
            case SetSqlNode set -> wrapped(node, set.contents(), context, "SET", "", false, true, "", "");
            case TrimSqlNode trim -> wrapped(node, trim.contents(), context, trim.prefix(), trim.suffix(),
                    false, false, trim.prefixOverrides(), trim.suffixOverrides());
        };
    }

    private static Fragment mixed(MixedSqlNode mixed, Context context) {
        List<SqlNode> result = new ArrayList<>();
        List<SqlNode> children = mixed.contents();
        for (int i = 0; i < children.size(); i++) {
            Fragment fragment = visit(children.get(i), context);
            result.add(fragment.node);
            if (!fragment.complete) {
                // A pending bind or conditional can change subsequent values; leave the tail untouched.
                result.addAll(children.subList(i + 1, children.size()));
                return new Fragment(new MixedSqlNode(result, mixed.separator()), false);
            }
        }
        return literal(flatText(new MixedSqlNode(result, mixed.separator())));
    }

    private static Fragment selected(SqlNode owner, SqlNode contents, Context parent) {
        Context child = parent.copy();
        Fragment fragment = visit(contents, child);
        parent.absorb(child, fragment.complete, fragment.complete);
        return fragment.complete ? fragment : new Fragment(owner, false);
    }

    private static Fragment choose(ChooseSqlNode choose, Context context) {
        for (IfSqlNode branch : choose.whenContents()) {
            SqlValue value = context.evaluate(branch.test());
            if (value.truth() == SqlValue.Truth.UNKNOWN) {
                return unresolved(choose, context, branch.test(), value);
            }
            if (value.truth() == SqlValue.Truth.TRUE) {
                return selected(choose, branch.contents(), context);
            }
        }
        return choose.otherwiseContents() == null ? literal("")
                : selected(choose, choose.otherwiseContents(), context);
    }

    private static Fragment wrapped(SqlNode owner, SqlNode contents, Context context,
                                    String prefix, String suffix, boolean where, boolean set,
                                    String prefixOverrides, String suffixOverrides) {
        Context child = context.copy();
        Fragment fragment = visit(contents, child);
        context.absorb(child, fragment.complete, fragment.complete);
        if (!fragment.complete) {
            return new Fragment(owner, false);
        }
        String body = flatText(fragment.node);
        if (body.isBlank()) {
            return literal("");
        }
        body = body.stripLeading();
        if (where) {
            body = body.replaceFirst("(?i)^(AND|OR)(?=\\s)", "").stripLeading();
        }
        if (set) {
            body = removeSuffix(body, ",");
        }
        body = removePrefix(body, prefixOverrides);
        body = removeSuffix(body, suffixOverrides);
        if (body.isBlank()) {
            return literal("");
        }
        // Keep trailing newlines: they may terminate a SQL line comment.
        return literal((prefix.isEmpty() ? "" : prefix + " ") + body
                + (suffix.isEmpty() ? "" : " " + suffix));
    }

    private static String removePrefix(String sql, String overrides) {
        for (String token : overrides.split("\\|", -1)) {
            if (!token.isEmpty() && sql.regionMatches(true, 0, token, 0, token.length())) {
                return sql.substring(token.length()).stripLeading();
            }
        }
        return sql;
    }

    private static String removeSuffix(String sql, String overrides) {
        int end = sql.length();
        while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
            end--;
        }
        for (String token : overrides.split("\\|", -1)) {
            String trimmed = token.strip();
            int start = end - trimmed.length();
            if (!trimmed.isEmpty() && start >= 0 && sql.regionMatches(true, start, trimmed, 0, trimmed.length())) {
                return sql.substring(0, start) + sql.substring(end);
            }
        }
        return sql;
    }

    private static Fragment foreach(ForEachSqlNode foreach, Context context) {
        var evaluated = context.evaluated(foreach.collection(), false);
        SqlValue collection = evaluated.value();
        if (collection instanceof SqlValue.Unknown) {
            return unresolved(foreach, context, foreach.collection(), collection);
        }
        Object value = ((SqlValue.Known) collection).value();
        if (value == null && foreach.nullable()) {
            return literal("");
        }
        List<Map.Entry<Object, Object>> items = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                context.checkpoint();
                items.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, item));
            });
        } else if (value instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object item : iterable) {
                context.checkpoint();
                items.add(new java.util.AbstractMap.SimpleImmutableEntry<>(i++, item));
            }
        } else if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                context.checkpoint();
                items.add(new java.util.AbstractMap.SimpleImmutableEntry<>(i, Array.get(value, i)));
            }
        } else {
            return unresolved(foreach, context, foreach.collection(),
                    new SqlValue.Unknown("foreach requires a non-null collection, array or map"));
        }
        List<String> sqls = new ArrayList<>();
        Context loop = context.copy();
        for (var item : items) {
            Context child = loop.copy();
            if (!foreach.index().isEmpty()) {
                child.values.put(foreach.index(), item.getKey());
                child.origins.put(foreach.index(), new SqlValueOrigin.Literal(new SqlValue.Known(item.getKey())));
            }
            // Bind the item last so an explicitly shared name resolves to the element value.
            child.values.put(foreach.item(), item.getValue());
            child.origins.put(foreach.item(), new SqlValueOrigin.Expression("foreach element",
                    List.of(evaluated.origin(), new SqlValueOrigin.Literal(new SqlValue.Known(item.getKey())))));
            Fragment fragment = visit(foreach.contents(), child);
            loop.absorb(child, false, fragment.complete); // Iteration variables and binds are local to each iteration.
            if (!fragment.complete) {
                context.absorb(loop, false, false);
                return new Fragment(foreach, false);
            }
            String sql = flatText(fragment.node);
            if (!foreach.skipBlank() || !sql.isBlank()) {
                sqls.add(sql);
            }
        }
        context.absorb(loop, false, true);
        return sqls.isEmpty() ? literal("") : literal(foreach.open()
                + String.join(foreach.separator(), sqls) + foreach.close());
    }

    private static Fragment text(TextSqlNode text, Context context) {
        SqlNode parsed;
        try {
            parsed = SqlNodeBuilder.templateText(text.text());
        } catch (IllegalArgumentException e) {
            context.diagnostics.add(new SqlDiagnostic("INVALID_PARAMETER", e.getMessage()));
            return new Fragment(text, false);
        }
        return visit(parsed, context);
    }

    private static Fragment value(ValueSqlNode node, Context context) {
        var evaluated = context.evaluated(node.getExpression(), node.getKind() == ValueSqlNode.Kind.PARAMETER);
        SqlValue value = evaluated.value();
        if (context.parameterized && node.getKind() == ValueSqlNode.Kind.PARAMETER) {
            context.bindings.add(new BoundParameter(context.bindings.size() + 1, node.getExpression(),
                    node.getValueType(), node.getBindingOptions(), value, evaluated.origin()));
            if (value instanceof SqlValue.Unknown unknown)
                context.diagnostics.add(new SqlDiagnostic("UNKNOWN_PARAMETER", node.getExpression() + ": " + unknown.reason()));
            return literal("?");
        }
        if (value instanceof SqlValue.Known known) {
            Object raw = known.value();
            if (raw == null) {
                return literal(node.getNullText());
            }
            if (node.getKind() == ValueSqlNode.Kind.RAW_TEXT) {
                return literal(String.valueOf(raw));
            }
            if (raw instanceof Number || raw instanceof Boolean) {
                return literal(raw.toString());
            }
            if (raw instanceof CharSequence || raw instanceof Character) {
                return literal("'" + raw.toString().replace("'", "''") + "'");
            }
            value = new SqlValue.Unknown("No SQL literal representation for " + raw.getClass().getName());
        }
        return unresolved(node, context, node.getExpression(), value);
    }

    private static Fragment unresolved(SqlNode node, Context context, String expression, SqlValue value) {
        String reason = value instanceof SqlValue.Unknown unknown ? unknown.reason() : "Value is not known";
        context.diagnostics.add(new SqlDiagnostic("UNKNOWN_VALUE", expression + ": " + reason));
        return new Fragment(node, false);
    }

    private static Fragment literal(String sql) {
        return new Fragment(new StaticTextSqlNode(sql), true);
    }

    private static String flatText(SqlNode node) {
        return switch (node) {
            case StaticTextSqlNode text -> text.text();
            case MixedSqlNode mixed -> {
                yield String.join(mixed.separator(), mixed.contents().stream().map(SqlNodeRenderer::flatText)
                        .filter(text -> mixed.separator().isEmpty() || !text.isEmpty()).toList());
            }
            default -> throw new IllegalStateException("A completed result must contain only literal text");
        };
    }

    private record Fragment(SqlNode node, boolean complete) {
    }

    private record Evaluated(SqlValue value, SqlValueOrigin origin) {}

    private static SqlValueOrigin inputOrigin(SqlExpressionEvaluator.Reference reference) {
        return accessed(new SqlValueOrigin.Input(reference.name()), reference.path());
    }
    private static SqlValueOrigin accessed(SqlValueOrigin origin, List<Object> path) {
        if (path.isEmpty()) return origin;
        var inputs = new ArrayList<SqlValueOrigin>(); inputs.add(origin);
        path.forEach(key -> inputs.add(new SqlValueOrigin.Literal(new SqlValue.Known(key))));
        return new SqlValueOrigin.Expression("Template property access", inputs);
    }

    /**
     * sql 为空表示尚未得到完整 SQL。residual 可配合 parameters 和补充参数重新渲染；
     * diagnostics 解释未完成原因，真实 null 可以保留在 parameters 中。
     */
    public record RenderedSql(Optional<String> sql, SqlNode residual,
                              Map<String, Object> parameters, List<SqlDiagnostic> diagnostics) {
        public RenderedSql {
            Objects.requireNonNull(sql, "sql");
            Objects.requireNonNull(residual, "residual");
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean complete() {
            return sql.isPresent();
        }
    }

    private static final class Context {
        private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        private final LinkedHashSet<SqlDiagnostic> diagnostics = new LinkedHashSet<>();
        private final SqlExpressionEvaluator evaluator;
        private final List<BoundParameter> bindings = new ArrayList<>();
        private final Map<String, SqlValueOrigin> origins = new LinkedHashMap<>();
        private final Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs;
        private final boolean parameterized;
        private final RenderBudget budget;
        private final Map<String, ?> initial;

        private Context(Map<String, ?> values, SqlExpressionEvaluator evaluator, boolean parameterized,
                        Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs, RenderBudget budget) {
            this.values.putAll(Objects.requireNonNull(values, "parameters"));
            this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
            this.initial = values; this.parameterized = parameterized; this.budget = budget;
            this.inputs = Objects.requireNonNull(inputs);
        }

        private Context copy() {
            var copy = new Context(initial, evaluator, parameterized, inputs, budget);
            copy.values.clear(); copy.values.putAll(values); copy.bindings.addAll(bindings);
            copy.origins.putAll(origins);
            return copy;
        }

        private void absorb(Context child, boolean commitValues, boolean commitBindings) {
            diagnostics.addAll(child.diagnostics);
            if (commitBindings) { bindings.clear(); bindings.addAll(child.bindings); }
            if (commitValues) {
                values.clear();
                values.putAll(child.values);
                origins.clear(); origins.putAll(child.origins);
            }
        }

        private SqlValue evaluate(String expression) {
            return evaluate(expression, false);
        }
        private SqlValue evaluate(String expression, boolean parameter) {
            return evaluated(expression, parameter).value();
        }
        private Evaluated evaluated(String expression, boolean parameter) {
            try {
                Map<String, Object> overlay = new AbstractMap<>() {
                    @Override public Set<Entry<String, Object>> entrySet() { return values.entrySet(); }
                    @Override public boolean containsKey(Object key) { return values.containsKey(key) || initial.containsKey(key); }
                    @Override public Object get(Object key) { return values.containsKey(key) ? values.get(key) : initial.get(key); }
                };
                var environment = Collections.unmodifiableMap(overlay);
                if (!parameterized) {
                    var value = Objects.requireNonNull(parameter ? evaluator.evaluateParameter(expression, environment)
                            : evaluator.evaluate(expression, environment), "Evaluator returned null");
                    return new Evaluated(value, new SqlValueOrigin.Literal(value));
                }
                var evaluation = evaluator.evaluateWithReferences(expression, environment, parameter);
                var references = evaluation.references().stream().map(reference -> origins.containsKey(reference.name())
                        ? accessed(origins.get(reference.name()), reference.path())
                        : Objects.requireNonNull(inputs.apply(reference), "Input origin is unavailable")).toList();
                SqlValueOrigin origin = references.isEmpty() ? new SqlValueOrigin.Literal(evaluation.value())
                        : evaluation.directReference() ? references.getFirst() : new SqlValueOrigin.Expression(expression, references);
                return new Evaluated(evaluation.value(), origin);
            } catch (RuntimeException e) {
                var value = new SqlValue.Unknown("Evaluation failed: " + e.getMessage());
                return new Evaluated(value, new SqlValueOrigin.Literal(value));
            }
        }
        private void checkpoint() { if (budget != null) budget.checkpoint(); }
    }
    private static final class RenderBudget {
        private final Runnable checkpoint;
        private int steps;
        private RenderBudget(Runnable checkpoint) { this.checkpoint = Objects.requireNonNull(checkpoint); }
        void checkpoint() {
            checkpoint.run();
            if (++steps > 10000) throw new RenderLimit();
        }
    }
    private static final class RenderLimit extends RuntimeException {
        RenderLimit() { super("Rendering limit", null, false, false); }
    }
}
