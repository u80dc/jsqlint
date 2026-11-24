package legacyplugin;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.OptimisticLockerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.PaginationInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.RowBounds;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 仅使用目标依赖运行旧拦截器；Executor/JDBC 由代理记录，不连接数据库。 */
public class LegacyPluginOracle {
    public static void main(String[] arguments) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.getGlobalConfig(configuration).setDbConfig(new GlobalConfig.DbConfig());
        configuration.addMapper(Fixture.UserMapper.class);
        List<Map<String, Object>> rows = arguments[0].equals("optimistic") ? optimistic(configuration) : pagination(configuration);
        try (ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(arguments[1]))) { output.writeObject(rows); }
    }

    private static List<Map<String, Object>> optimistic(MybatisConfiguration configuration) throws Exception {
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        final MappedStatement mapped = configuration.getMappedStatement(Fixture.UserMapper.class.getName() + ".updateById");
        for (Integer version : Arrays.asList(Integer.valueOf(3), null, Integer.valueOf(Integer.MAX_VALUE))) {
            final Map<String, Object> parameters = new HashMap<String, Object>();
            parameters.put("et", Fixture.entity(version));
            final Map<String, Object> row = row("optimistic");row.put("version", version);
            row.put("before", snapshot(mapped.getBoundSql(parameters), parameters, configuration));
            Executor executor = (Executor) Proxy.newProxyInstance(Executor.class.getClassLoader(), new Class<?>[]{Executor.class}, (proxy, method, args) -> {
                if (method.getName().equals("update")) {
                    row.put("after", snapshot(((MappedStatement) args[0]).getBoundSql(args[1]), args[1], configuration));
                    Object entity = ((Map<?, ?>) args[1]).get("et");
                    row.put("protocol", entity instanceof Map ? "et-map" : "entity");
                    row.put("parameterKeys", new ArrayList<Object>(((Map<?, ?>) args[1]).keySet()));
                    row.put("entityKeys", entity instanceof Map ? new ArrayList<Object>(((Map<?, ?>) entity).keySet()) : new ArrayList<Object>());
                    return Integer.valueOf(1);
                }
                return defaultValue(method.getReturnType());
            });
            Executor intercepted = (Executor) new OptimisticLockerInterceptor().plugin(executor);
            intercepted.update(mapped, parameters);
            if (!row.containsKey("after")) throw new AssertionError("Optimistic interceptor did not reach Executor.update");
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> pagination(MybatisConfiguration configuration) throws Exception {
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        final Map<String, Object> parameters = new HashMap<String, Object>();
        Page<Fixture.User> page = new Page<Fixture.User>(2, 10);
        QueryWrapper<Fixture.User> wrapper = new QueryWrapper<Fixture.User>();wrapper.eq("name", "Alice");
        parameters.put("page", page);parameters.put("ew", wrapper);
        MappedStatement mapped = configuration.getMappedStatement(Fixture.UserMapper.class.getName() + ".selectPage");
        final BoundSql bound = mapped.getBoundSql(parameters);
        final Map<String, Object> before = snapshot(bound, parameters, configuration);
        final Connection[] connection = new Connection[1];
        final Map<String, Object>[] preparedMain = new Map[1];
        connection[0] = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement")) {
                final String sql = (String) args[0];
                final TreeMap<Integer, Object> jdbc = new TreeMap<Integer, Object>();
                final boolean count = sql.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT).startsWith("SELECTCOUNT(");
                final Map<String, Object> row = row(count ? "pagination-count" : "pagination-main");row.put("before", before);
                if (!count) preparedMain[0] = row;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (statement, operation, values) -> {
                    if (operation.getName().startsWith("set") && values != null && values.length >= 2 && values[0] instanceof Integer) {
                        jdbc.put((Integer) values[0], operation.getName().equals("setNull") ? null : values[1]);return null;
                    }
                    if (operation.getName().equals("executeQuery")) {
                        row.put("after", jdbcSnapshot(sql, jdbc, before));rows.add(row);
                        final boolean[] first = {true};
                        return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (resultSet, read, columns) -> {
                            if (read.getName().equals("next")) { boolean next=first[0];first[0]=false;return next; }
                            if (read.getName().equals("getLong")) return Long.valueOf(100);
                            return defaultValue(read.getReturnType());
                        });
                    }
                    if (operation.getName().equals("getConnection")) return connection[0];
                    return defaultValue(operation.getReturnType());
                });
            }
            if (method.getName().equals("getMetaData")) return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (metadata, read, values) -> {
                if (read.getName().equals("getURL")) return "jdbc:mysql://localhost/recording";
                if (read.getName().equals("getDatabaseProductName")) return "MySQL";
                return defaultValue(read.getReturnType());
            });
            return defaultValue(method.getReturnType());
        });
        Executor executor = (Executor) Proxy.newProxyInstance(Executor.class.getClassLoader(), new Class<?>[]{Executor.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        StatementHandler handler = new RoutingStatementHandler(executor, mapped, parameters, RowBounds.DEFAULT, null, bound);
        PaginationInterceptor interceptor = new PaginationInterceptor();
        try { interceptor.getClass().getMethod("setDbType", DbType.class).invoke(interceptor, DbType.MYSQL); }
        catch (NoSuchMethodException absent) { interceptor.setDialectType("mysql"); }
        StatementHandler intercepted = (StatementHandler) interceptor.plugin(handler);
        Statement statement = intercepted.prepare(connection[0], null);intercepted.parameterize(statement);
        if (preparedMain[0] == null) throw new AssertionError("Pagination did not prepare main statement");
        preparedMain[0].put("after", snapshot(bound, parameters, configuration));rows.add(preparedMain[0]);
        return rows;
    }

    private static Map<String, Object> row(String kind) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();row.put("kind", kind);
        row.put("frameworkVersion", BaseMapper.class.getPackage().getImplementationVersion());return row;
    }

    private static Map<String, Object> snapshot(BoundSql bound, Object parameters, MybatisConfiguration configuration) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();snapshot.put("sql", bound.getSql());
        List<Map<String, Object>> bindings = new ArrayList<Map<String, Object>>();
        for (ParameterMapping mapping : bound.getParameterMappings()) {
            Object value = bound.hasAdditionalParameter(mapping.getProperty()) ? bound.getAdditionalParameter(mapping.getProperty())
                    : parameters == null ? null : configuration.getTypeHandlerRegistry().hasTypeHandler(parameters.getClass()) ? parameters
                    : configuration.newMetaObject(parameters).getValue(mapping.getProperty());
            Map<String, Object> binding = new LinkedHashMap<String, Object>();binding.put("expression", mapping.getProperty());
            binding.put("type", mapping.getJavaType().getName());binding.put("value", value);bindings.add(binding);
        }
        snapshot.put("bindings", bindings);return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jdbcSnapshot(String sql, TreeMap<Integer, Object> jdbc, Map<String, Object> source) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();snapshot.put("sql", sql);
        List<Map<String, Object>> original = (List<Map<String, Object>>) source.get("bindings");
        List<Map<String, Object>> bindings = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Integer, Object> entry : jdbc.entrySet()) {
            Map<String, Object> binding = new LinkedHashMap<String, Object>(original.get(entry.getKey() - 1));
            binding.put("value", entry.getValue());bindings.add(binding);
        }
        snapshot.put("bindings", bindings);return snapshot;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0);return null;
    }
}
