package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.RawSql;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** 消费结构化传播链生成 raw SQL，不重新扫描方法体，也不解析 Jimple 文本中的字符串转义。 @author u80dc */
public final class RawSqlBuilder {
    private static final int MAX_VARIANTS = 128;
    private static final int MAX_LENGTH = 1_000_000;
    private enum Use { SQL, FRAGMENT, STRINGIFIED }
    /** 计算中间片段时保留已选中的依赖根，拼接时按操作数顺序组合，不能只按文本去重。 */
    private record Fragment(String text, List<RawSql.Placeholder> placeholders, List<Node> sources) {
        private Fragment {
            placeholders = List.copyOf(placeholders); sources = List.copyOf(sources);
        }
        private Fragment(String text, List<RawSql.Placeholder> placeholders) { this(text, placeholders, List.of()); }
        private Fragment withSources(List<Node> sources) { return new Fragment(text, placeholders, sources); }
    }
    private final Runnable checkpoint;
    private final Map<Node, String> names = new LinkedHashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Set<String> issues = new LinkedHashSet<>();
    private int steps;

    private RawSqlBuilder(Runnable checkpoint) { this.checkpoint = checkpoint; }

    public static RawSql build(ValueTrace trace, Runnable checkpoint) {
        var builder = new RawSqlBuilder(checkpoint);
        var variants = builder.visit(trace.root(), "", Use.SQL, 0);
        return new RawSql(variants.stream().map(variant -> builder.nameSlots(variant, trace)).distinct().toList(),
                new ArrayList<>(builder.issues));
    }

    private List<Fragment> visit(Node node, String hint, Use use, int depth) {
        checkpoint.run();
        var fragments = ++steps > 10000 || depth > 256
                ? unknown(node, hint, "Raw SQL reconstruction budget exceeded") : compute(node, hint, use, depth);
        // 已展开的分支只保留选中的输入；未展开的符号值/未知值保留原始依赖，避免伪造已知来源。
        return fragments.stream().map(fragment -> fragment.withSources(List.of(fragment.sources().isEmpty() ? node
                : new Node(node.variable(), node.kind(), node.detail(), fragment.sources(), node.computation())))).toList();
    }

    private List<Fragment> compute(Node node, String hint, Use use, int depth) {
        String local = localName(node);
        if (node.kind() == Kind.DEFINITION && local != null) hint = local;
        // 保留具名参数，即使某次调用已传入常量，也能用同一个 raw SQL 构造其他样例。
        if (node.kind() == Kind.PARAMETER && node.variable().type().equals("java.lang.String")
                && (use == Use.SQL || hasStructure(node, 0))) return alternatives(node.inputs(), hint, use, depth);
        if (node.kind() == Kind.PARAMETER || node.kind() == Kind.ENTRY || node.kind() == Kind.NO_CALLER)
            return placeholder(node, parameterName(node, hint));
        return switch (node.computation().operation()) {
            case LITERAL -> {
                if (use != Use.STRINGIFIED && !node.variable().type().equals("java.lang.String"))
                    yield unknown(node, hint, "SQL root is not a string");
                if (node.computation().text().length() > MAX_LENGTH) yield unknown(node, hint, "Raw SQL length limit exceeded");
                yield literal(node.computation().text());
            }
            case NULL -> use == Use.STRINGIFIED ? literal("null") : unknown(node, hint, "SQL string is null");
            case IDENTITY -> node.inputs().size() == 1 ? visit(node.inputs().getFirst(), hint, use, depth + 1)
                    : unknown(node, hint, "Invalid identity computation");
            case STRINGIFY -> stringify(node, hint, depth);
            case ALTERNATIVES -> node.inputs().isEmpty() ? unknown(node, hint, "No SQL value alternatives")
                    : alternatives(node.inputs(), hint, use, depth);
            case CONCAT -> concat(node, hint, depth);
            case REPLACE -> replace(node, hint, depth);
            case ARRAY_READ -> {
                List<Node> sources = node.inputs().subList(Math.min(1, node.inputs().size()), node.inputs().size());
                if (sources.isEmpty()) yield unknown(node, hint, "Array element source is unavailable");
                // 外部数组元素使用元素槽位；同一次查询中的候选写入和不确定性诊断仍全部保留。
                sources = sources.stream().map(source -> source.kind() == Kind.ENTRY || source.kind() == Kind.NO_CALLER
                        ? new Node(node.variable(), Kind.NO_CALLER, "external array element", node.inputs(), node.computation())
                        : source).toList();
                var variants = alternatives(sources, hint.isEmpty() ? arrayName(node) : hint, use, depth);
                // ARRAY_READ 的第一个输入始终是下标；选择元素值时仍保存下标的独立依赖。
                yield variants.stream().map(variant -> variant.withSources(join(
                        node.inputs().subList(0, Math.min(1, node.inputs().size())), variant.sources()))).toList();
            }
            case OPAQUE -> unknown(node, chooseName(node, hint), "Unmodeled SQL value: " + node.detail());
        };
    }

