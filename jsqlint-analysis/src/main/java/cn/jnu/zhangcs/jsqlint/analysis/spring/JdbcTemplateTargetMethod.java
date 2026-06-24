package cn.jnu.zhangcs.jsqlint.analysis.spring;

import lombok.Getter;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JdbcTemplate 污点分析目标方法枚举
 *
 * <p>定义了 JdbcTemplate 中需要进行污点分析的方法，用于参数溯源分析。
 * 覆盖了 JdbcTemplate、NamedParameterJdbcTemplate、PreparedStatement、Statement 等
 * 常用的数据库操作方法。
 *
 * <h3>使用方法分类</h3>
 * <ul>
 *   <li><b>查询方法 (Query)</b>：SELECT 语句相关</li>
 *   <li><b>更新方法 (Update)</b>：INSERT/UPDATE/DELETE 语句相关</li>
 *   <li><b>执行方法 (Execute)</b>：通用 SQL 执行</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 1. 使用枚举进行参数分析
 * TaintSourceInfo result = analyzer.analyze(
 *     JdbcTemplateTargetMethod.QUERY.getMethodSignature(), 0);
 *
 * // 2. 获取方法的参数类型
 * String sqlType = JdbcTemplateTargetMethod.QUERY.getParamType(0);  // "java.lang.String"
 * String argsType = JdbcTemplateTargetMethod.QUERY.getParamType(2);  // "[Ljava.lang.Object;"
 *
 * // 3. 查找所有查询方法
 * List&lt;JdbcTemplateTargetMethod&gt; queries = JdbcTemplateTargetMethod.getQueryMethods();
 *
 * // 4. 根据签名匹配
 * JdbcTemplateTargetMethod method = JdbcTemplateTargetMethod.fromSignature(
 *     "&lt;org.springframework.jdbc.core.JdbcTemplate: java.util.List query(java.lang.String, org.springframework.jdbc.core.RowMapper, [Ljava.lang.Object;)&gt;");
 *
 * // 5. 检查参数语义
 * boolean isParam0Sql = JdbcTemplateTargetMethod.QUERY.isSqlParam(0);  // true
 * boolean isParam2Args = JdbcTemplateTargetMethod.QUERY.isArgsParam(2); // true
 * </pre>
 *
 * @author zhangcs
 */
@Getter
public enum JdbcTemplateTargetMethod {

    // ==================== JdbcTemplate query 方法 ====================

    /**
     * JdbcTemplate.query(String sql, RowMapper&lt;T&gt; rowMapper, Object... args)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: RowMapper</li>
     *   <li>param2: 查询参数数组</li>
     * </ul>
     */
    QUERY(
            "org.springframework.jdbc.core.JdbcTemplate",
            "query",
            "java.util.List",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.RowMapper",
            "[Ljava.lang.Object;"
    ),

    /**
     * JdbcTemplate.query(String sql, PreparedStatementSetter pss)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: PreparedStatementSetter</li>
     * </ul>
     */
    QUERY_PREPARED_STATEMENT_SETTER(
            "org.springframework.jdbc.core.JdbcTemplate",
            "query",
            "java.util.List",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.PreparedStatementSetter"
    ),

    /**
     * JdbcTemplate.query(String sql, Class&lt;T&gt; requiredType, Object... args)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: 返回类型</li>
     *   <li>param2: 参数数组</li>
     * </ul>
     */
    QUERY_FOR_VALUE(
            "org.springframework.jdbc.core.JdbcTemplate",
            "query",
            "java.lang.Object",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "java.lang.Class",
            "[Ljava.lang.Object;"
    ),

    /**
     * JdbcTemplate.query(String sql, RowMapper&lt;T&gt; rowMapper)
     * <p>无参数查询
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: RowMapper</li>
     * </ul>
     */
    QUERY_NO_PARAM(
            "org.springframework.jdbc.core.JdbcTemplate",
            "query",
            "java.util.List",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.RowMapper"
    ),

