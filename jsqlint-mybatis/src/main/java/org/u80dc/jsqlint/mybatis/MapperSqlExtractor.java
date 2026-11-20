package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.ValueSqlNode;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.mybatis.spi.MapperMethodContext;

import java.util.*;

/** 将 Mapper 调用定位到 SQL 定义与命名实参，后续分析全部交给 core。 @author u80dc */
final class MapperSqlExtractor implements SqlExtractionHandler {
    private final MapperMethodMatcher methods;
    private final MapperParameterResolver parameters;
    private final MybatisOptions options;
    private final ProviderSqlExtractor providers;
    private final MapperExtensionResolver resolver;
    MapperSqlExtractor(MapperMethodMatcher methods, MybatisOptions options, MapperExtensionResolver resolver) {
        this.methods = methods; this.options = options; parameters = new MapperParameterResolver(options);
        this.resolver = resolver;
        providers = new ProviderSqlExtractor(options);
    }
    @Override public List<SqlTraceSource> extract(FlowPoint point, DataFlowAnalysis flow) {
        var site = point.invocation(flow.program().view()).orElseThrow();
        var signature = site.invocation().getMethodSignature();
        var method = methods.resolveMethod(signature, site.view()).orElseThrow();
        var context = new MapperMethodContext(signature, method, site.view());
        return extractMapper(context, point, flow);
    }
    List<SqlTraceSource> extractMapper(MapperMethodContext context, FlowPoint point, DataFlowAnalysis flow) {
        return extractMapper(context, point, flow, point.invocation(flow.program()).orElseThrow().invocation().getArgs());
    }
    List<SqlTraceSource> extractMapper(MapperMethodContext context, FlowPoint point, DataFlowAnalysis flow,
                                     List<? extends sootup.core.jimple.common.Value> arguments) {
        var site = point.invocation(flow.program().view()).orElseThrow();
        var method = context.method();
        var resolved = resolver.resolve(context);
        var definition = resolved.map(MapperExtensionResolver.Resolved::definition)
                .orElseGet(() -> new SqlDefinition(site.invocation().getMethodSignature().getDeclClassType() + "." + method.getName(),
                        site.location(), ValueSqlNode.raw("missingSqlDefinition"), new MybatisExpressionEvaluator(options), Map.of(),
                        List.of("No active MyBatis statement for " + site.invocation().getMethodSignature())));
        var input = resolved.flatMap(value -> value.extension().flatMap(extension -> extension.parameters(context, definition, point, flow, arguments)))
                .orElseGet(() -> parameters.resolve(definition, method, site.view(), index -> new TemplateSqlInput.Parameter(
                        point.value(arguments.get(index)), method.getParameterType(index).toString())));
        var sources=definition.metadata().getOrDefault("command", "").endsWith("provider")&&definition.complete()
                ?providers.extract(definition,method,input,point,flow):List.of(input);
        return resolved.flatMap(value->value.extension()).map(extension->extension.postprocess(context,sources,point,flow)).orElse(sources);
    }
}
