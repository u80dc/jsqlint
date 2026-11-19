package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.spi.*;
import java.util.*;

/**
 * XML/注解定义和 Mapper 调用适配；索引与缓存在每次 register 时独立建立。
 * @author u80dc
 */
public final class MybatisAnalysisModule implements SqlAnalysisModule {
    private final MybatisOptions options;
    public MybatisAnalysisModule() { this(MybatisOptions.defaults()); }
    public MybatisAnalysisModule(MybatisOptions options) { this.options = Objects.requireNonNull(options); }

    @Override
    public String id() { return "mybatis"; }

    @Override
    public void register(DiscoveryRegistry registry) {
        var definitions = new MybatisDefinitionIndex(options);
        var methods = new MapperMethodMatcher();
        registry.sqlMethod(methods::isSqlMethod);
        registry.sqlInvocation(methods::isSqlInvocation);
        registry.resource(resource -> resource.name().toLowerCase(Locale.ROOT).endsWith(".xml"),
                (resource, seeds) -> definitions.resource(resource, methods));
        registry.method(method -> method.isAbstract(), (method, seeds) -> definitions.method(method));
        registry.afterDiscovery(definitions::seeds);
        registry.methodDefinition((signature, method, view, seeds) -> definitions.seeds(signature, method, view, methods, seeds));
        registry.extraction(site -> {
            if (!methods.isSqlInvocation(site)) return false;
            // 引用依赖接口上的继承注解时按需索引，不扫描依赖库全部方法体。
            methods.resolveMethod(site).ifPresent(definitions::method);
            return true;
        }, new MapperSqlExtractor(definitions, methods, options));
    }
}