    /**
     * JdbcTemplate.queryForList(String sql, Object... args)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: 参数数组</li>
     * </ul>
     */
    QUERY_FOR_LIST(
            "org.springframework.jdbc.core.JdbcTemplate",
            "queryForList",
            "java.util.List",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "[Ljava.lang.Object;"
    ),

    /**
     * JdbcTemplate.queryForList(String sql, Class&lt;T&gt; elementType, Object... args)
     */
    QUERY_FOR_LIST_WITH_TYPE(
            "org.springframework.jdbc.core.JdbcTemplate",
            "queryForList",
            "java.util.List",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "java.lang.Class",
            "[Ljava.lang.Object;"
    ),

    /**
     * JdbcTemplate.queryForObject(String sql, Class&lt;T&gt; requiredType, Object... args)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: 返回类型</li>
     *   <li>param2: 参数数组</li>
     * </ul>
     */
    QUERY_FOR_OBJECT(
            "org.springframework.jdbc.core.JdbcTemplate",
            "queryForObject",
            "java.lang.Object",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "java.lang.Class",
            "[Ljava.lang.Object;"
    ),

    /**
     * JdbcTemplate.queryForObject(String sql, RowMapper&lt;T&gt; rowMapper, Object... args)
     */
    QUERY_FOR_OBJECT_WITH_MAPPER(
            "org.springframework.jdbc.core.JdbcTemplate",
            "queryForObject",
            "java.lang.Object",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.RowMapper",
            "[Ljava.lang.Object;"
    ),

    /**
     * JdbcTemplate.queryForMap(String sql, Object... args)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: 参数数组</li>
     * </ul>
     */
    QUERY_FOR_MAP(
            "org.springframework.jdbc.core.JdbcTemplate",
            "queryForMap",
            "java.util.Map",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "[Ljava.lang.Object;"
    ),

    /**
     * JdbcTemplate.queryForRowSet(String sql, Object... args)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: 参数数组</li>
     * </ul>
     */
    QUERY_FOR_ROW_SET(
            "org.springframework.jdbc.core.JdbcTemplate",
            "queryForRowSet",
            "javax.sql.RowSet",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "[Ljava.lang.Object;"
    ),

    // ==================== JdbcTemplate update/execute 方法 ====================

    /**
     * JdbcTemplate.update(String sql, Object... args)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: 参数数组</li>
     * </ul>
     */
    UPDATE(
            "org.springframework.jdbc.core.JdbcTemplate",
            "update",
            "int",
            true,   // firstParamIsSql
            true,   // hasArgsParam
            "java.lang.String",
            "[Ljava.lang.Object;"
    ),

    /**
     * JdbcTemplate.update(String sql, PreparedStatementSetter pss)
     */
    UPDATE_PREPARED_STATEMENT_SETTER(
            "org.springframework.jdbc.core.JdbcTemplate",
            "update",
            "int",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.PreparedStatementSetter"
    ),

    /**
     * JdbcTemplate.batchUpdate(String sql, List&lt;Object[]&gt; batch)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: 批量参数列表</li>
     * </ul>
     */
    BATCH_UPDATE(
            "org.springframework.jdbc.core.JdbcTemplate",
            "batchUpdate",
            "int[]",
            true,   // firstParamIsSql
            false,  // hasArgsParam (使用 List 而非 Object[])
            "java.lang.String",
            "java.util.List"
    ),

    /**
     * JdbcTemplate.batchUpdate(String... sqls)
     * <p>批量执行多条 SQL
     */
    BATCH_UPDATE_MULTIPLE(
            "org.springframework.jdbc.core.JdbcTemplate",
            "batchUpdate",
            "int[]",
            false,  // firstParamIsSql (数组元素是 SQL)
            false,  // hasArgsParam
            "[Ljava.lang.String;"
    ),

