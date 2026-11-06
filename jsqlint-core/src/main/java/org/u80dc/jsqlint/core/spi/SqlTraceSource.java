package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import java.util.*;

/** 框架定位 SQL 与绑定值后，交给 core 统一追踪；一个执行点可以对应多个批次/分支。 @author u80dc */
public record SqlTraceSource(Optional<ValuePoint> sql, List<Binding> bindings,
                             Optional<SourceLocation> batchEntry, List<String> issues) {
    public SqlTraceSource {
        Objects.requireNonNull(sql); bindings = List.copyOf(bindings); Objects.requireNonNull(batchEntry); issues = List.copyOf(issues);
    }
    public static SqlTraceSource sql(ValuePoint value) { return new SqlTraceSource(Optional.of(value), List.of(), Optional.empty(), List.of()); }
    /** key 可以是位置、名称或未知标识；keyInput 保留动态参数下标/名称本身的数据来源。 */
    public record Binding(String key, ValuePoint keyInput, ValuePoint value, SourceLocation location) {
        public Binding { Objects.requireNonNull(key); Objects.requireNonNull(keyInput); Objects.requireNonNull(value); Objects.requireNonNull(location); }
    }
}
