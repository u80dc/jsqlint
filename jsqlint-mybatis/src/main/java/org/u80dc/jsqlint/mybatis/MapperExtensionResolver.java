package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.node.ValueSqlNode;
import org.u80dc.jsqlint.mybatis.spi.*;
import java.util.*;

/** 会话内统一选择显式定义与扩展定义，不在不同模块中重复注册 Mapper 提取规则。 */
final class MapperExtensionResolver {
    private final MybatisDefinitionIndex index;
    private final MapperMethodMatcher methods;
    private final List<Registered> extensions;
    private final Map<sootup.core.signatures.MethodSignature, Optional<Resolved>> cache = new HashMap<>();

    MapperExtensionResolver(MybatisDefinitionIndex index, MapperMethodMatcher methods, MybatisOptions options, List<MybatisExtension> factories) {
        this.index = index; this.methods = methods;
        extensions = factories.stream().map(extension -> new Registered(extension.id(),
                Objects.requireNonNull(extension.createSession(options), "extension session"))).toList();
    }

    Optional<Resolved> resolve(MapperMethodContext context) {
        return cache.computeIfAbsent(context.signature(), ignored -> {
            if (!context.view().getClass(context.method().getDeclClassType()).map(sootup.java.core.JavaSootClass::isInterface).orElse(false)
                    || context.method().isStatic() || context.method().isPrivate()) return Optional.empty();
            var matches = extensions.stream().filter(extension -> extension.session().supports(context)).toList();
            if (context.method().isAbstract() || !matches.isEmpty()) {
                index.method(context.method());
                var alias=matches.size()==1?matches.getFirst().session().statementAlias(context):Optional.<String>empty();
                var explicit = methods.definitionIds(context.signature(), context.method(), context.view()).stream()
                        .map(id->alias.map(name->id.substring(0,id.lastIndexOf('.')+1)+name).orElse(id))
                        .map(index::resolve).flatMap(Optional::stream).findFirst();
                if (explicit.isPresent()) return Optional.of(new Resolved(explicit.get(), matches.size() == 1
                        ? Optional.of(matches.getFirst().session()) : Optional.empty(), false));
            }
            if (matches.isEmpty()) return Optional.empty();
            if (matches.size() > 1) {
                var location = new org.u80dc.jsqlint.core.spi.SourceLocation(java.net.URI.create("java:" + context.signature().getDeclClassType()), context.signature().toString());
                return Optional.of(new Resolved(new SqlDefinition(context.signature().getDeclClassType() + "." + context.method().getName(),
                        location, ValueSqlNode.raw("ambiguousMapperExtension"), org.u80dc.jsqlint.core.node.SqlExpressionEvaluator.simple(),
                        Map.of(), List.of("Ambiguous MyBatis extensions: " + matches.stream().map(Registered::id).toList())), Optional.empty(), true));
            }
            var extension = matches.getFirst();
            return extension.session().definition(context).map(definition -> new Resolved(definition, Optional.of(extension.session()), true));
        });
    }
    void register(org.u80dc.jsqlint.core.spi.DiscoveryRegistry registry, MapperSqlExtractor extractor) {
        var access = new MybatisExtension.MapperAccess() {
            public Optional<SqlDefinition> definition(MapperMethodContext context) { return resolve(context).map(Resolved::definition); }
            public List<org.u80dc.jsqlint.core.spi.SqlTraceSource> extract(MapperMethodContext context,
                    org.u80dc.jsqlint.core.dataflow.FlowPoint point, org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis flow) {
                return extractor.extractMapper(context, point, flow);
            }
            public List<org.u80dc.jsqlint.core.spi.SqlTraceSource> extract(MapperMethodContext context,
                    org.u80dc.jsqlint.core.dataflow.FlowPoint point, org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis flow,
                    List<? extends sootup.core.jimple.common.Value> arguments) { return extractor.extractMapper(context, point, flow, arguments); }
        };
        extensions.forEach(extension -> extension.session().register(registry, access));
    }

    record Resolved(SqlDefinition definition, Optional<MybatisExtension.Session> extension, boolean generated) {}
    private record Registered(String id, MybatisExtension.Session session) {}
}
