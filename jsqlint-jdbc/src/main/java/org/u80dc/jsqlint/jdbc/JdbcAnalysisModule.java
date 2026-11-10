package org.u80dc.jsqlint.jdbc;

import org.u80dc.jsqlint.core.spi.*;

/**
 * 标准 JDBC SQL 调用发现；值传播和动态 SQL 构造由 core 执行。
 *
 * @author u80dc
 */
public final class JdbcAnalysisModule implements SqlAnalysisModule {
    @Override
    public String id() { return "jdbc"; }

    @Override
    public void register(DiscoveryRegistry registry) {
        registry.sqlInvocation(JdbcAnalysisModule::isSqlCall);
        registry.extraction(JdbcAnalysisModule::isSqlCall, new JdbcSqlExtractor());
        // 提取起点仍要求 SQL 字符串实参；无参执行调用独立报告，不伪造 SQL 参数。
        registry.invocation(JdbcMethodMatcher::hasSqlArgument,
                (site, seeds) -> {
                    if (isSqlCall(site)) seeds.accept(new JdbcSqlSeed(site));
                });
    }

    private static boolean isSqlCall(InvocationSite site) {
        return JdbcMethodMatcher.match(site).map(JdbcMethodMatcher.Rule::sqlCall).orElse(false);
    }
}
