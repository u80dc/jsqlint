package matrix;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在隔离 classloader 中使用目标框架的真实 Mapper proxy/SQL 注入器取得 SQL 与绑定。 */
public class RuntimeOracle {
    /** 由测试指定兼容JDK执行；只输出Bootstrap类型组成的SQL/绑定快照。 */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected snapshot output path");
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("javaVersion", System.getProperty("java.version"));
        output.put("javaHome", System.getProperty("java.home"));
        output.put("frameworkSource", com.baomidou.mybatisplus.core.mapper.BaseMapper.class
                .getProtectionDomain().getCodeSource().getLocation().toURI().toString());
        output.put("snapshots", snapshots());
        try (ObjectOutputStream stream = new ObjectOutputStream(Files.newOutputStream(Paths.get(arguments[0])))) {
            stream.writeObject(output);
        }
    }

    public static Map<String, List<Map<String, Object>>> snapshots() throws Exception {
        final MybatisConfiguration configuration = new MybatisConfiguration();
        // getGlobalConfig 在3.x各版本稳定；静态setGlobalConfig/setGlobalConfigCache名称曾变化。
        GlobalConfigUtils.getGlobalConfig(configuration).setDbConfig(new GlobalConfig.DbConfig());
        configuration.addMapper(NodeMapper.class);
        configuration.addMapper(LogicNodeMapper.class);

        final Map<String, List<Map<String, Object>>> results = new LinkedHashMap<String, List<Map<String, Object>>>();
        final String[] current = {null};
        SqlSession session = (SqlSession) Proxy.newProxyInstance(RuntimeOracle.class.getClassLoader(), new Class<?>[]{SqlSession.class},
                (proxy, method, arguments) -> {
                    String name = method.getName();
                    if (name.equals("getConfiguration")) return configuration;
                    if (name.equals("selectList") || name.equals("selectOne") || name.equals("insert") || name.equals("update") || name.equals("delete")) {
                        String statement = (String) arguments[0];
                        Object parameters = arguments.length > 1 ? arguments[1] : null;
                        MappedStatement mapped = configuration.getMappedStatement(statement);
                        BoundSql bound = mapped.getBoundSql(parameters);
                        List<Object> values = new ArrayList<Object>();
                        for (ParameterMapping mapping : bound.getParameterMappings()) {
                            Object value;
                            if (bound.hasAdditionalParameter(mapping.getProperty())) value = bound.getAdditionalParameter(mapping.getProperty());
                            else if (parameters == null) value = null;
                            else if (configuration.getTypeHandlerRegistry().hasTypeHandler(parameters.getClass())) value = parameters;
                            else value = configuration.newMetaObject(parameters).getValue(mapping.getProperty());
                            values.add(value);
                        }
                        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
                        snapshot.put("statement", statement); snapshot.put("sql", bound.getSql()); snapshot.put("bindings", values);
                        results.get(current[0]).add(snapshot);
                        if (name.equals("selectList")) return Collections.emptyList();
                        if (name.equals("selectOne")) {
                            if (statement.endsWith(".selectCount")) {
                                Class<?> resultType = mapped.getResultMaps().get(0).getType();
                                if (resultType == Long.class || resultType == long.class) return Long.valueOf(1L);
                                return Integer.valueOf(1);
                            }
                            return null;
                        }
                        return 1;
                    }
                    if (name.equals("toString")) return "matrix SqlSession";
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return proxy == arguments[0];
                    throw new UnsupportedOperationException("Unexpected SQL session operation: " + method);
                });
        Object plain = configuration.getMapper(NodeMapper.class, session);
        Object logical = configuration.getMapper(LogicNodeMapper.class, session);
        for (Method method : Fixture.class.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter != NodeMapper.class && parameter != LogicNodeMapper.class) continue;
            current[0] = method.getName(); results.put(current[0], new ArrayList<Map<String, Object>>());
            method.invoke(null, parameter == NodeMapper.class ? plain : logical);
        }
        return results;
    }
}