    /**
     * JdbcTemplate.execute(String sql)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     * </ul>
     */
    EXECUTE(
            "org.springframework.jdbc.core.JdbcTemplate",
            "execute",
            "void",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String"
    ),

    // ==================== PreparedStatement 相关 ====================

    /**
     * PreparedStatement.executeQuery()
     * <p>无参数，SQL 在 prepareStatement 时已经设置
     */
    PREPARED_STATEMENT_QUERY(
            "java.sql.PreparedStatement",
            "executeQuery",
            "java.sql.ResultSet"
    ),

    /**
     * PreparedStatement.executeUpdate()
     * <p>无参数
     */
    PREPARED_STATEMENT_UPDATE(
            "java.sql.PreparedStatement",
            "executeUpdate",
            "int"
    ),

    /**
     * PreparedStatement.execute()
     * <p>无参数
     */
    PREPARED_STATEMENT_EXECUTE(
            "java.sql.PreparedStatement",
            "execute",
            "boolean"
    ),

    /**
     * PreparedStatement.setString(int parameterIndex, String x)
     * <p>参数说明:
     * <ul>
     *   <li>param0: 参数索引</li>
     *   <li>param1: 设置的值</li>
     * </ul>
     */
    PREPARED_STATEMENT_SET_STRING(
            "java.sql.PreparedStatement",
            "setString",
            "void",
            false,  // firstParamIsSql (参数索引不是 SQL)
            false,  // hasArgsParam
            "int",
            "java.lang.String"
    ),

    /**
     * PreparedStatement.setObject(int parameterIndex, Object x)
     */
    PREPARED_STATEMENT_SET_OBJECT(
            "java.sql.PreparedStatement",
            "setObject",
            "void",
            false,  // firstParamIsSql
            false,  // hasArgsParam
            "int",
            "java.lang.Object"
    ),

    // ==================== Statement 相关 ====================

    /**
     * Statement.executeQuery(String sql)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     * </ul>
     */
    STATEMENT_QUERY(
            "java.sql.Statement",
            "executeQuery",
            "java.sql.ResultSet",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String"
    ),

    /**
     * Statement.execute(String sql)
     */
    STATEMENT_EXECUTE(
            "java.sql.Statement",
            "execute",
            "boolean",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String"
    ),

    /**
     * Statement.executeUpdate(String sql)
     */
    STATEMENT_UPDATE(
            "java.sql.Statement",
            "executeUpdate",
            "int",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String"
    ),

    /**
     * Statement.executeLargeUpdate(String sql)
     * <p>用于大数据的更新操作
     */
    STATEMENT_LARGE_UPDATE(
            "java.sql.Statement",
            "executeLargeUpdate",
            "long",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String"
    ),

    // ==================== NamedParameterJdbcTemplate ====================

    /**
     * NamedParameterJdbcTemplate.query(String sql, SqlParameterSource paramSource, RowMapper&lt;T&gt; rowMapper)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串 (命名参数格式，如 :id, :name)</li>
     *   <li>param1: 参数源</li>
     *   <li>param2: RowMapper</li>
     * </ul>
     */
    NAMED_QUERY(
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "query",
            "java.util.List",
            true,   // firstParamIsSql
            false,  // hasArgsParam (使用 SqlParameterSource)
            "java.lang.String",
            "org.springframework.jdbc.core.namedparam.SqlParameterSource",
            "org.springframework.jdbc.core.RowMapper"
    ),

    /**
     * NamedParameterJdbcTemplate.queryForObject(String sql, SqlParameterSource paramSource, Class&lt;T&gt; requiredType)
     */
    NAMED_QUERY_FOR_OBJECT(
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "queryForObject",
            "java.lang.Object",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.namedparam.SqlParameterSource",
            "java.lang.Class"
    ),

    /**
     * NamedParameterJdbcTemplate.queryForMap(String sql, SqlParameterSource paramSource)
     */
    NAMED_QUERY_FOR_MAP(
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "queryForMap",
            "java.util.Map",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.namedparam.SqlParameterSource"
    ),

