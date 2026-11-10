package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import org.u80dc.jsqlint.core.dataflow.ControlFlowEdge;
import java.util.*;

/** 框架定位 SQL 与绑定值后，交给 core 统一追踪；一个执行点可以对应多个批次/分支。 @author u80dc */
public record SqlTraceSource(Optional<ValuePoint> sql, List<Binding> bindings,
                             Optional<SourceLocation> batchEntry, List<String> issues, List<List<ControlFlowEdge>> controlFlowPaths,
                             Optional<TemplateSqlInput> templateInput) {
    public SqlTraceSource {
        Objects.requireNonNull(sql); bindings = List.copyOf(bindings); Objects.requireNonNull(batchEntry); issues = List.copyOf(issues);
        controlFlowPaths = controlFlowPaths.stream().map(List::copyOf).toList();
        Objects.requireNonNull(templateInput);
        if (sql.isPresent() && templateInput.isPresent()) throw new IllegalArgumentException("SQL has both value and template sources");
        if (templateInput.isPresent() && !bindings.isEmpty()) throw new IllegalArgumentException("Template bindings are generated in template order");
    }
    public SqlTraceSource(Optional<ValuePoint> sql, List<Binding> bindings, Optional<SourceLocation> batchEntry,
                          List<String> issues, List<List<ControlFlowEdge>> controlFlowPaths) {
        this(sql, bindings, batchEntry, issues, controlFlowPaths, Optional.empty());
    }
    public SqlTraceSource(Optional<ValuePoint> sql, List<Binding> bindings, Optional<SourceLocation> batchEntry, List<String> issues) {
        this(sql, bindings, batchEntry, issues, List.of());
    }
    public static SqlTraceSource sql(ValuePoint value) { return new SqlTraceSource(Optional.of(value), List.of(), Optional.empty(), List.of()); }
    public static SqlTraceSource template(TemplateSqlInput input, List<String> issues) {
        return new SqlTraceSource(Optional.empty(), List.of(), Optional.empty(), issues, List.of(), Optional.of(input));
    }
    /**
     * key 可以是位置、名称或未知标识；keyInput 保留动态参数下标/名称本身的数据来源。
     * valueType 是使用位置的声明类型，可补足字节码中的 boolean/byte/short 等类型信息。
     */
    public record Binding(String key, ValuePoint keyInput, ValuePoint value, SourceLocation location, String valueType) {
        public Binding { Objects.requireNonNull(key); Objects.requireNonNull(keyInput); Objects.requireNonNull(value); Objects.requireNonNull(location); Objects.requireNonNull(valueType); }
        public Binding(String key, ValuePoint keyInput, ValuePoint value, SourceLocation location) {
            this(key, keyInput, value, location, value.value().getType().toString());
        }
    }
}
