package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import java.util.*;

/**
 * 从传播链恢复的 SQL 候选；命名槽位表示原始文本拼接，JDBC 的 ? 仍由 Binding 描述。
 * complete 只表示文本结构恢复没有未知操作或截断；联合参数与条件求解状态见 Variant.jointInference。
 * @author u80dc
 */
public record RawSql(List<Variant> variants, List<String> issues) {
    public RawSql { variants = List.copyOf(variants); issues = List.copyOf(issues); }
    public boolean complete() { return !variants.isEmpty() && issues.isEmpty(); }

    /** 在语句结果组装时关联同一调用上下文/批次的绑定快照，不按 SQL 文本查找或合并参数。 */
    RawSql withBindings(List<SqlExtraction.Binding> bindings) {
        var snapshot = List.copyOf(bindings);
        return new RawSql(variants.stream().map(variant -> new Variant(
                variant.text(), variant.placeholders(), variant.trace(), snapshot, variant.jointInference())).toList(), issues);
    }

    /**
     * 每个候选保存自己的传播树、独立参数推断和联合求解结果；相同文本但不同来源仍分别保留。
     * 联合求解为 UNSATISFIABLE 的原始候选也保留，供审计剔除原因；手工构造或尚未求解时为 Optional.empty。
     */
    public record Variant(String text, List<Placeholder> placeholders, Optional<ValueTrace> trace,
                          List<SqlExtraction.Binding> bindings, Optional<JointSqlInferenceResult> jointInference) {
        public Variant {
            Objects.requireNonNull(text); placeholders = List.copyOf(placeholders); Objects.requireNonNull(trace);
            bindings = List.copyOf(bindings);
            Objects.requireNonNull(jointInference);
            int end = 0;
            for (var slot : placeholders) {
                if (slot.start() < end || slot.end() > text.length()
                        || !text.substring(slot.start(), slot.end()).equals("${" + slot.name() + "}"))
                    throw new IllegalArgumentException("Placeholder positions must match ordered, non-overlapping text slots");
                end = slot.end();
            }
        }

        public Variant(String text, List<Placeholder> placeholders, Optional<ValueTrace> trace, List<SqlExtraction.Binding> bindings) {
            this(text, placeholders, trace, bindings, Optional.empty());
        }

        /** raw SQL 构造阶段尚未关联绑定参数，语句结果组装时统一补入。 */
        public Variant(String text, List<Placeholder> placeholders, Optional<ValueTrace> trace) {
            this(text, placeholders, trace, List.of());
        }

        /** 手工创建的文本候选可以不提供字节码传播来源。 */
        public Variant(String text, List<Placeholder> placeholders) { this(text, placeholders, Optional.empty()); }

        /** 按槽位名读取拼接参数推断，同一个参数在文本中重复出现时只返回一次；预编译参数见 bindings。 */
        public List<ParameterValueInferenceResult> parameters() {
            var parameters = new LinkedHashMap<String, ParameterValueInferenceResult>();
            placeholders.forEach(slot -> parameters.putIfAbsent(slot.name(), slot.inference()));
            return List.copyOf(parameters.values());
        }

        /** 当前候选的拼接参数和绑定参数是否均已完成推断，不表示路径在实际运行中可行。 */
        public boolean inferenceComplete() {
            return parameters().stream().allMatch(ParameterValueInferenceResult::complete)
                    && bindings.stream().allMatch(binding -> binding.inference().complete());
        }

        /**
         * 通过槽位位置构造通用动态节点，不误替换原始 SQL 字面量中的 ${...} 文本。
         * 样例值应是 source 所描述转换之后的文本；此方法不执行 Java 方法，也不自动转义/加引号。
         */
        public SqlNode template() {
            List<SqlNode> nodes = new ArrayList<>();
            int start = 0;
            for (Placeholder slot : placeholders) {
                nodes.add(new StaticTextSqlNode(text.substring(start, slot.start())));
                nodes.add(ValueSqlNode.raw(slot.name(), "null"));
                start = slot.end();
            }
            nodes.add(new StaticTextSqlNode(text.substring(start)));
            return new MixedSqlNode(nodes);
        }
    }

    /** start/end 为 text 中槽位的 UTF-16 起止位置；source 保留来源，inference 保存转换后的参数候选值。 */
    public record Placeholder(String name, int start, int end, ValueTrace.Node source, ParameterValueInferenceResult inference) {
        public Placeholder {
            Objects.requireNonNull(name); Objects.requireNonNull(source); Objects.requireNonNull(inference);
            if (name.isBlank() || start < 0 || end <= start) throw new IllegalArgumentException("Invalid placeholder name or positions");
        }
        public Placeholder(String name, int start, int end, ValueTrace.Node source) {
            this(name, start, end, source, new ParameterValueInferenceResult(name, source.variable().type(), List.of(), List.of("Parameter not inferred")));
        }
    }
}