    private boolean hasStructure(Node node, int depth) {
        checkpoint.run();
        if (++steps > 10000 || depth > 128) {
            issues.add("Raw SQL structure inspection budget exceeded");
            return false;
        }
        if (node.computation().operation() == Operation.CONCAT || node.computation().operation() == Operation.REPLACE) return true;
        if (node.computation().operation() == Operation.IDENTITY || node.computation().operation() == Operation.ALTERNATIVES)
            return node.inputs().stream().anyMatch(child -> hasStructure(child, depth + 1));
        return false;
    }

    private String parameterName(Node node, String hint) {
        // 辅助方法中的形参优先使用调用方具名实参，避免 quote(value) 抹掉 email 等业务名。
        if (node.kind() == Kind.PARAMETER && node.inputs().size() == 1) {
            String callerName = localName(node.inputs().getFirst());
            if (callerName != null) return callerName;
        }
        if (!hint.isEmpty()) return hint;
        String expression = node.variable().expression();
        if (expression.startsWith("@parameter")) return "parameter" + expression.substring(10, expression.indexOf(':'));
        return hint;
    }

    private List<Fragment> replace(Node node, String hint, int depth) {
        if (node.inputs().size() != 3 || node.inputs().get(1).computation().operation() != Operation.LITERAL
                || node.inputs().get(2).computation().operation() != Operation.LITERAL)
            return unknown(node, hint, "Unsupported replacement operands");
        String target = node.inputs().get(1).computation().text(), replacement = node.inputs().get(2).computation().text();
        List<Fragment> result = new ArrayList<>();
        for (var base : visit(node.inputs().getFirst(), hint, Use.FRAGMENT, depth + 1)) {
            if (base.placeholders().isEmpty()) {
                String replaced = boundedReplace(base.text(), target, replacement);
                if (replaced == null) return unknown(node, hint, "Raw SQL length limit exceeded");
                result.add(new Fragment(replaced, List.of(), replacementSources(node, base)));
                continue;
            }
            // 单字符替换可按片段分配；多字符匹配可能跨越槽位边界，必须保留为整体未知转换。
            if (target.length() != 1) return unknown(node, chooseName(node, hint), "Replacement may cross placeholder boundaries");
            StringBuilder text = new StringBuilder();
            List<RawSql.Placeholder> slots = new ArrayList<>();
            int start = 0;
            for (var slot : base.placeholders()) {
                String replaced = boundedReplace(base.text().substring(start, slot.start()), target, replacement);
                if (replaced == null || (long) text.length() + replaced.length() + slot.end() - slot.start() > MAX_LENGTH)
                    return unknown(node, hint, "Raw SQL length limit exceeded");
                text.append(replaced);
                int offset = text.length();
                text.append("${").append(slot.name()).append('}');
                var source = new Node(slot.source().variable(), Kind.EXPRESSION, "String.replace on substituted value",
                        List.of(slot.source(), node.inputs().get(1), node.inputs().get(2)), node.computation());
                slots.add(new RawSql.Placeholder(slot.name(), offset, text.length(), source));
                start = slot.end();
            }
            String replaced = boundedReplace(base.text().substring(start), target, replacement);
            if (replaced == null || (long) text.length() + replaced.length() > MAX_LENGTH)
                return unknown(node, hint, "Raw SQL length limit exceeded");
            text.append(replaced);
            result.add(new Fragment(text.toString(), slots, replacementSources(node, base)));
        }
        return result;
    }

    private List<Node> replacementSources(Node node, Fragment base) {
        return join(base.sources(), node.inputs().subList(1, 3));
    }

    private static <T> List<T> join(List<T> left, List<T> right) {
        var result = new ArrayList<>(left); result.addAll(right); return List.copyOf(result);
    }

    /** 先计算替换后的长度，避免一个很短的调用表达式在分析期生成巨量字符串。 */
    private String boundedReplace(String text, String target, String replacement) {
        long count = 0;
        if (target.isEmpty()) count = (long) text.length() + 1;
        else if (replacement.length() > target.length()) {
            for (int offset = text.indexOf(target); offset >= 0; offset = text.indexOf(target, offset + target.length())) {
                checkpoint.run();
                count++;
            }
        }
        if (text.length() + count * (replacement.length() - target.length()) > MAX_LENGTH) return null;
        return text.replace(target, replacement);
    }

