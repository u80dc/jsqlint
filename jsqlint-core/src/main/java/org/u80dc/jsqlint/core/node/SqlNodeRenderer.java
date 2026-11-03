package org.u80dc.jsqlint.core.node;

import java.lang.reflect.Array;
import java.util.*;

/**
 * 部分求值的 SQL 模板渲染器。只有全部求值成功时才提供 sql；
 * 未知条件、参数或表达式保留在 residual 中，不伪造 NULL、空串或 false。
 * 输出为分析样例，不是可直接执行的参数化 SQL。
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
        Objects.requireNonNull(node, "node");
        Context context = new Context(parameters, evaluator);
        Fragment fragment = visit(node, context);
        return new RenderedSql(fragment.complete ? Optional.of(flatText(fragment.node)) : Optional.empty(),
                fragment.node, context.values, List.copyOf(context.diagnostics));
    }

    /**
     * 样例由调用方提供，core 不依赖参数推断实现，也不隐式制造笛卡尔积。
     */
    public static List<RenderedSql> renderSamples(SqlNode node, Collection<? extends Map<String, ?>> samples) {
        return samples.stream().map(sample -> render(node, sample)).toList();
    }

    private static Fragment visit(SqlNode node, Context context) {
        return switch (node) {
            case StaticTextSqlNode text -> new Fragment(text, true);
            case TextSqlNode text -> text(text, context);
            case ValueSqlNode value -> value(value, context);
            case MixedSqlNode mixed -> mixed(mixed, context);
            case IfSqlNode conditional -> {
                SqlValue value = context.evaluate(conditional.getTest());
                yield switch (value.truth()) {
                    case TRUE -> selected(node, conditional.getContents(), context);
                    case FALSE -> literal("");
                    case UNKNOWN -> unresolved(node, context, conditional.getTest(), value);
                };
            }
            case ChooseSqlNode choose -> choose(choose, context);
            case BindSqlNode bind -> {
                SqlValue value = context.evaluate(bind.getExpression());
                if (value instanceof SqlValue.Known known) {
                    context.values.put(bind.getName(), known.value());
                    yield literal("");
                }
                yield unresolved(node, context, bind.getExpression(), value);
            }
            case ForEachSqlNode foreach -> foreach(foreach, context);
            case WhereSqlNode where -> wrapped(node, where.getContents(), context, "WHERE", "", true, false, "", "");
            case SetSqlNode set -> wrapped(node, set.getContents(), context, "SET", "", false, true, "", "");
            case TrimSqlNode trim -> wrapped(node, trim.getContents(), context, trim.getPrefix(), trim.getSuffix(),
                    false, false, trim.getPrefixOverrides(), trim.getSuffixOverrides());
        };
    }

    private static Fragment mixed(MixedSqlNode mixed, Context context) {
        List<SqlNode> result = new ArrayList<>();
        List<SqlNode> children = mixed.getContents();
        for (int i = 0; i < children.size(); i++) {
            Fragment fragment = visit(children.get(i), context);
            result.add(fragment.node);
            if (!fragment.complete) {
                // A pending bind or conditional can change subsequent values; leave the tail untouched.
                result.addAll(children.subList(i + 1, children.size()));
                return new Fragment(new MixedSqlNode(result), false);
            }
        }
        return literal(flatText(new MixedSqlNode(result)));
    }

    private static Fragment selected(SqlNode owner, SqlNode contents, Context parent) {
        Context child = parent.copy();
        Fragment fragment = visit(contents, child);
        parent.absorb(child, fragment.complete);
        return fragment.complete ? fragment : new Fragment(owner, false);
    }

    private static Fragment choose(ChooseSqlNode choose, Context context) {
        for (IfSqlNode branch : choose.getWhenContents()) {
            SqlValue value = context.evaluate(branch.getTest());
            if (value.truth() == SqlValue.Truth.UNKNOWN) {
                return unresolved(choose, context, branch.getTest(), value);
            }
            if (value.truth() == SqlValue.Truth.TRUE) {
                return selected(choose, branch.getContents(), context);
            }
        }
        return choose.getOtherwiseContents() == null ? literal("")
                : selected(choose, choose.getOtherwiseContents(), context);
    }

    private static Fragment wrapped(SqlNode owner, SqlNode contents, Context context,
                                    String prefix, String suffix, boolean where, boolean set,
                                    String prefixOverrides, String suffixOverrides) {
        Context child = context.copy();
        Fragment fragment = visit(contents, child);
        context.absorb(child, fragment.complete);
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
        SqlValue collection = context.evaluate(foreach.getCollection());
        if (collection instanceof SqlValue.Unknown) {
            return unresolved(foreach, context, foreach.getCollection(), collection);
        }
        Object value = ((SqlValue.Known) collection).value();
        if (value == null && foreach.isNullable()) {
            return literal("");
        }
        List<Map.Entry<Object, Object>> items = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> items.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, item)));
        } else if (value instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object item : iterable) {
                items.add(new java.util.AbstractMap.SimpleImmutableEntry<>(i++, item));
            }
        } else if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                items.add(new java.util.AbstractMap.SimpleImmutableEntry<>(i, Array.get(value, i)));
            }
        } else {
            return unresolved(foreach, context, foreach.getCollection(),
                    new SqlValue.Unknown("foreach requires a non-null collection, array or map"));
        }
        List<String> sqls = new ArrayList<>();
        for (var item : items) {
            Context child = context.copy();
            child.values.put(foreach.getItem(), item.getValue());
            child.values.put(foreach.getIndex(), item.getKey());
            Fragment fragment = visit(foreach.getContents(), child);
            context.absorb(child, false); // Iteration variables and binds are local to each iteration.
            if (!fragment.complete) {
                return new Fragment(foreach, false);
            }
            String sql = flatText(fragment.node);
            if (!foreach.isSkipBlank() || !sql.isBlank()) {
                sqls.add(sql);
            }
        }
        return sqls.isEmpty() ? literal("") : literal(foreach.getOpen()
                + String.join(foreach.getSeparator(), sqls) + foreach.getClose());
    }

    private static Fragment text(TextSqlNode text, Context context) {
        SqlNode parsed;
        try {
            parsed = SqlNodeBuilder.templateText(text.getText());
        } catch (IllegalArgumentException e) {
            context.diagnostics.add(new SqlDiagnostic("INVALID_PARAMETER", e.getMessage()));
            return new Fragment(text, false);
        }
        return visit(parsed, context);
    }

    private static Fragment value(ValueSqlNode node, Context context) {
        SqlValue value = context.evaluate(node.getExpression());
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
            case StaticTextSqlNode text -> text.getText();
            case MixedSqlNode mixed -> {
                StringBuilder result = new StringBuilder();
                mixed.getContents().forEach(child -> result.append(flatText(child)));
                yield result.toString();
            }
            default -> throw new IllegalStateException("A completed result must contain only literal text");
        };
    }

    private record Fragment(SqlNode node, boolean complete) {
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

        private Context(Map<String, ?> values, SqlExpressionEvaluator evaluator) {
            this.values.putAll(Objects.requireNonNull(values, "parameters"));
            this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        }

        private Context copy() {
            return new Context(values, evaluator);
        }

        private void absorb(Context child, boolean commitValues) {
            diagnostics.addAll(child.diagnostics);
            if (commitValues) {
                values.clear();
                values.putAll(child.values);
            }
        }

        private SqlValue evaluate(String expression) {
            try {
                return Objects.requireNonNull(evaluator.evaluate(expression,
                        Collections.unmodifiableMap(values)), "Evaluator returned null");
            } catch (RuntimeException e) {
                return new SqlValue.Unknown("Evaluation failed: " + e.getMessage());
            }
        }
    }
}
