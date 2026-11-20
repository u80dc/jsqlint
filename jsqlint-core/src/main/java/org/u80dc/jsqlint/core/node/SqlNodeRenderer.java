package org.u80dc.jsqlint.core.node;

import org.u80dc.jsqlint.core.ReplayBudgets;
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
        return render(node, parameters, evaluator, SqlEvaluationContext.defaults(checkpoint, ignored -> {}));
    }

    public static RenderedSql render(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator,
                                     ReplayBudgets budgets, Runnable checkpoint) {
        return render(node, parameters, evaluator, new SqlEvaluationContext(budgets, checkpoint));
    }

    public static RenderedSql render(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator,
                                     SqlEvaluationContext execution) {
        Objects.requireNonNull(node, "node");
        var diagnostics = new LinkedHashSet<SqlDiagnostic>();
        execution = Objects.requireNonNull(execution).reportingTo(message -> diagnostics.add(new SqlDiagnostic("RENDER_LIMIT", message, SqlDiagnostic.Effect.LIMIT)));
        Context context = new Context(parameters, evaluator, false, SqlNodeRenderer::inputOrigin,
                new RenderBudget(execution));
        return execution.evaluate(() -> {
            Fragment fragment = renderFragment(node, context);
            context.diagnostics.addAll(diagnostics);
            return new RenderedSql(fragment.complete ? Optional.of(((StaticTextSqlNode) fragment.node).text()) : Optional.empty(),
                    fragment.node, context.values, List.copyOf(context.diagnostics));
        });
    }

    /**
     * 参数节点输出占位符；未知绑定值不会否定已经确定的 SQL 文本。
     * 返回的 residual 保留原始模板，parameters 保存原始输入；再次 renderBound 会完整重演并重建绑定。
     * 重演应使用相同求值器；自定义输入来源映射也需再次传入。
     */
    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator) {
        return renderBound(node, parameters, evaluator, () -> {});
    }

    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator, Runnable checkpoint) {
        return renderBound(node, parameters, evaluator, SqlNodeRenderer::inputOrigin, checkpoint);
    }

    /** 适配后的输入引用由调用方关联到通用来源；局部 bind/foreach 来源由渲染器记录。 */
    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator,
                                      Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs, Runnable checkpoint) {
        return renderBound(node, parameters, evaluator, inputs, SqlEvaluationContext.defaults(checkpoint, ignored -> {}));
    }

    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator,
                                      ReplayBudgets budgets, Runnable checkpoint) {
        return renderBound(node, parameters, evaluator, SqlNodeRenderer::inputOrigin, new SqlEvaluationContext(budgets, checkpoint));
    }

    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator,
                                      Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs,
                                      ReplayBudgets budgets, Runnable checkpoint) {
        return renderBound(node, parameters, evaluator, inputs, new SqlEvaluationContext(budgets, checkpoint));
    }

    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator,
                                      SqlEvaluationContext execution) {
        return renderBound(node, parameters, evaluator, SqlNodeRenderer::inputOrigin, execution);
    }

    public static BoundSql renderBound(SqlNode node, Map<String, ?> parameters, SqlExpressionEvaluator evaluator,
                                      Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs,
                                      SqlEvaluationContext execution) {
        Objects.requireNonNull(node);
        var diagnostics = new LinkedHashSet<SqlDiagnostic>();
        execution = Objects.requireNonNull(execution).reportingTo(message -> diagnostics.add(new SqlDiagnostic("RENDER_LIMIT", message, SqlDiagnostic.Effect.LIMIT)));
        Context context = new Context(parameters, evaluator, true, inputs, new RenderBudget(execution));
        return execution.evaluate(() -> {
            Fragment fragment = renderFragment(node, context);
            context.diagnostics.addAll(diagnostics);
            return new BoundSql(new RenderedSql(fragment.complete ? Optional.of(((StaticTextSqlNode) fragment.node).text()) : Optional.empty(),
                    node, new LinkedHashMap<>(context.initial), List.copyOf(context.diagnostics),
                    RenderedSql.RecoveryMode.BOUND_REPLAY), context.bindings);
        });
    }

    private static Fragment renderFragment(SqlNode node, Context context) {
        try {
            Fragment fragment = visit(node, context);
            return fragment.complete ? literal(flatText(fragment.node, context)) : fragment;
        }
        catch (RenderLimit limit) {
            if (!limit.reported) context.budget.evaluation.limit(limit.getMessage());
            context.diagnostics.add(new SqlDiagnostic("RENDER_LIMIT", limit.getMessage(), SqlDiagnostic.Effect.LIMIT));
            context.bindings.clear(); context.origins.clear(); context.values.clear(); context.values.putAll(context.initial);
            return new Fragment(node, false);
        }
    }

    /**
     * 绑定列表是本次渲染的快照。补充 rendered.parameters 后用 rendered.residual 再次 renderBound，
     * 从原始模板重建全部绑定及其来源，避免把前缀占位符当作无绑定的静态文本。
     */
    public record BoundSql(RenderedSql rendered, List<BoundParameter> bindings, List<SqlRewriteStep> rewrites) {
        public BoundSql(RenderedSql rendered,List<BoundParameter> bindings) {this(rendered,bindings,List.of());}
        public BoundSql {
            Objects.requireNonNull(rendered); bindings = List.copyOf(bindings);
            rewrites=List.copyOf(rewrites);
            for (int i = 0; i < bindings.size(); i++)
                if (bindings.get(i).position() != i + 1)
                    throw new IllegalArgumentException("Bindings must follow consecutive rendering positions");
        }
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
        context.budget.enter();
        try {
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
                    context.values.put(bind.name(), evaluated.activeValue());
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
        } finally { context.budget.exit(); }
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
        return literal(flatText(new MixedSqlNode(result, mixed.separator()), context));
    }

    private static Fragment selected(SqlNode owner, SqlNode contents, Context parent) {
        Context child = parent.copy();
        Fragment fragment = visit(contents, child);
        parent.absorb(child, fragment.complete, fragment.complete);
        return fragment.complete ? fragment : new Fragment(owner, false);
    }

    private static Fragment choose(ChooseSqlNode choose, Context context) {
        for (IfSqlNode branch : choose.whenContents()) {
            context.checkpoint();
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
        String body = flatText(fragment.node, context);
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
        Object value = evaluated.activeValue();
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
        long outputLength = (long) foreach.open().length() + foreach.close().length();
        context.budget.length(outputLength);
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
            String sql = flatText(fragment.node, context);
            if (!foreach.skipBlank() || !sql.isBlank()) {
                outputLength += sql.length() + (sqls.isEmpty() ? 0L : foreach.separator().length());
                context.budget.length(outputLength);
                sqls.add(sql);
            }
        }
        context.absorb(loop, false, true);
        return sqls.isEmpty() ? literal("") : literal(foreach.open()
                + String.join(foreach.separator(), sqls) + foreach.close());
    }

    private static Fragment text(TextSqlNode text, Context context) {
        context.budget.length(text.text().length());
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

    private static String flatText(SqlNode node, Context context) {
        Deque<TextFrame> pending = new ArrayDeque<>(); pending.addLast(new TextFrame(node));
        int steps = 0;
        while (!pending.isEmpty()) {
            context.budget.evaluation.check();
            if (++steps > context.budget.evaluation.renderBudgets().steps())
                throw new RenderLimit("SQL template text expansion work budget exceeded");
            TextFrame frame = pending.peekLast();
            String complete;
            if (frame.node instanceof StaticTextSqlNode text) {
                complete = text.text(); context.budget.length(complete.length());
            } else if (frame.node instanceof MixedSqlNode mixed) {
                if (frame.next < mixed.contents().size()) {
                    pending.addLast(new TextFrame(mixed.contents().get(frame.next++)));
                    continue;
                }
                complete = frame.text.toString();
            } else throw new IllegalStateException("A completed result must contain only literal text");
            pending.removeLast();
            if (pending.isEmpty()) return complete;
            TextFrame parent = pending.peekLast();
            String separator = ((MixedSqlNode) parent.node).separator();
            if (!separator.isEmpty() && complete.isEmpty()) continue;
            long length = (long) parent.text.length() + complete.length() + (parent.parts == 0 ? 0L : separator.length());
            context.budget.length(length);
            if (parent.parts++ > 0) parent.text.append(separator);
            parent.text.append(complete);
        }
        throw new IllegalStateException("Missing SQL text");
    }

    private static final class TextFrame {
        final SqlNode node;
        final StringBuilder text = new StringBuilder();
        int next;
        int parts;
        TextFrame(SqlNode node) { this.node = node; }
    }

    private record Fragment(SqlNode node, boolean complete) {
    }

    private record Evaluated(SqlValue value, SqlValueOrigin origin, Object activeValue) {}

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
     * sql 为空表示尚未得到完整 SQL。render 的 residual 保存已求值前缀，parameters 保存局部绑定；
     * renderBound 的 residual 保存原始模板，parameters 保存原始输入，供完整重演和重建绑定。
     * 两种结果均可配合 parameters 和补充参数重新渲染；parameters 仅快照 Map 可枚举的条目，
     * 使用隐式名称回退的自定义 Map 时，应再次提供包含该回退行为的输入 Map。
     * diagnostics 解释未完成原因，真实 null 可以保留在 parameters 中。
     */
    public record RenderedSql(Optional<String> sql, SqlNode residual,
                              Map<String, Object> parameters, List<SqlDiagnostic> diagnostics, RecoveryMode recoveryMode) {
        public enum RecoveryMode { LITERAL_CONTINUATION, BOUND_REPLAY }

        public RenderedSql {
            Objects.requireNonNull(sql, "sql");
            Objects.requireNonNull(residual, "residual");
            parameters = SqlValueSnapshots.parameters(parameters);
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(recoveryMode, "recoveryMode");
        }

        /** 兼容字面量预览与手工构造的结果；参数化重演由 renderBound 显式标记。 */
        public RenderedSql(Optional<String> sql, SqlNode residual, Map<String, Object> parameters,
                           List<SqlDiagnostic> diagnostics) {
            this(sql, residual, parameters, diagnostics, RecoveryMode.LITERAL_CONTINUATION);
        }

        @Override public Map<String, Object> parameters() { return SqlValueSnapshots.parameters(parameters); }

        public boolean complete() {
            return sql.isPresent();
        }
    }

    private static final class Context {
        private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        private final LinkedHashSet<SqlDiagnostic> diagnostics = new LinkedHashSet<>();
        private final SqlExpressionEvaluator evaluator;
        private final List<BoundParameter> bindings;
        private final int bindingStart;
        private final Map<String, SqlValueOrigin> origins = new LinkedHashMap<>();
        private final Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs;
        private final boolean parameterized;
        private final RenderBudget budget;
        private final Map<String, ?> initial;

        private Context(Map<String, ?> values, SqlExpressionEvaluator evaluator, boolean parameterized,
                        Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs, RenderBudget budget) {
            this(values, evaluator, parameterized, inputs, budget, new ArrayList<>());
        }

        private Context(Map<String, ?> values, SqlExpressionEvaluator evaluator, boolean parameterized,
                        Function<SqlExpressionEvaluator.Reference, SqlValueOrigin> inputs, RenderBudget budget,
                        List<BoundParameter> bindings) {
            this.values.putAll(Objects.requireNonNull(values, "parameters"));
            this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
            this.initial = values; this.parameterized = parameterized; this.budget = budget;
            this.inputs = Objects.requireNonNull(inputs);
            this.bindings = bindings; this.bindingStart = bindings.size();
        }

        private Context copy() {
            var copy = new Context(initial, evaluator, parameterized, inputs, budget, bindings);
            copy.values.clear(); copy.values.putAll(values);
            copy.origins.putAll(origins);
            return copy;
        }

        private void absorb(Context child, boolean commitValues, boolean commitBindings) {
            diagnostics.addAll(child.diagnostics);
            // Child scopes append to one buffer. Failed scopes remove only their additions,
            // including completed earlier iterations of an unfinished foreach.
            if (!commitBindings) bindings.subList(child.bindingStart, bindings.size()).clear();
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
                var evaluation = evaluator.evaluateWithReferences(expression, environment, parameter, budget.evaluation)
                        .withActivity(budget.evaluation, environment);
                if (evaluation.limit().isPresent()) throw new RenderLimit(evaluation.limit().get(), true);
                var references = new ArrayList<SqlValueOrigin>();
                int steps = 0;
                for (var reference : evaluation.references()) {
                    budget.evaluation.check();
                    if (reference.path().size() > Math.min(128, budget.evaluation.replayBudgets().depth()))
                        throw new RenderLimit("Expression origin path depth budget exceeded");
                    if (++steps > budget.evaluation.replayBudgets().steps())
                        throw new RenderLimit("Expression origin work budget exceeded");
                    for (Object ignored : reference.path()) {
                        budget.evaluation.check();
                        if (++steps > budget.evaluation.replayBudgets().steps())
                            throw new RenderLimit("Expression origin work budget exceeded");
                    }
                    references.add(origins.containsKey(reference.name())
                            ? accessed(origins.get(reference.name()), reference.path())
                            : Objects.requireNonNull(inputs.apply(reference), "Input origin is unavailable"));
                }
                SqlValueOrigin origin = references.isEmpty() ? new SqlValueOrigin.Literal(evaluation.value())
                        : evaluation.directReference() ? references.getFirst() : new SqlValueOrigin.Expression(expression, references);
                Object activeValue = evaluation.activity().isPresent() ? evaluation.activity().get().value(budget.evaluation) : null;
                return new Evaluated(evaluation.value(), origin, activeValue);
            } catch (RuntimeException e) {
                SqlEvaluationContext.rethrowControl(e);
                if (e instanceof RenderLimit) throw e;
                var value = new SqlValue.Unknown("Evaluation failed: " + e.getMessage());
                return new Evaluated(value, new SqlValueOrigin.Literal(value), null);
            }
        }
        private void checkpoint() { if (budget != null) budget.checkpoint(); }
    }
    private static final class RenderBudget {
        private final SqlEvaluationContext evaluation;
        private int steps;
        private int depth;
        private RenderBudget(SqlEvaluationContext evaluation) { this.evaluation = Objects.requireNonNull(evaluation); }
        void enter() {
            checkpoint();
            if (depth >= Math.min(128, evaluation.renderBudgets().depth()))
                throw new RenderLimit("SQL template rendering depth budget exceeded");
            depth++;
        }
        void exit() { depth--; }
        void length(long length) {
            evaluation.check();
            if (length > evaluation.renderBudgets().length())
                throw new RenderLimit("SQL template rendering text length budget exceeded");
        }
        void checkpoint() {
            evaluation.check();
            if (++steps > evaluation.renderBudgets().steps())
                throw new RenderLimit("SQL template rendering work budget exceeded");
        }
    }
    private static final class RenderLimit extends RuntimeException {
        private final boolean reported;
        RenderLimit(String message) { this(message, false); }
        RenderLimit(String message, boolean reported) { super(message, null, false, false); this.reported = reported; }
    }
}
