package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import java.util.*;

/** 定义与调用实参的通用关联；别名和单值回退策略由框架适配层提供。 @author u80dc */
public record TemplateSqlInput(SqlDefinition definition, Map<String, Parameter> parameters,
                               Optional<Parameter> fallback, Optional<String> parameterMapName,
                               Map<String, Object> constants, Optional<ValueTrace> sqlSource) {
    public TemplateSqlInput {
        Objects.requireNonNull(definition);
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        Objects.requireNonNull(fallback);
        Objects.requireNonNull(parameterMapName);
        constants = Collections.unmodifiableMap(new LinkedHashMap<>(constants));
        Objects.requireNonNull(sqlSource);
    }
    public TemplateSqlInput(SqlDefinition definition, Map<String, Parameter> parameters, Optional<Parameter> fallback,
                            Optional<String> parameterMapName, Map<String, Object> constants) {
        this(definition, parameters, fallback, parameterMapName, constants, Optional.empty());
    }
    public TemplateSqlInput(SqlDefinition definition, Map<String, Parameter> parameters, Optional<Parameter> fallback) {
        this(definition, parameters, fallback, Optional.empty(), Map.of(), Optional.empty());
    }
    public record Parameter(Source source, String type) {
        public Parameter { Objects.requireNonNull(source); Objects.requireNonNull(type); }
        public Parameter(ValuePoint value, String type) { this(new Source.At(value), type); }
        public Parameter(ValueTrace trace, String type) { this(new Source.Trace(trace), type); }
        public ValuePoint value() {
            if (source instanceof Source.At at) return at.value();
            throw new IllegalStateException("This template parameter contains a saved trace");
        }
        public ValueTrace trace(DataFlowAnalysis flow) {
            return switch (source) { case Source.At at -> flow.trace(at.value()); case Source.Trace saved -> saved.trace(); };
        }
    }
    public sealed interface Source {
        record At(ValuePoint value) implements Source { public At { Objects.requireNonNull(value); } }
        record Trace(ValueTrace trace) implements Source { public Trace { Objects.requireNonNull(trace); } }
    }
}
