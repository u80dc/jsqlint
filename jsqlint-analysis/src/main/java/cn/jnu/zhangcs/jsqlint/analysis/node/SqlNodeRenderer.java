package cn.jnu.zhangcs.jsqlint.analysis.node;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.InferredParameterValue;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders parsed MyBatis-style {@link SqlNode} trees into possible SQL strings.
 *
 * <p>The renderer evaluates a pragmatic subset of OGNL expressions used by
 * dynamic SQL tags and substitutes sample values inferred by
 * {@link cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferencer}.
 * It is intended for analysis/audit, not for executing SQL.
 */
public final class SqlNodeRenderer {

    private static final Pattern PARAMETER_PATTERN = Pattern.compile("([#$])\\{\\s*([^}]+?)\\s*}");

    private SqlNodeRenderer() {
    }

    /**
     * Render one possible SQL using caller-supplied parameter values.
     */
    public static RenderedSql render(SqlNode node, Map<String, ?> parameters) {
        RenderContext context = new RenderContext(parameters);
        String sql = normalizeSql(renderNode(node, context));
        return new RenderedSql(sql, context.snapshot(), context.diagnostics());
    }

    /**
     * Render all SQL variants produced by parameter value inference results.
     *
     * <p>Each {@link ParameterValueInferenceResult} may contain multiple possible
     * values. This method computes their cartesian product and renders the SQL
     * once per parameter combination.
     */
    public static List<RenderedSql> render(
            SqlNode node,
            Collection<ParameterValueInferenceResult> inferenceResults) {

        if (node == null) {
            return List.of();
        }

        List<RenderContext> contexts = contexts(inferenceResults);
        List<RenderedSql> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RenderContext context : contexts) {
            RenderContext renderingContext = context.copy();
            String sql = normalizeSql(renderNode(node, renderingContext));
            String key = sql + "\n" + renderingContext.snapshot();
            if (seen.add(key)) {
                results.add(new RenderedSql(sql, renderingContext.snapshot(), renderingContext.diagnostics()));
            }
        }
        return List.copyOf(results);
    }

    public static List<RenderedSql> render(
            SqlNode node,
            ParameterValueInferenceResult... inferenceResults) {

        if (inferenceResults == null) {
            return render(node, List.of());
        }
        return render(node, List.of(inferenceResults));
    }

    private static String renderNode(SqlNode node, RenderContext context) {
        if (node == null) {
            return "";
        }
        if (node instanceof MixedSqlNode mixed) {
            return joinSql(mixed.getContents().stream()
                    .map(child -> renderNode(child, context))
                    .toList());
        }
        if (node instanceof StaticTextSqlNode text) {
            return substituteParameters(text.getText(), context);
        }
        if (node instanceof TextSqlNode text) {
            return substituteParameters(text.getText(), context);
        }
        if (node instanceof IfSqlNode ifNode) {
            return context.evaluate(ifNode.getTest())
                    ? renderNode(ifNode.getContents(), context)
                    : "";
        }
        if (node instanceof WhereSqlNode where) {
            String body = stripPrefixOverrides(renderNode(where.getContents(), context), "AND|OR");
            return body.isBlank() ? "" : "WHERE " + body;
        }
        if (node instanceof SetSqlNode set) {
            String body = stripSuffixOverrides(renderNode(set.getContents(), context), ",");
            return body.isBlank() ? "" : "SET " + body;
        }
        if (node instanceof TrimSqlNode trim) {
            String body = renderNode(trim.getContents(), context).trim();
            body = stripPrefixOverrides(body, trim.getPrefixOverrides());
            body = stripSuffixOverrides(body, trim.getSuffixOverrides());
            if (body.isBlank()) {
                return "";
            }
            return joinSql(List.of(trim.getPrefix(), body, trim.getSuffix()));
        }
        if (node instanceof ForEachSqlNode foreach) {
            return renderForEach(foreach, context);
        }
        if (node instanceof ChooseSqlNode choose) {
            return renderChoose(choose, context);
        }
        if (node instanceof BindSqlNode bind) {
            Object value = context.resolveExpression(bind.getExpression());
            context.put(bind.getName(), value);
            return "";
        }
        context.diagnostic("Unsupported SqlNode type: " + node.getClass().getName());
        return "";
    }

    private static String renderForEach(ForEachSqlNode foreach, RenderContext context) {
        Object value = context.resolveExpression(foreach.getCollection());
        List<IterationValue> values = iterationValues(value);
        if (values.isEmpty()) {
            return "";
        }

        List<String> renderedItems = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            IterationValue iteration = values.get(i);
            RenderContext itemContext = context.copy();
            itemContext.put(foreach.getItem(), iteration.value());
            if (foreach.getIndex() != null && !foreach.getIndex().isBlank()) {
                itemContext.put(foreach.getIndex(), iteration.index() == null ? i : iteration.index());
            }

            String itemSql = renderNode(foreach.getContents(), itemContext).trim();
            context.mergeDiagnostics(itemContext);
            if (!itemSql.isBlank()) {
                renderedItems.add(itemSql);
            }
        }

        if (renderedItems.isEmpty()) {
            return "";
        }

        String separator = foreach.getSeparator() == null ? "" : foreach.getSeparator();
        return nullToBlank(foreach.getOpen())
                + String.join(separator, renderedItems)
                + nullToBlank(foreach.getClose());
    }

    private static String renderChoose(ChooseSqlNode choose, RenderContext context) {
        for (SqlNode when : choose.getWhenContents()) {
            if (when instanceof IfSqlNode ifNode) {
                if (context.evaluate(ifNode.getTest())) {
                    return renderNode(ifNode.getContents(), context);
                }
                continue;
            }

            String rendered = renderNode(when, context);
            if (!rendered.isBlank()) {
                return rendered;
            }
        }

        return choose.getOtherwiseContents() == null
                ? ""
                : renderNode(choose.getOtherwiseContents(), context);
    }

    private static String substituteParameters(String text, RenderContext context) {
        if (text == null || text.isBlank()) {
            return "";
        }

        Matcher matcher = PARAMETER_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String marker = matcher.group(1);
            String expression = matcher.group(2);
            Object value = context.resolveExpression(expression);
            String replacement = "$".equals(marker) ? rawValue(value) : sqlLiteral(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static List<RenderContext> contexts(Collection<ParameterValueInferenceResult> inferenceResults) {
        List<RenderContext> contexts = new ArrayList<>();
        contexts.add(new RenderContext(Map.of()));
        if (inferenceResults == null || inferenceResults.isEmpty()) {
            return contexts;
        }

        for (ParameterValueInferenceResult result : inferenceResults) {
            if (result == null || result.possibleValues().isEmpty()) {
                continue;
            }

            List<RenderContext> expanded = new ArrayList<>();
            for (RenderContext context : contexts) {
                for (InferredParameterValue value : result.possibleValues()) {
                    RenderContext next = context.copy();
                    bindInferredValue(next, value);
                    expanded.add(next);
                }
            }
            contexts = expanded;
        }
        return contexts;
    }

    private static void bindInferredValue(RenderContext context, InferredParameterValue value) {
        if (value == null) {
            return;
        }
        Object sample = value.value();
        int index = value.target().index();
        context.put(value.target().name(), sample);
        context.put("param" + index, sample);
        context.put("arg" + index, sample);
        if (shouldExposeSingleParameterProperties(value)) {
            bindMapEntries(context, sample);
        }
        bindValueAliases(context, value.sourceValue(), sample);
        bindValueAliases(context, value.targetValue(), sample);
    }

    private static boolean shouldExposeSingleParameterProperties(InferredParameterValue value) {
        return value != null
                && value.target().method().getParameterCount() == 1
                && value.value() instanceof Map<?, ?>;
    }

    private static void bindMapEntries(RenderContext context, Object sample) {
        if (!(sample instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && !key.isBlank()) {
                context.put(key, entry.getValue());
            }
        }
    }

    private static void bindValueAliases(RenderContext context, ValueModel value, Object sample) {
        if (value == null) {
            return;
        }
        if (value instanceof ValueModel.ParameterValue parameter) {
            context.put(parameter.name(), sample);
            context.put("param" + parameter.index(), sample);
            context.put("arg" + parameter.index(), sample);
            return;
        }
        if (value instanceof ValueModel.FieldValue field) {
            context.put(field.fieldName(), sample);
            bindValueAliases(context, field.base(), sample);
            return;
        }
        if (value instanceof ValueModel.CastValue cast) {
            bindValueAliases(context, cast.value(), sample);
            return;
        }
        if (value instanceof ValueModel.ObjectValue object) {
            bindObjectFieldAliases(context, object, sample);
            return;
        }
        for (ValueModel.ParameterValue dependency : value.parameterDependencies()) {
            context.put(dependency.name(), sample);
        }
    }

    private static void bindObjectFieldAliases(
            RenderContext context,
            ValueModel.ObjectValue object,
            Object sample) {

        if (!(sample instanceof Map<?, ?> map)) {
            return;
        }
        for (String fieldName : object.fields().keySet()) {
            if (map.containsKey(fieldName)) {
                context.put(fieldName, map.get(fieldName));
            }
        }
    }

    private static List<IterationValue> iterationValues(Object value) {
        if (value == null) {
            return List.of();
        }
        List<IterationValue> values = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                values.add(new IterationValue(entry.getKey(), entry.getValue()));
            }
            return values;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object element : iterable) {
                values.add(new IterationValue(index++, element));
            }
            return values;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                values.add(new IterationValue(i, Array.get(value, i)));
            }
            return values;
        }
        values.add(new IterationValue(0, value));
        return values;
    }

    private static String stripPrefixOverrides(String sql, String prefixOverrides) {
        String result = sql == null ? "" : sql.trim();
        if (result.isBlank() || prefixOverrides == null || prefixOverrides.isBlank()) {
            return result;
        }

        for (String token : overrideTokens(prefixOverrides)) {
            Pattern pattern = Pattern.compile("(?i)^\\s*" + Pattern.quote(token.trim()) + "\\b\\s*");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                return result.substring(matcher.end()).trim();
            }
        }
        return result;
    }

    private static String stripSuffixOverrides(String sql, String suffixOverrides) {
        String result = sql == null ? "" : sql.trim();
        if (result.isBlank() || suffixOverrides == null || suffixOverrides.isBlank()) {
            return result;
        }

        for (String token : overrideTokens(suffixOverrides)) {
            Pattern pattern = Pattern.compile("\\s*" + Pattern.quote(token.trim()) + "\\s*$");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                return result.substring(0, matcher.start()).trim();
            }
        }
        return result;
    }

    private static List<String> overrideTokens(String overrides) {
        if (overrides == null || overrides.isBlank()) {
            return List.of();
        }
        if (",".equals(overrides.trim())) {
            return List.of(",");
        }
        String separator = overrides.contains("|") ? "\\|" : ",";
        List<String> tokens = new ArrayList<>();
        for (String token : overrides.split(separator, -1)) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String joinSql(List<String> parts) {
        List<String> filtered = parts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
        return String.join(" ", filtered);
    }

    private static String normalizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")")
                .trim();
    }

    private static String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(SqlNodeRenderer::sqlLiteral).reduce((a, b) -> a + ", " + b).orElse("");
        }
        if (value.getClass().isArray()) {
            List<String> parts = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                parts.add(sqlLiteral(Array.get(value, i)));
            }
            return String.join(", ", parts);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static String rawValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * A rendered SQL candidate and the parameter sample values that produced it.
     */
    public record RenderedSql(
            String sql,
            Map<String, Object> parameters,
            List<String> diagnostics) {

        public RenderedSql {
            sql = sql == null ? "" : sql;
            parameters = parameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private record IterationValue(Object index, Object value) {
    }

    private static final class RenderContext {

        private final LinkedHashMap<String, Object> values;
        private final List<String> diagnostics;

        private RenderContext(Map<String, ?> values) {
            this.values = new LinkedHashMap<>();
            if (values != null) {
                values.forEach(this::put);
            }
            this.diagnostics = new ArrayList<>();
        }

        private RenderContext(LinkedHashMap<String, Object> values, List<String> diagnostics) {
            this.values = new LinkedHashMap<>(values);
            this.diagnostics = new ArrayList<>(diagnostics);
        }

        private RenderContext copy() {
            return new RenderContext(values, diagnostics);
        }

        private void put(String name, Object value) {
            if (name != null && !name.isBlank()) {
                values.put(name.trim(), value);
            }
        }

        private Map<String, Object> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        private List<String> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private void diagnostic(String message) {
            diagnostics.add(message);
        }

        private void mergeDiagnostics(RenderContext other) {
            diagnostics.addAll(other.diagnostics);
        }

        private boolean evaluate(String expression) {
            try {
                return truthy(evaluateBoolean(stripOuterParentheses(expression == null ? "" : expression.trim())));
            } catch (RuntimeException e) {
                diagnostic("Failed to evaluate expression `" + expression + "`: " + e.getMessage());
                return false;
            }
        }

        private Object evaluateBoolean(String expression) {
            String trimmed = stripOuterParentheses(expression);
            List<String> orParts = splitByKeyword(trimmed, "or");
            if (orParts.size() > 1) {
                return orParts.stream().anyMatch(this::evaluate);
            }

            List<String> andParts = splitByKeyword(trimmed, "and");
            if (andParts.size() > 1) {
                return andParts.stream().allMatch(this::evaluate);
            }

            if (trimmed.startsWith("!")) {
                return !truthy(resolveExpression(trimmed.substring(1)));
            }

            for (String operator : List.of("!=", "==", ">=", "<=", ">", "<")) {
                int index = indexOfOperator(trimmed, operator);
                if (index >= 0) {
                    Object left = resolveExpression(trimmed.substring(0, index));
                    Object right = resolveExpression(trimmed.substring(index + operator.length()));
                    return compare(left, right, operator);
                }
            }

            return resolveExpression(trimmed);
        }

        private Object resolveExpression(String expression) {
            String trimmed = stripOuterParentheses(expression == null ? "" : expression.trim());
            if (trimmed.isEmpty()) {
                return null;
            }

            List<String> concatParts = splitByChar(trimmed, '+');
            if (concatParts.size() > 1) {
                StringBuilder builder = new StringBuilder();
                for (String part : concatParts) {
                    Object value = resolveExpression(part);
                    builder.append(value == null ? "null" : value);
                }
                return builder.toString();
            }

            if (isQuoted(trimmed)) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            if ("null".equals(trimmed)) {
                return null;
            }
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                return Boolean.parseBoolean(trimmed);
            }
            if (trimmed.matches("-?\\d+")) {
                try {
                    return Long.parseLong(trimmed);
                } catch (NumberFormatException ignored) {
                    return trimmed;
                }
            }
            if (trimmed.matches("-?\\d+\\.\\d+")) {
                try {
                    return Double.parseDouble(trimmed);
                } catch (NumberFormatException ignored) {
                    return trimmed;
                }
            }

            return resolvePath(trimmed);
        }

        private Object resolvePath(String expression) {
            String path = expression.trim();
            if (path.endsWith(".isEmpty()")) {
                Object value = resolvePath(path.substring(0, path.length() - ".isEmpty()".length()));
                return isEmpty(value);
            }
            if (path.endsWith(".size()")) {
                Object value = resolvePath(path.substring(0, path.length() - ".size()".length()));
                return sizeOf(value);
            }
            if (path.endsWith(".length()")) {
                Object value = resolvePath(path.substring(0, path.length() - ".length()".length()));
                return sizeOf(value);
            }

            String[] parts = path.split("\\.");
            Object current = values.get(parts[0]);
            if (current == null && !values.containsKey(parts[0]) && parts.length > 1) {
                current = values.get(parts[parts.length - 1]);
            }
            if (parts.length == 1) {
                return current;
            }

            for (int i = 1; i < parts.length; i++) {
                if (current == null) {
                    return null;
                }
                current = readProperty(current, parts[i]);
            }
            return current;
        }

        private Object readProperty(Object target, String property) {
            if (target instanceof Map<?, ?> map) {
                return map.get(property);
            }
            if (target instanceof List<?> list && property.matches("\\d+")) {
                int index = Integer.parseInt(property);
                return index >= 0 && index < list.size() ? list.get(index) : null;
            }
            if (target.getClass().isArray() && property.matches("\\d+")) {
                int index = Integer.parseInt(property);
                return index >= 0 && index < Array.getLength(target) ? Array.get(target, index) : null;
            }

            String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
            for (String getterName : List.of("get" + suffix, "is" + suffix)) {
                try {
                    Method method = target.getClass().getMethod(getterName);
                    return method.invoke(target);
                } catch (ReflectiveOperationException ignored) {
                }
            }
            try {
                Field field = target.getClass().getDeclaredField(property);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private boolean compare(Object left, Object right, String operator) {
            return switch (operator) {
                case "==" -> Objects.equals(normalizeNumber(left), normalizeNumber(right));
                case "!=" -> !Objects.equals(normalizeNumber(left), normalizeNumber(right));
                case ">" -> compareOrder(left, right) > 0;
                case ">=" -> compareOrder(left, right) >= 0;
                case "<" -> compareOrder(left, right) < 0;
                case "<=" -> compareOrder(left, right) <= 0;
                default -> false;
            };
        }

        private Object normalizeNumber(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            return value;
        }

        private int compareOrder(Object left, Object right) {
            if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
                return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
            }
            String leftText = left == null ? "" : String.valueOf(left);
            String rightText = right == null ? "" : String.valueOf(right);
            return leftText.compareTo(rightText);
        }

        private boolean truthy(Object value) {
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof Number number) {
                return number.doubleValue() != 0.0d;
            }
            if (value instanceof CharSequence text) {
                return !text.isEmpty();
            }
            if (value instanceof Collection<?> collection) {
                return !collection.isEmpty();
            }
            if (value instanceof Map<?, ?> map) {
                return !map.isEmpty();
            }
            if (value.getClass().isArray()) {
                return Array.getLength(value) > 0;
            }
            return true;
        }

        private boolean isEmpty(Object value) {
            return !truthy(value);
        }

        private int sizeOf(Object value) {
            if (value == null) {
                return 0;
            }
            if (value instanceof Collection<?> collection) {
                return collection.size();
            }
            if (value instanceof Map<?, ?> map) {
                return map.size();
            }
            if (value instanceof CharSequence text) {
                return text.length();
            }
            if (value.getClass().isArray()) {
                return Array.getLength(value);
            }
            return 1;
        }

        private String stripOuterParentheses(String expression) {
            String result = expression == null ? "" : expression.trim();
            while (result.startsWith("(") && result.endsWith(")") && matchingOuterParentheses(result)) {
                result = result.substring(1, result.length() - 1).trim();
            }
            return result;
        }

        private boolean matchingOuterParentheses(String expression) {
            int depth = 0;
            boolean quoted = false;
            char quote = 0;
            for (int i = 0; i < expression.length(); i++) {
                char c = expression.charAt(i);
                if (quoted) {
                    if (c == quote) {
                        quoted = false;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quoted = true;
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0 && i < expression.length() - 1) {
                        return false;
                    }
                }
            }
            return depth == 0;
        }

        private List<String> splitByKeyword(String expression, String keyword) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            boolean quoted = false;
            char quote = 0;
            int start = 0;
            String lower = expression.toLowerCase();
            for (int i = 0; i < expression.length(); i++) {
                char c = expression.charAt(i);
                if (quoted) {
                    if (c == quote) {
                        quoted = false;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quoted = true;
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && matchesKeyword(lower, i, keyword)) {
                    parts.add(expression.substring(start, i).trim());
                    i += keyword.length() - 1;
                    start = i + 1;
                }
            }
            if (start == 0) {
                return List.of(expression);
            }
            parts.add(expression.substring(start).trim());
            return parts;
        }

        private boolean matchesKeyword(String lowerExpression, int index, String keyword) {
            if (!lowerExpression.startsWith(keyword, index)) {
                return false;
            }
            int before = index - 1;
            int after = index + keyword.length();
            boolean beforeBoundary = before < 0 || Character.isWhitespace(lowerExpression.charAt(before));
            boolean afterBoundary = after >= lowerExpression.length() || Character.isWhitespace(lowerExpression.charAt(after));
            return beforeBoundary && afterBoundary;
        }

        private List<String> splitByChar(String expression, char separator) {
            List<String> parts = new ArrayList<>();
            boolean quoted = false;
            char quote = 0;
            int start = 0;
            for (int i = 0; i < expression.length(); i++) {
                char c = expression.charAt(i);
                if (quoted) {
                    if (c == quote) {
                        quoted = false;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quoted = true;
                    quote = c;
                } else if (c == separator) {
                    parts.add(expression.substring(start, i).trim());
                    start = i + 1;
                }
            }
            if (start == 0) {
                return List.of(expression);
            }
            parts.add(expression.substring(start).trim());
            return parts;
        }

        private int indexOfOperator(String expression, String operator) {
            boolean quoted = false;
            char quote = 0;
            int depth = 0;
            for (int i = 0; i <= expression.length() - operator.length(); i++) {
                char c = expression.charAt(i);
                if (quoted) {
                    if (c == quote) {
                        quoted = false;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quoted = true;
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && expression.startsWith(operator, i)) {
                    return i;
                }
            }
            return -1;
        }

        private boolean isQuoted(String value) {
            return value.length() >= 2
                    && ((value.startsWith("'") && value.endsWith("'"))
                    || (value.startsWith("\"") && value.endsWith("\"")));
        }
    }
}
