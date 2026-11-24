package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValueSnapshots;
import java.util.*;

/**
 * 定义与调用实参的通用关联；业务参数、渲染槽位和 SQL 路径来源分别保存。
 * renderParameters 只进入模板求值环境，不属于 parameterMapName 指定的业务参数对象；
 * constants 可为同名渲染槽位提供已恢复的SQL结构值，槽位仍保留Java来源。
 * sqlSource 参与联合求解和来源记录，不作为可见模板变量。
 * @author u80dc
 */
public record TemplateSqlInput(SqlDefinition definition, Map<String, Parameter> parameters,
                               Optional<Parameter> fallback, Optional<String> parameterMapName,
                               Map<String, Object> constants, Optional<ValueTrace> sqlSource,
                               Map<String, Parameter> renderParameters) {
    public TemplateSqlInput {
        Objects.requireNonNull(definition);
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        Objects.requireNonNull(fallback);
        Objects.requireNonNull(parameterMapName);
        constants = SqlValueSnapshots.parameters(constants);
        Objects.requireNonNull(sqlSource);
        renderParameters = Collections.unmodifiableMap(new LinkedHashMap<>(renderParameters));
        for (String name : renderParameters.keySet())
            if (parameters.containsKey(name) || parameterMapName.filter(name::equals).isPresent())
                throw new IllegalArgumentException("Template render parameter conflicts with a business or reserved name: " + name);
    }
    @Override public Map<String, Object> constants() { return SqlValueSnapshots.parameters(constants); }
    public TemplateSqlInput(SqlDefinition definition, Map<String, Parameter> parameters, Optional<Parameter> fallback,
                            Optional<String> parameterMapName, Map<String, Object> constants, Optional<ValueTrace> sqlSource) {
        this(definition, parameters, fallback, parameterMapName, constants, sqlSource, Map.of());
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
