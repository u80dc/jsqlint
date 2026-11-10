package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.ValueSqlNode;
import org.u80dc.jsqlint.core.spi.*;

import java.util.*;

/** 将 Mapper 调用定位到 SQL 定义与命名实参，后续分析全部交给 core。 @author u80dc */
final class MapperSqlExtractor implements SqlExtractionHandler {
    private final MybatisDefinitionIndex index;
    private final MapperMethodMatcher methods;
    private final MapperParameterResolver parameters;
    private final MybatisOptions options;
    private final ProviderSqlExtractor providers;
    MapperSqlExtractor(MybatisDefinitionIndex index, MapperMethodMatcher methods, MybatisOptions options) {
        this.index = index; this.methods = methods; this.options = options; parameters = new MapperParameterResolver(options);
        providers = new ProviderSqlExtractor(options);
    }
    @Override public List<SqlTraceSource> extract(FlowPoint point, DataFlowAnalysis flow) {
        var site = point.invocation(flow.program().view()).orElseThrow();
        var method = methods.resolveMethod(site).orElseThrow();
        var definition = methods.definitionIds(site, method).stream().map(index::resolve).flatMap(Optional::stream).findFirst()
                .orElseGet(() -> new SqlDefinition(site.invocation().getMethodSignature().getDeclClassType() + "." + method.getName(),
                        site.location(), ValueSqlNode.raw("missingSqlDefinition"), new MybatisExpressionEvaluator(options), Map.of(),
                        List.of("No active MyBatis statement for " + site.invocation().getMethodSignature())));
        var input = parameters.resolve(definition, method, site, point);
        if (definition.metadata().getOrDefault("command", "").endsWith("provider") && definition.complete())
            return providers.extract(definition, method, input, point, flow);
        return List.of(input);
    }
}