    /**
     * NamedParameterJdbcTemplate.update(String sql, SqlParameterSource paramSource)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     *   <li>param1: 参数源</li>
     * </ul>
     */
    NAMED_UPDATE(
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "update",
            "int",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.namedparam.SqlParameterSource"
    ),

    /**
     * NamedParameterJdbcTemplate.execute(String sql, SqlParameterSource paramSource)
     */
    NAMED_EXECUTE(
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "execute",
            "java.lang.Object",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.namedparam.SqlParameterSource"
    ),

    /**
     * NamedParameterJdbcTemplate.batchUpdate(String sql, Map&lt;String,?&gt;[] batchValues)
     */
    NAMED_BATCH_UPDATE(
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "batchUpdate",
            "int[]",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "[Ljava.util.Map;"
    ),

    /**
     * NamedParameterJdbcTemplate.batchUpdate(String sql, SqlParameterSourceBatch batch)
     */
    NAMED_BATCH_UPDATE_SOURCE(
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "batchUpdate",
            "int[]",
            true,   // firstParamIsSql
            false,  // hasArgsParam
            "java.lang.String",
            "org.springframework.jdbc.core.namedparam.SqlParameterSourceBatch"
    );

    // ==================== 枚举字段 ====================

    /**
     * 全限定类名
     */
    private final String className;

    /**
     * 方法名
     */
    private final String methodName;

    /**
     * 返回类型
     */
    private final String returnType;

    /**
     * 第一个参数是否为 SQL 字符串（用于快速判断）
     */
    private final boolean firstParamIsSql;

    /**
     * 是否存在参数数组（Object... args）
     */
    private final boolean hasArgsParam;

    /**
     * 参数类型数组
     */
    private final String[] paramTypes;

    // 缓存 MethodSignature
    private MethodSignature cachedSignature;

    // 缓存参数描述
    private String cachedParamDescription;

    // 缓存简单参数描述
    private String cachedSimpleParamDescription;

    JdbcTemplateTargetMethod(String className, String methodName, String returnType,
                             boolean firstParamIsSql, boolean hasArgsParam, String... paramTypes) {
        this.className = className;
        this.methodName = methodName;
        this.returnType = returnType;
        this.firstParamIsSql = firstParamIsSql;
        this.hasArgsParam = hasArgsParam;
        this.paramTypes = paramTypes;
    }

    // 无参数变体的构造函数
    JdbcTemplateTargetMethod(String className, String methodName, String returnType) {
        this(className, methodName, returnType, false, false);
    }

    // ==================== 便捷方法 ====================

