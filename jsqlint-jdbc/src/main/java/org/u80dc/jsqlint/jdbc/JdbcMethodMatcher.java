package org.u80dc.jsqlint.jdbc;

import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.signatures.MethodSignature;

import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

/**
 * JDBC 入口和状态追踪共用的标准签名目录；只读取运行 JDK 的接口元数据，不加载目标项目类。
 * 方法名、参数类型和声明类型继承关系共同匹配，返回类型不参与匹配，以支持协变返回。
 * @author u80dc
 */
final class JdbcMethodMatcher {
    private JdbcMethodMatcher() {}

    enum Operation {
        PREPARE_SQL, CREATE_STATEMENT, SQL_ARGUMENT, EXECUTE, ADD_BATCH, EXECUTE_BATCH,
        CLEAR_PARAMETERS, CLEAR_BATCH, SET_PARAMETER, SET_NULL
    }

    record Rule(Operation operation, String receiverType, int sqlArgument, int keyArgument, int valueArgument) {
        boolean hasSqlArgument() { return sqlArgument >= 0; }
        boolean factory() { return operation == Operation.PREPARE_SQL || operation == Operation.CREATE_STATEMENT; }
        boolean setter() { return operation == Operation.SET_PARAMETER || operation == Operation.SET_NULL; }
        boolean sqlCall() {
            return switch (operation) {
                case PREPARE_SQL, SQL_ARGUMENT, EXECUTE, ADD_BATCH, EXECUTE_BATCH -> true;
                default -> false;
            };
        }
        boolean batchOperation() {
            return operation == Operation.ADD_BATCH || operation == Operation.CLEAR_BATCH || operation == Operation.EXECUTE_BATCH;
        }
    }

    private record Signature(String name, List<String> parameters) {
        static Signature of(MethodSignature method) {
            return new Signature(method.getName(), method.getParameterTypes().stream().map(Object::toString).toList());
        }
        static Signature of(Method method) {
            return new Signature(method.getName(), Arrays.stream(method.getParameterTypes()).map(Class::getTypeName).toList());
        }
    }

    private static final Set<String> EXECUTIONS = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate");
    private static final Map<Signature, List<Rule>> RULES = rules();

    static Optional<Rule> match(InvocationSite site) {
        if (!(site.invocation() instanceof AbstractInstanceInvokeExpr)) return Optional.empty();
        return RULES.getOrDefault(Signature.of(site.invocation().getMethodSignature()), List.of()).stream()
                .filter(rule -> site.isDeclaredOnSubtypeOf(rule.receiverType())).findFirst();
    }

    /** 兼容发现 SPI 的签名初筛；处理器仍须用 match 检查声明类型和实例调用。 */
    static boolean hasSqlArgument(MethodSignature signature) {
        return RULES.getOrDefault(Signature.of(signature), List.of()).stream().anyMatch(Rule::hasSqlArgument);
    }

    private static Map<Signature, List<Rule>> rules() {
        var rules = new LinkedHashMap<Signature, List<Rule>>();
        for (Class<?> api : List.of(Connection.class, Statement.class, PreparedStatement.class, CallableStatement.class)) {
            for (Method method : api.getDeclaredMethods()) {
                Rule rule = rule(api, method);
                if (rule != null) rules.computeIfAbsent(Signature.of(method), ignored -> new ArrayList<>()).add(rule);
            }
        }
        rules.replaceAll((signature, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(rules);
    }

    private static Rule rule(Class<?> api, Method method) {
        String name = method.getName();
        if (api == Connection.class) {
            return switch (name) {
                case "prepareStatement", "prepareCall" -> new Rule(Operation.PREPARE_SQL, api.getName(), 0, -1, -1);
                case "createStatement" -> new Rule(Operation.CREATE_STATEMENT, api.getName(), -1, -1, -1);
                case "nativeSQL" -> new Rule(Operation.SQL_ARGUMENT, api.getName(), 0, -1, -1);
                default -> null;
            };
        }
        if (api == Statement.class) {
            if (name.equals("executeBatch") || name.equals("executeLargeBatch"))
                return new Rule(Operation.EXECUTE_BATCH, api.getName(), -1, -1, -1);
            if (name.equals("clearBatch")) return new Rule(Operation.CLEAR_BATCH, api.getName(), -1, -1, -1);
            if (method.getParameterCount() > 0 && method.getParameterTypes()[0] == String.class) {
                if (name.equals("addBatch")) return new Rule(Operation.ADD_BATCH, api.getName(), 0, -1, -1);
                if (EXECUTIONS.contains(name)) return new Rule(Operation.SQL_ARGUMENT, api.getName(), 0, -1, -1);
            }
            return null;
        }
        if (api == PreparedStatement.class && method.getParameterCount() == 0) {
            if (name.equals("clearParameters")) return new Rule(Operation.CLEAR_PARAMETERS, api.getName(), -1, -1, -1);
            if (name.equals("addBatch")) return new Rule(Operation.ADD_BATCH, api.getName(), -1, -1, -1);
            if (EXECUTIONS.contains(name)) return new Rule(Operation.EXECUTE, api.getName(), -1, -1, -1);
        }
        Class<?> keyType = api == PreparedStatement.class ? int.class : String.class;
        if (name.startsWith("set") && method.getParameterCount() >= 2 && method.getParameterTypes()[0] == keyType)
            return new Rule(name.equals("setNull") ? Operation.SET_NULL : Operation.SET_PARAMETER,
                    api.getName(), -1, 0, name.equals("setNull") ? -1 : 1);
        return null;
    }
}
