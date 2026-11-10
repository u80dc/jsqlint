package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.RawSql;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferencer;
import java.util.*;

/** 从已恢复的候选派生模板视图；只折叠已知输入，不为兼容输出生成随机值。 @author u80dc */
public final class SqlTemplateProjection {
    private SqlTemplateProjection() {}

    public static SqlNode template(RawSql raw, Runnable checkpoint) {
        var inference = new ParameterValueInferencer(
                (type, name) -> new SqlValue.Unknown("External value retained symbolically"), checkpoint);
        List<SqlNode> alternatives = new ArrayList<>();
        for (var variant : raw.variants()) {
            checkpoint.run();
            List<SqlNode> parts = new ArrayList<>();
            int start = 0;
            for (var slot : variant.placeholders()) {
                parts.add(new StaticTextSqlNode(variant.text().substring(start, slot.start())));
                var trace = new ValueTrace(slot.source().variable(),
                        variant.trace().map(ValueTrace::callChain).orElse(List.of()), slot.source());
                var values = inference.infer(trace);
                // SQL 槽位是转换后的字符串；原始 null 或非字符串 SQL 不能被折叠成有效文本。
                if (values.complete() && values.possibleValues().size() == 1
                        && values.possibleValues().getFirst().value() instanceof SqlValue.Known known
                        && known.value() instanceof String text) parts.add(new StaticTextSqlNode(text));
                else parts.add(ValueSqlNode.raw(slot.name(), "null"));
                start = slot.end();
            }
            parts.add(new StaticTextSqlNode(variant.text().substring(start)));
            alternatives.add(parts.stream().allMatch(StaticTextSqlNode.class::isInstance)
                    ? new StaticTextSqlNode(parts.stream().map(StaticTextSqlNode.class::cast)
                        .map(StaticTextSqlNode::text).reduce("", String::concat))
                    : new MixedSqlNode(parts));
        }
        return alternatives(alternatives);
    }

    /** 选择标识仅展示候选关系；实际控制条件保存在联合求解结果中。 */
    public static SqlNode alternatives(Collection<? extends SqlNode> nodes) {
        var distinct = nodes.stream().distinct().toList();
        if (distinct.isEmpty()) return ValueSqlNode.raw("unresolvedSql");
        if (distinct.size() == 1) return distinct.getFirst();
        List<IfSqlNode> branches = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) branches.add(new IfSqlNode("choice_0 == " + i, distinct.get(i)));
        return new ChooseSqlNode(branches, null);
    }
}