    /**
     * 根据方法签名查找枚举值（精确匹配 Jimple 签名）
     *
     * @param signature Jimple 格式的方法签名
     * @return 对应的枚举值，如果未找到返回 null
     */
    public static JdbcTemplateTargetMethod fromSignature(String signature) {
        if (signature == null) return null;
        for (JdbcTemplateTargetMethod method : values()) {
            if (method.getJimpleSignature().equals(signature)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 根据 SootUp MethodSignature 查找枚举值
     *
     * @param methodSignature SootUp 方法签名
     * @return 对应的枚举值，如果未找到返回 null
     */
    public static JdbcTemplateTargetMethod fromMethodSignature(MethodSignature methodSignature) {
        if (methodSignature == null) return null;
        String declClass = methodSignature.getDeclClassType().getFullyQualifiedName();
        String methodName = methodSignature.getName();
        return fromMethod(declClass, methodName);
    }

    /**
     * 根据类名和方法名查找枚举值（模糊匹配）
     *
     * @param className  类名
     * @param methodName 方法名
     * @return 对应的枚举值，如果未找到返回 null
     */
    public static JdbcTemplateTargetMethod fromMethod(String className, String methodName) {
        if (className == null || methodName == null) return null;
        for (JdbcTemplateTargetMethod method : values()) {
            if (method.getClassName().equals(className) && method.getMethodName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 根据完整类名和方法名查找枚举值
     *
     * @param fullyQualifiedClassName 全限定类名
     * @param methodName              方法名
     * @return 对应的枚举值，如果未找到返回 null
     */
    public static JdbcTemplateTargetMethod fromFullyQualifiedMethod(String fullyQualifiedClassName, String methodName) {
        return fromMethod(fullyQualifiedClassName, methodName);
    }

    /**
     * 获取所有查询方法
     */
    public static List<JdbcTemplateTargetMethod> getQueryMethods() {
        return Arrays.stream(values())
                .filter(JdbcTemplateTargetMethod::isQueryMethod)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有更新方法
     */
    public static List<JdbcTemplateTargetMethod> getUpdateMethods() {
        return Arrays.stream(values())
                .filter(JdbcTemplateTargetMethod::isUpdateMethod)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有执行方法
     */
    public static List<JdbcTemplateTargetMethod> getExecuteMethods() {
        return Arrays.stream(values())
                .filter(JdbcTemplateTargetMethod::isExecuteMethod)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有批量操作方法
     */
    public static List<JdbcTemplateTargetMethod> getBatchMethods() {
        return Arrays.stream(values())
                .filter(JdbcTemplateTargetMethod::isBatchMethod)
                .collect(Collectors.toList());
    }

    /**
     * 获取指定类别的所有方法
     *
     * @param className 类名
     * @return 该类的所有方法
     */
    public static List<JdbcTemplateTargetMethod> getMethodsByClass(String className) {
        if (className == null) return Collections.emptyList();
        return Arrays.stream(values())
                .filter(m -> m.getClassName().equals(className))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有 JdbcTemplate 方法
     */
    public static List<JdbcTemplateTargetMethod> getJdbcTemplateMethods() {
        return getMethodsByClass("org.springframework.jdbc.core.JdbcTemplate");
    }

    /**
     * 获取所有 NamedParameterJdbcTemplate 方法
     */
    public static List<JdbcTemplateTargetMethod> getNamedParameterJdbcTemplateMethods() {
        return getMethodsByClass("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate");
    }

    /**
     * 获取所有包含 SQL 参数的方法
     */
    public static List<JdbcTemplateTargetMethod> getMethodsWithSqlParam() {
        return Arrays.stream(values())
                .filter(m -> m.firstParamIsSql)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有包含参数数组的方法
     */
    public static List<JdbcTemplateTargetMethod> getMethodsWithArgsParam() {
        return Arrays.stream(values())
                .filter(JdbcTemplateTargetMethod::isHasArgsParam)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有需要分析的方法签名
     *
     * @return 所有方法的 MethodSignature 列表
     */
    public static List<MethodSignature> list() {
        return Arrays.stream(values())
                .map(JdbcTemplateTargetMethod::getMethodSignature)
                .collect(Collectors.toList());
    }

//    /**
//     * 获取所有需要分析的 Jimple 签名
//     *
//     * @return 所有方法的 Jimple 签名集合
//     */
//    public static Set<String> getAllJimpleSignatures() {
//        return Arrays.stream(values())
//                .map(JdbcTemplateTargetMethod::getJimpleSignature)
//                .collect(Collectors.toSet());
//    }

    /**
     * 获取完整的 Jimple 方法签名
     * <p>格式: &lt;class.Name: returnType methodName(param1, param2)&gt;
     */
    public String getJimpleSignature() {
        return JavaSootUtils.getJimpleSignature(className, methodName, returnType, paramTypes);
    }

    /**
     * 获取 SootUp MethodSignature
     */
    public MethodSignature getMethodSignature() {
        if (cachedSignature == null) {
            cachedSignature = JavaSootUtils.getMethodSignature(className, methodName, returnType, paramTypes);
        }
        return cachedSignature;
    }

    /**
     * 获取参数数量
     */
    public int getParamCount() {
        return paramTypes != null ? paramTypes.length : 0;
    }

    /**
     * 获取指定索引的参数类型
     *
     * @param index 参数索引
     * @return 参数类型，如果索引无效则返回 null
     */
    public String getParamType(int index) {
        if (paramTypes == null || index < 0 || index >= paramTypes.length) {
            return null;
        }
        return paramTypes[index];
    }

    /**
     * 检查指定参数是否是 SQL 字符串
     *
     * @param index 参数索引
     * @return 如果是 SQL 字符串返回 true
     */
    public boolean isSqlParam(int index) {
        return index == 0 && firstParamIsSql;
    }

    /**
     * 检查指定参数是否是参数数组
     *
     * @param index 参数索引
     * @return 如果是参数数组返回 true
     */
    public boolean isArgsParam(int index) {
        if (!hasArgsParam) return false;
        // 找到数组类型的索引
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] != null && paramTypes[i].startsWith("[")) {
                return i == index;
            }
        }
        return false;
    }

    /**
     * 检查是否是查询方法
     */
    public boolean isQueryMethod() {
        return this == QUERY || this == QUERY_NO_PARAM || this == QUERY_PREPARED_STATEMENT_SETTER ||
                this == QUERY_FOR_VALUE || this == QUERY_FOR_LIST || this == QUERY_FOR_LIST_WITH_TYPE ||
                this == QUERY_FOR_OBJECT || this == QUERY_FOR_OBJECT_WITH_MAPPER ||
                this == QUERY_FOR_MAP || this == QUERY_FOR_ROW_SET ||
                this == NAMED_QUERY || this == NAMED_QUERY_FOR_OBJECT || this == NAMED_QUERY_FOR_MAP ||
                this == STATEMENT_QUERY || this == PREPARED_STATEMENT_QUERY;
    }

    /**
     * 检查是否是更新方法
     */
    public boolean isUpdateMethod() {
        return this == UPDATE || this == UPDATE_PREPARED_STATEMENT_SETTER ||
                this == BATCH_UPDATE || this == BATCH_UPDATE_MULTIPLE ||
                this == NAMED_UPDATE || this == NAMED_BATCH_UPDATE || this == NAMED_BATCH_UPDATE_SOURCE ||
                this == STATEMENT_UPDATE || this == STATEMENT_LARGE_UPDATE ||
                this == PREPARED_STATEMENT_UPDATE;
    }

    /**
     * 检查是否是执行方法
     */
    public boolean isExecuteMethod() {
        return this == EXECUTE || this == NAMED_EXECUTE ||
                this == STATEMENT_EXECUTE || this == PREPARED_STATEMENT_EXECUTE;
    }

    /**
     * 检查是否是批量操作方法
     */
    public boolean isBatchMethod() {
        return this == BATCH_UPDATE || this == BATCH_UPDATE_MULTIPLE ||
                this == NAMED_BATCH_UPDATE || this == NAMED_BATCH_UPDATE_SOURCE;
    }

    /**
     * 检查是否是 PreparedStatement 相关方法
     */
    public boolean isPreparedStatementMethod() {
        return this == PREPARED_STATEMENT_QUERY || this == PREPARED_STATEMENT_UPDATE ||
                this == PREPARED_STATEMENT_EXECUTE || this == PREPARED_STATEMENT_SET_STRING ||
                this == PREPARED_STATEMENT_SET_OBJECT;
    }

    /**
     * 检查是否是 Statement 相关方法
     */
    public boolean isStatementMethod() {
        return this == STATEMENT_QUERY || this == STATEMENT_EXECUTE ||
                this == STATEMENT_UPDATE || this == STATEMENT_LARGE_UPDATE;
    }

    /**
     * 检查是否是 NamedParameterJdbcTemplate 方法
     */
    public boolean isNamedParameterMethod() {
        return this == NAMED_QUERY || this == NAMED_QUERY_FOR_OBJECT ||
                this == NAMED_QUERY_FOR_MAP || this == NAMED_UPDATE ||
                this == NAMED_EXECUTE || this == NAMED_BATCH_UPDATE ||
                this == NAMED_BATCH_UPDATE_SOURCE;
    }

    /**
     * 获取参数类型的简短描述
     * <p>格式: "param0: String, param1: RowMapper, param2: Object[]"
     */
    public String getParamTypesDescription() {
        if (cachedSimpleParamDescription != null) {
            return cachedSimpleParamDescription;
        }
        if (paramTypes == null || paramTypes.length == 0) {
            cachedSimpleParamDescription = "无参数";
            return cachedSimpleParamDescription;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("param").append(i).append(": ").append(simplifyTypeName(paramTypes[i]));
        }
        cachedSimpleParamDescription = sb.toString();
        return cachedSimpleParamDescription;
    }

    /**
     * 获取详细的参数描述，包含语义信息
     * <p>格式: "param0: String (SQL), param2: Object[] (参数数组)"
     */
    public String getDetailedParamDescription() {
        if (cachedParamDescription != null) {
            return cachedParamDescription;
        }
        if (paramTypes == null || paramTypes.length == 0) {
            cachedParamDescription = "无参数";
            return cachedParamDescription;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            String typeName = simplifyTypeName(paramTypes[i]);
            String semantic = getParamSemantic(i);
            sb.append("param").append(i).append(": ").append(typeName);
            if (semantic != null) {
                sb.append(" (").append(semantic).append(")");
            }
        }
        cachedParamDescription = sb.toString();
        return cachedParamDescription;
    }

    /**
     * 获取参数的语义描述
     */
    private String getParamSemantic(int index) {
        if (index == 0 && firstParamIsSql) {
            return "SQL";
        }
        if (index == 1 && (this == QUERY || this == QUERY_NO_PARAM)) {
            return "RowMapper";
        }
        if (index == 1 && this == QUERY_PREPARED_STATEMENT_SETTER) {
            return "PreparedStatementSetter";
        }
        if (index == 1 && this == UPDATE_PREPARED_STATEMENT_SETTER) {
            return "PreparedStatementSetter";
        }
        if (this == QUERY_FOR_OBJECT && index == 1) {
            return "返回类型";
        }
        if (this == QUERY_FOR_OBJECT_WITH_MAPPER && index == 1) {
            return "RowMapper";
        }
        if (isArgsParam(index)) {
            return "参数数组";
        }
        if (isNamedParameterMethod() && index == 1) {
            return "SqlParameterSource";
        }
        return null;
    }

    /**
     * 获取方法分类
     */
    public String getCategory() {
        if (isQueryMethod()) return "查询";
        if (isUpdateMethod()) return "更新";
        if (isExecuteMethod()) return "执行";
        if (isBatchMethod()) return "批量";
        if (isPreparedStatementMethod()) return "PreparedStatement";
        if (isStatementMethod()) return "Statement";
        if (isNamedParameterMethod()) return "命名参数";
        return "其他";
    }

    /**
     * 简化类型名称（去掉包名）
     */
    private String simplifyTypeName(String type) {
        if (type == null) return "null";
        if (type.startsWith("java.lang.") || type.startsWith("javax.")) {
            int lastDot = type.lastIndexOf('.');
            return lastDot >= 0 ? type.substring(lastDot + 1) : type;
        }
        // 处理数组类型
        if (type.startsWith("[L")) {
            String component = type.substring(2, type.length() - 1);
            int lastDot = component.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? component.substring(lastDot + 1) : component;
            return simpleName + "[]";
        }
        if (type.startsWith("[")) {
            String component = type.substring(1);
            String simpleComponent = component.equals("L") ? "Object" : component;
            return simpleComponent + "[]";
        }
        int lastDot = type.lastIndexOf('.');
        return lastDot >= 0 ? type.substring(lastDot + 1) : type;
    }

    @Override
    public String toString() {
        return getJimpleSignature();
    }
}
