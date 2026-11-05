package org.u80dc.jsqlint.jdbc;

import org.u80dc.jsqlint.core.spi.*;
import sootup.core.signatures.MethodSignature;
import java.util.List;
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
        registry.sqlInvocation(JdbcAnalysisModule::isSqlCall);
        // 提取起点仍要求 SQL 字符串实参；无参执行调用独立报告，不伪造 SQL 参数。
        registry.invocation(JdbcAnalysisModule::hasSqlArgument,
                (site, seeds) -> {
                    if (isSqlCall(site)) seeds.accept(new JdbcSqlSeed(site));
                });
    }

    private static boolean isSqlCall(InvocationSite site) {
        MethodSignature signature = site.invocation().getMethodSignature();
        String name = signature.getName();
        if (hasSqlArgument(signature)) {
            // 类型检查同时覆盖 JDBC 接口、子接口和依赖可解析的驱动实现类。
            return site.isDeclaredOnSubtypeOf(CONNECTION.contains(name)
                    ? "java.sql.Connection" : "java.sql.Statement");
        }
        if (!signature.getParameterTypes().isEmpty()) return false;
        if (name.equals("executeBatch") || name.equals("executeLargeBatch"))
            return site.isDeclaredOnSubtypeOf("java.sql.Statement");
        return STATEMENT.contains(name) && site.isDeclaredOnSubtypeOf("java.sql.PreparedStatement");
    }

    /** 只接受 JDBC 标准重载，避免驱动扩展中碰巧同名的方法被识别为 SQL 入口。 */
    private static boolean hasSqlArgument(MethodSignature signature) {
        String name = signature.getName();
        if (!CONNECTION.contains(name) && !STATEMENT.contains(name)) return false;
        List<String> parameters = signature.getParameterTypes().stream().map(Object::toString).toList();
        if (parameters.isEmpty() || !parameters.getFirst().equals("java.lang.String")) return false;
        if (parameters.size() == 1) return true;
        if (name.equals("nativeSQL") || name.equals("executeQuery") || name.equals("addBatch")) return false;
        if (name.equals("prepareStatement") || name.equals("prepareCall")) {
            if (parameters.equals(List.of("java.lang.String", "int", "int"))
                    || parameters.equals(List.of("java.lang.String", "int", "int", "int"))) return true;
            if (name.equals("prepareCall")) return false;
        }
        return parameters.size() == 2
                && Set.of("int", "int[]", "java.lang.String[]").contains(parameters.get(1));
    }
}
