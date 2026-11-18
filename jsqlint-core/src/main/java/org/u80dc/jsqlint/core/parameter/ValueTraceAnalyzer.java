package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.RawSql;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.template.SqlTemplateProjection;
import org.u80dc.jsqlint.core.template.RawSqlBuilder;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.spi.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 旧 SQL 模板入口的兼容视图，统一使用 DataFlowAnalysis 和 RawSqlBuilder。
 * 新调用方应直接使用结构化数据流与 SqlTemplateProjection，避免重复恢复 SQL。
 * @author u80dc
 */
@Deprecated
public final class ValueTraceAnalyzer {
    private final Supplier<DataFlowAnalysis> factory;
    private final Runnable checkpoint;
    private final BiConsumer<String, String> incomplete;
    private DataFlowAnalysis flow;

    public ValueTraceAnalyzer(JavaView view, Map<MethodSignature, List<InvocationSite>> callers,
                              int maxDepth, int cacheSize, Runnable checkpoint, BiConsumer<String, String> incomplete) {
        this(view, callers, maxDepth, maxDepth, 10000, cacheSize, checkpoint, incomplete);
    }

    public ValueTraceAnalyzer(JavaView view, Map<MethodSignature, List<InvocationSite>> callers,
                              int maxCallDepth, int maxDepth, int maxNodes, int cacheSize,
                              Runnable checkpoint, BiConsumer<String, String> incomplete) {
        if (maxCallDepth < 1 || maxDepth < 1 || maxNodes < 1 || cacheSize < 1)
            throw new IllegalArgumentException("Trace budgets and cache size must be positive");
        Objects.requireNonNull(view); Objects.requireNonNull(callers);
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.incomplete = Objects.requireNonNull(incomplete);
        // 保留旧构造方式；独立使用时按需创建索引，不在构造阶段加载方法体。
        factory = () -> new DataFlowAnalysis(new ProgramIndex(view,
                view.getClasses().filter(JavaSootClass::isApplicationClass).toList(),
                callers.values().stream().flatMap(Collection::stream).distinct().toList(), checkpoint),
                maxCallDepth, maxDepth, maxNodes, cacheSize, checkpoint, incomplete);
    }

    private DataFlowAnalysis flow() {
        if (flow == null) flow = factory.get();
        return flow;
    }

    public record Recovery(SqlNode template, List<String> issues) {
        public Recovery { Objects.requireNonNull(template); issues = List.copyOf(issues); }
    }

    /** 单次查询的诊断来自同一份 raw SQL，不维护第二套值传播算法。 */
    public Recovery recoverSql(SqlInput.ValueAt input, CallContext context) {
        checkpoint.run();
        var trace = flow().trace(new FlowPoint(input.method(), input.statement(), context).value(input.value()));
        RawSql raw = RawSqlBuilder.build(trace, checkpoint);
        for (String issue : raw.issues()) incomplete.accept("UNSUPPORTED_VALUE", issue);
        return new Recovery(SqlTemplateProjection.template(raw, checkpoint), raw.issues());
    }

    public SqlNode sql(SqlInput.ValueAt input) {
        List<SqlNode> templates = new ArrayList<>();
        for (var context : flow().contexts(input.method())) templates.add(recoverSql(input, context.context()).template());
        return SqlTemplateProjection.alternatives(templates);
    }

    public SqlNode sql(SqlInput.ValueAt input, CallContext context) {
        return recoverSql(input, context).template();
    }
}
