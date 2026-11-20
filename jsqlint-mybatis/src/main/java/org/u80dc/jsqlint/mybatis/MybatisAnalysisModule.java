package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.mybatis.spi.*;
import java.util.*;

/**
 * XML/注解定义和 Mapper 调用适配；索引与缓存在每次 register 时独立建立。
 * @author u80dc
 */
public final class MybatisAnalysisModule implements SqlAnalysisModule {
    private final MybatisOptions options;
    private final List<MybatisExtension> extensions;
    public MybatisAnalysisModule() { this(MybatisOptions.defaults()); }
    public MybatisAnalysisModule(MybatisOptions options) {
        this(options, ServiceLoader.load(MybatisExtension.class).stream().map(ServiceLoader.Provider::get).toList());
    }
    /** 显式扩展列表替代自动发现；空列表仅启用标准 MyBatis。 */
    public MybatisAnalysisModule(MybatisOptions options, List<? extends MybatisExtension> extensions) {
        this.options = Objects.requireNonNull(options); this.extensions = List.copyOf(extensions);
        var ids = new HashSet<String>();
        for (var extension : this.extensions)
            if (extension.id() == null || extension.id().isBlank() || !ids.add(extension.id()))
                throw new IllegalArgumentException("MyBatis extension IDs must be nonblank and unique: " + extension.id());
    }

    @Override
    public String id() { return "mybatis"; }

    @Override
    public void register(DiscoveryRegistry registry) {
        var definitions = new MybatisDefinitionIndex(options);
        var methods = new MapperMethodMatcher();
        var resolver = new MapperExtensionResolver(definitions, methods, options, extensions);
        registry.sqlMethod((method, view) -> methods.isSqlMethod(method, view)
                || resolver.resolve(new MapperMethodContext(method.getSignature(), method, view)).isPresent());
        registry.sqlInvocation(site -> methods.isSqlInvocation(site) || methods.resolveMethod(site.invocation().getMethodSignature(), site.view())
                .flatMap(method -> resolver.resolve(new MapperMethodContext(site.invocation().getMethodSignature(), method, site.view()))).isPresent());
        registry.resource(resource -> resource.name().toLowerCase(Locale.ROOT).endsWith(".xml"),
                (resource, seeds) -> definitions.resource(resource, methods));
        registry.method(method -> method.isAbstract(), (method, seeds) -> definitions.method(method));
        registry.afterDiscovery(definitions::seeds);
        registry.methodDefinition((signature, method, view, seeds) -> {
            var resolved = resolver.resolve(new MapperMethodContext(signature, method, view));
            if (resolved.isEmpty()) return;
            if (!resolved.get().generated() && method.isAbstract()) { definitions.seeds(signature, method, view, methods, seeds); return; }
            var source = resolved.get().definition();
            var metadata = new LinkedHashMap<>(source.metadata()); metadata.put("method", signature.toString());
            var definition = source.withMetadata(metadata);
            seeds.accept(new AnalysisSeed() {
                public SourceLocation location() { return definition.location(); }
                public SqlInput input() { return new SqlInput.Definition(definition); }
            });
        });
        var extractor = new MapperSqlExtractor(methods, options, resolver);
        registry.extraction(site -> {
            return methods.resolveMethod(site.invocation().getMethodSignature(), site.view())
                    .flatMap(method -> resolver.resolve(new MapperMethodContext(site.invocation().getMethodSignature(), method, site.view()))).isPresent();
        }, extractor);
        resolver.register(registry, extractor);
    }
}