    private List<Fragment> stringify(Node node, String hint, int depth) {
        if (node.inputs().size() != 1) return unknown(node, hint, "Invalid string conversion");
        Node value = node.inputs().getFirst();
        var variants = visit(value, hint, Use.STRINGIFIED, depth + 1);
        if (!Set.of("char", "boolean").contains(node.computation().text())) return variants;
        List<Fragment> converted = new ArrayList<>();
        for (var variant : variants) {
            if (!variant.placeholders().isEmpty()) { converted.add(variant); continue; }
            String text = variant.text();
            try {
                String convertedText = node.computation().text().equals("char")
                        ? Character.toString((char) Integer.parseInt(text))
                        : Boolean.toString(!text.equals("0") && !text.equals("false"));
                converted.add(new Fragment(convertedText, List.of(), variant.sources()));
            } catch (NumberFormatException ignored) { return unknown(node, hint, "Invalid primitive string conversion"); }
        }
        return converted;
    }

    private List<Fragment> concat(Node node, String hint, int depth) {
        List<Fragment> result = literal("");
        for (Node child : node.inputs()) {
            var parts = visit(child, hint, Use.FRAGMENT, depth + 1);
            List<Fragment> next = new ArrayList<>();
            outer: for (var prefix : result) for (var suffix : parts) {
                checkpoint.run();
                if (next.size() >= MAX_VARIANTS) { issues.add("Raw SQL variant limit exceeded"); break outer; }
                if ((long) prefix.text().length() + suffix.text().length() > MAX_LENGTH) {
                    return unknown(node, hint, "Raw SQL length limit exceeded");
                }
                var slots = new ArrayList<>(prefix.placeholders());
                int offset = prefix.text().length();
                suffix.placeholders().forEach(slot -> slots.add(new RawSql.Placeholder(slot.name(),
                        offset + slot.start(), offset + slot.end(), slot.source())));
                next.add(new Fragment(prefix.text() + suffix.text(), slots, join(prefix.sources(), suffix.sources())));
            }
            result = next.stream().distinct().toList();
        }
        return result;
    }

    private List<Fragment> alternatives(List<Node> inputs, String hint, Use use, int depth) {
        List<Fragment> result = new ArrayList<>();
        for (Node child : inputs) for (var variant : visit(child, hint, use, depth + 1)) {
            if (!result.contains(variant)) {
                if (result.size() >= MAX_VARIANTS) {
                    issues.add("Raw SQL variant limit exceeded");
                    return List.copyOf(result);
                }
                result.add(variant);
            }
        }
        return List.copyOf(result);
    }

    private List<Fragment> unknown(Node node, String hint, String reason) {
        issues.add(reason);
        return placeholder(node, hint);
    }

    private List<Fragment> placeholder(Node node, String hint) {
        if (hint.isEmpty()) hint = "sql";
        String text = "${" + hint + "}";
        return List.of(new Fragment(text, List.of(new RawSql.Placeholder(hint, 0, text.length(), node))));
    }

    /** 在转换完成后统一分配名字，避免同一变量的原值和 replace 后的值意外共用一个槽位。 */
    private RawSql.Variant nameSlots(Fragment variant, ValueTrace original) {
        StringBuilder text = new StringBuilder();
        List<RawSql.Placeholder> slots = new ArrayList<>();
        int start = 0;
        for (var slot : variant.placeholders()) {
            checkpoint.run();
            text.append(variant.text(), start, slot.start());
            String name = name(slot.source(), slot.name());
            int offset = text.length();
            text.append("${").append(name).append('}');
            slots.add(new RawSql.Placeholder(name, offset, text.length(), slot.source()));
            start = slot.end();
        }
        text.append(variant.text(), start, variant.text().length());
        var trace = new ValueTrace(original.variable(), original.callChain(), variant.sources().getFirst());
        return new RawSql.Variant(text.toString(), slots, Optional.of(trace));
    }

    private String name(Node node, String hint) {
        String name = names.get(node);
        if (name == null) {
            String base = hint.replaceAll("[^\\p{L}\\p{N}_]", "_");
            if (base.isBlank() || !Character.isJavaIdentifierStart(base.charAt(0))) base = "value_" + base;
            name = base;
            int suffix = 2;
            while (!usedNames.add(name)) name = base + "_" + suffix++;
            names.put(node, name);
        }
        return name;
    }

    private static List<Fragment> literal(String text) { return List.of(new Fragment(text, List.of())); }

    private static String localName(Node node) {
        if (node.kind() != Kind.DEFINITION) return null;
        String expression = node.variable().expression();
        return expression.matches("[\\p{L}_][\\p{L}\\p{N}_]*") && !expression.matches("(?:stack|l|r)\\d+") ? expression : null;
    }

    private static String arrayName(Node node) { return node.variable().expression().replace('[', '_').replace("]", ""); }

    private String chooseName(Node node, String hint) {
        if (!hint.isEmpty()) return hint;
        Deque<Node> pending = new ArrayDeque<>(node.inputs());
        int count = 0;
        while (!pending.isEmpty() && count++ < 256) {
            Node candidate = pending.removeFirst();
            String name = localName(candidate);
            if (candidate.kind() == Kind.DEFINITION && name != null) return name;
            pending.addAll(candidate.inputs());
        }
        return hint;
    }
}
