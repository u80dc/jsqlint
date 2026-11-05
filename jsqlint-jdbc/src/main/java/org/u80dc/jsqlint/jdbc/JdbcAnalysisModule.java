package org.u80dc.jsqlint.jdbc;

import org.u80dc.jsqlint.core.spi.*;
import java.util.Set;

/**
 * 标准 JDBC SQL 调用发现；值传播和动态 SQL 构造由 core 执行。
 *
 * @author u80dc
 */
public final class JdbcAnalysisModule implements SqlAnalysisModule {
    private static final Set<String> CONNECTION = Set.of("prepareStatement", "prepareCall", "nativeSQL");
    private static final Set<String> STATEMENT = Set.of("execute", "executeQuery", "executeUpdate",
            "executeLargeUpdate", "addBatch");

    @Override
    public String id() { return "jdbc"; }

    @Override
    public void register(DiscoveryRegistry registry) {
        // 先按方法名和首参类型初筛；无 SQL 字符串参数的 PreparedStatement 执行方法不在此处理。
        registry.invocation(signature -> !signature.getParameterTypes().isEmpty()
                && signature.getParameterTypes().getFirst().toString().equals("java.lang.String")
                && (CONNECTION.contains(signature.getName()) || STATEMENT.contains(signature.getName())),
                (site, seeds) -> {
                    // 再确认 JDBC 类型关系，排除仅名称相同的方法，同时支持已解析的实现类。
                    String name = site.invocation().getMethodSignature().getName();
                    if ((CONNECTION.contains(name) && site.isDeclaredOnSubtypeOf("java.sql.Connection"))
                            || (STATEMENT.contains(name) && site.isDeclaredOnSubtypeOf("java.sql.Statement")))
                        seeds.accept(new JdbcSqlSeed(site));
                });
    }
}
