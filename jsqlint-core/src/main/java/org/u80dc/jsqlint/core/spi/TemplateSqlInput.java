package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import java.util.*;

/** 定义与调用实参的通用关联；别名和单值回退策略由框架适配层提供。 @author u80dc */
public record TemplateSqlInput(SqlDefinition definition, Map<String, Parameter> parameters,
                               Optional<Parameter> fallback, Optional<String> parameterMapName,
                               Map<String, Object> constants) {
    public TemplateSqlInput {
        Objects.requireNonNull(definition);
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        Objects.requireNonNull(fallback);
        Objects.requireNonNull(parameterMapName);
        constants = Collections.unmodifiableMap(new LinkedHashMap<>(constants));
    }
    public TemplateSqlInput(SqlDefinition definition, Map<String, Parameter> parameters, Optional<Parameter> fallback) {
        this(definition, parameters, fallback, Optional.empty(), Map.of());
    }
    public record Parameter(ValuePoint value, String type) {
        public Parameter { Objects.requireNonNull(value); Objects.requireNonNull(type); }
    }
}
