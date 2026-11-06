package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import java.util.*;

/**
 * 从传播链恢复的 SQL 候选；命名槽位表示原始文本拼接，JDBC 的 ? 仍由 Binding 描述。
 * 候选不附带路径可行性证明，complete 只表示文本结构恢复没有未知操作或截断。
 * @author u80dc
 */
public record RawSql(List<Variant> variants, List<String> issues) {
    public RawSql { variants = List.copyOf(variants); issues = List.copyOf(issues); }
    public boolean complete() { return !variants.isEmpty() && issues.isEmpty(); }

    /** 每个候选保存自己的传播树；相同文本但不同来源的候选仍分别保留。 */
    public record Variant(String text, List<Placeholder> placeholders, Optional<ValueTrace> trace) {
        public Variant {
            Objects.requireNonNull(text); placeholders = List.copyOf(placeholders); Objects.requireNonNull(trace);
            int end = 0;
            for (var slot : placeholders) {
                if (slot.start() < end || slot.end() > text.length()
                        || !text.substring(slot.start(), slot.end()).equals("${" + slot.name() + "}"))
                    throw new IllegalArgumentException("Placeholder positions must match ordered, non-overlapping text slots");
                end = slot.end();
            }
        }

        /** 手工创建的文本候选可以不提供字节码传播来源。 */
        public Variant(String text, List<Placeholder> placeholders) { this(text, placeholders, Optional.empty()); }

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

    /** start/end 为 text 中槽位的 UTF-16 起止位置；source 保留变量转换、参数与调用来源。 */
    public record Placeholder(String name, int start, int end, ValueTrace.Node source) {
        public Placeholder {
            Objects.requireNonNull(name); Objects.requireNonNull(source);
            if (name.isBlank() || start < 0 || end <= start) throw new IllegalArgumentException("Invalid placeholder name or positions");
        }
    }
}
