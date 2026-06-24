package cn.jnu.zhangcs.jsqlint.analysis.jdbc;

import lombok.Getter;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 原生 JDBC 污点分析目标方法枚举
 *
 * <p>定义了 java.sql 包中需要进行污点分析的方法，用于参数溯源分析。
 * 覆盖了 Connection、Statement、PreparedStatement、CallableStatement 等
 * 常用的 JDBC 接口方法。
 *
 * <h3>方法分类</h3>
 * <ul>
 *   <li><b>连接方法 (Connection)</b>：创建 Statement/PreparedStatement</li>
 *   <li><b>语句方法 (Statement)</b>：executeQuery/executeUpdate/execute</li>
 *   <li><b>预编译方法 (PreparedStatement)</b>：setXxx 参数设置</li>
 *   <li><b>存储过程方法 (CallableStatement)</b>：registerOutParameter</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 1. 根据签名匹配
 * JdbcTemplateTargetMethod method = JdbcTemplateTargetMethod.fromSignature(
 *     "&lt;java.sql.Connection: java.sql.PreparedStatement prepareStatement(java.lang.String)&gt;");
 *
 * // 2. 获取方法的参数类型
 * String sqlType = Jdbc.PREPARE_STATEMENT.getParamType(0);  // "java.lang.String"
 *
 * // 3. 检查参数语义
 * boolean isParam0Sql = Jdbc.PREPARE_STATEMENT.isSqlParam(0);  // true
 * </pre>
 *
 * @author zhangcs
 */
@Getter
public enum JdbcTargetMethod {

    // ==================== Connection 方法 ====================

    /**
     * Connection.prepareStatement(String sql)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     * </ul>
     */
    PREPARE_STATEMENT(
            "java.sql.Connection",
            "prepareStatement",
            "java.sql.PreparedStatement",
            true,   // firstParamIsSql
            "java.lang.String"
    ),

    /**
     * Connection.prepareCall(String sql)
     * <p>参数说明:
     * <ul>
     *   <li>param0: 调用存储过程的 SQL</li>
     * </ul>
     */
    PREPARE_CALL(
            "java.sql.Connection",
            "prepareCall",
            "java.sql.CallableStatement",
            true,   // firstParamIsSql
            "java.lang.String"
    ),

    /**
     * Connection.createStatement()
     * <p>创建普通 Statement，无 SQL 参数
     */
    CREATE_STATEMENT(
            "java.sql.Connection",
            "createStatement",
            "java.sql.Statement"
    ),

    // ==================== Statement 方法 ====================

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
            "java.lang.String"
    ),

    /**
     * Statement.executeUpdate(String sql)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     * </ul>
     */
    STATEMENT_UPDATE(
            "java.sql.Statement",
            "executeUpdate",
            "int",
            true,   // firstParamIsSql
            "java.lang.String"
    ),

    /**
     * Statement.execute(String sql)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     * </ul>
     */
    STATEMENT_EXECUTE(
            "java.sql.Statement",
            "execute",
            "boolean",
            true,   // firstParamIsSql
            "java.lang.String"
    ),

    /**
     * Statement.executeLargeUpdate(String sql)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     * </ul>
     */
    STATEMENT_LARGE_UPDATE(
            "java.sql.Statement",
            "executeLargeUpdate",
            "long",
            true,   // firstParamIsSql
            "java.lang.String"
    ),

    /**
     * Statement.addBatch(String sql)
     * <p>参数说明:
     * <ul>
     *   <li>param0: SQL 字符串</li>
     * </ul>
     */
    STATEMENT_ADD_BATCH(
            "java.sql.Statement",
            "addBatch",
            "void",
            true,   // firstParamIsSql
            "java.lang.String"
    ),

    // ==================== PreparedStatement 方法 ====================

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
     *   <li>param1: 设置的字符串值</li>
     * </ul>
     */
    SET_STRING(
            "java.sql.PreparedStatement",
            "setString",
            "void",
            "int",
            "java.lang.String"
    ),

    /**
     * PreparedStatement.setInt(int parameterIndex, int x)
     */
    SET_INT(
            "java.sql.PreparedStatement",
            "setInt",
            "void",
            "int",
            "int"
    ),

    /**
     * PreparedStatement.setLong(int parameterIndex, long x)
     */
    SET_LONG(
            "java.sql.PreparedStatement",
            "setLong",
            "void",
            "int",
            "long"
    ),

    /**
     * PreparedStatement.setDouble(int parameterIndex, double x)
     */
    SET_DOUBLE(
            "java.sql.PreparedStatement",
            "setDouble",
            "void",
            "int",
            "double"
    ),

    /**
     * PreparedStatement.setFloat(int parameterIndex, float x)
     */
    SET_FLOAT(
            "java.sql.PreparedStatement",
            "setFloat",
            "void",
            "int",
            "float"
    ),

    /**
     * PreparedStatement.setBoolean(int parameterIndex, boolean x)
     */
    SET_BOOLEAN(
            "java.sql.PreparedStatement",
            "setBoolean",
            "void",
            "int",
            "boolean"
    ),

    /**
     * PreparedStatement.setByte(int parameterIndex, byte x)
     */
    SET_BYTE(
            "java.sql.PreparedStatement",
            "setByte",
            "void",
            "int",
            "byte"
    ),

    /**
     * PreparedStatement.setShort(int parameterIndex, short x)
     */
    SET_SHORT(
            "java.sql.PreparedStatement",
            "setShort",
            "void",
            "int",
            "short"
    ),

    /**
     * PreparedStatement.setBigDecimal(int parameterIndex, BigDecimal x)
     */
    SET_BIG_DECIMAL(
            "java.sql.PreparedStatement",
            "setBigDecimal",
            "void",
            "int",
            "java.math.BigDecimal"
    ),

    /**
     * PreparedStatement.setDate(int parameterIndex, Date x)
     */
    SET_DATE(
            "java.sql.PreparedStatement",
            "setDate",
            "void",
            "int",
            "java.sql.Date"
    ),

    /**
     * PreparedStatement.setTime(int parameterIndex, Time x)
     */
    SET_TIME(
            "java.sql.PreparedStatement",
            "setTime",
            "void",
            "int",
            "java.sql.Time"
    ),

    /**
     * PreparedStatement.setTimestamp(int parameterIndex, Timestamp x)
     */
    SET_TIMESTAMP(
            "java.sql.PreparedStatement",
            "setTimestamp",
            "void",
            "int",
            "java.sql.Timestamp"
    ),

    /**
     * PreparedStatement.setObject(int parameterIndex, Object x)
     */
    SET_OBJECT(
            "java.sql.PreparedStatement",
            "setObject",
            "void",
            "int",
            "java.lang.Object"
    ),

    /**
     * PreparedStatement.setObject(int parameterIndex, Object x, int targetSqlType)
     */
    SET_OBJECT_WITH_TYPE(
            "java.sql.PreparedStatement",
            "setObject",
            "void",
            "int",
            "java.lang.Object",
            "int"
    ),

    /**
     * PreparedStatement.setNull(int parameterIndex, int sqlType)
     */
    SET_NULL(
            "java.sql.PreparedStatement",
            "setNull",
            "void",
            "int",
            "int"
    ),

    /**
     * PreparedStatement.setArray(int parameterIndex, Array x)
     */
    SET_ARRAY(
            "java.sql.PreparedStatement",
            "setArray",
            "void",
            "int",
            "java.sql.Array"
    ),

    /**
     * PreparedStatement.setBlob(int parameterIndex, Blob x)
     */
    SET_BLOB(
            "java.sql.PreparedStatement",
            "setBlob",
            "void",
            "int",
            "java.sql.Blob"
    ),

    /**
     * PreparedStatement.setClob(int parameterIndex, Clob x)
     */
    SET_CLOB(
            "java.sql.PreparedStatement",
            "setClob",
            "void",
            "int",
            "java.sql.Clob"
    ),

    // ==================== CallableStatement 方法 ====================

    /**
     * CallableStatement.registerOutParameter(int parameterIndex, int sqlType)
     * <p>参数说明:
     * <ul>
     *   <li>param0: 参数索引</li>
     *   <li>param1: SQL 类型</li>
     * </ul>
     */
    REGISTER_OUT_PARAMETER(
            "java.sql.CallableStatement",
            "registerOutParameter",
            "void",
            "int",
            "int"
    ),

    /**
     * CallableStatement.registerOutParameter(int parameterIndex, int sqlType, String typeName)
     */
    REGISTER_OUT_PARAMETER_WITH_NAME(
            "java.sql.CallableStatement",
            "registerOutParameter",
            "void",
            "int",
            "int",
            "java.lang.String"
    ),

    /**
     * CallableStatement.getString(int parameterIndex)
     * <p>获取输出参数值
     */
    GET_STRING(
            "java.sql.CallableStatement",
            "getString",
            "java.lang.String",
            "int"
    ),

    /**
     * CallableStatement.getInt(int parameterIndex)
     */
    GET_INT(
            "java.sql.CallableStatement",
            "getInt",
            "int",
            "int"
    ),

    /**
     * CallableStatement.getLong(int parameterIndex)
     */
    GET_LONG(
            "java.sql.CallableStatement",
            "getLong",
            "long",
            "int"
    ),

    /**
     * CallableStatement.getObject(int parameterIndex)
     */
    GET_OBJECT(
            "java.sql.CallableStatement",
            "getObject",
            "java.lang.Object",
            "int"
    ),

    ;

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
     * 参数类型数组
     */
    private final String[] paramTypes;

    // 缓存 MethodSignature
    private MethodSignature cachedSignature;

    JdbcTargetMethod(String className, String methodName, String returnType,
                     boolean firstParamIsSql, String... paramTypes) {
        this.className = className;
        this.methodName = methodName;
        this.returnType = returnType;
        this.firstParamIsSql = firstParamIsSql;
        this.paramTypes = paramTypes;
    }

    // 无参数变体的构造函数
    JdbcTargetMethod(String className, String methodName, String returnType) {
        this(className, methodName, returnType, false);
    }

    // 带参数类型的构造函数（不含 firstParamIsSql 标志）
    JdbcTargetMethod(String className, String methodName, String returnType, String... paramTypes) {
        this(className, methodName, returnType, false, paramTypes);
    }

    // ==================== 便捷方法 ====================

    /**
     * 根据方法签名查找枚举值（精确匹配 Jimple 签名）
     *
     * @param signature Jimple 格式的方法签名
     * @return 对应的枚举值，如果未找到返回 null
     */
    public static JdbcTargetMethod fromSignature(String signature) {
        if (signature == null) return null;
        for (JdbcTargetMethod method : values()) {
            if (method.getJimpleSignature().equals(signature)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 根据类名和方法名查找枚举值（模糊匹配）
     *
     * @param className  类名
     * @param methodName 方法名
     * @return 对应的枚举值，如果未找到返回 null
     */
    public static JdbcTargetMethod fromMethod(String className, String methodName) {
        if (className == null || methodName == null) return null;
        for (JdbcTargetMethod method : values()) {
            if (method.getClassName().equals(className) && method.getMethodName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 获取指定类别的所有方法
     *
     * @param className 类名
     * @return 该类的所有方法
     */
    public static List<JdbcTargetMethod> getMethodsByClass(String className) {
        if (className == null) return Collections.emptyList();
        return Arrays.stream(values())
                .filter(m -> m.getClassName().equals(className))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有 Connection 方法
     */
    public static List<JdbcTargetMethod> getConnectionMethods() {
        return getMethodsByClass("java.sql.Connection");
    }

    /**
     * 获取所有 Statement 方法
     */
    public static List<JdbcTargetMethod> getStatementMethods() {
        return getMethodsByClass("java.sql.Statement");
    }

    /**
     * 获取所有 PreparedStatement 方法
     */
    public static List<JdbcTargetMethod> getPreparedStatementMethods() {
        return getMethodsByClass("java.sql.PreparedStatement");
    }

    /**
     * 获取所有 CallableStatement 方法
     */
    public static List<JdbcTargetMethod> getCallableStatementMethods() {
        return getMethodsByClass("java.sql.CallableStatement");
    }

    /**
     * 获取所有包含 SQL 参数的方法
     */
    public static List<JdbcTargetMethod> getMethodsWithSqlParam() {
        return Arrays.stream(values())
                .filter(m -> m.firstParamIsSql)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有需要分析的方法签名
     *
     * @return 所有方法的 MethodSignature 列表
     */
    public static List<MethodSignature> list() {
        return Arrays.stream(values())
                .map(JdbcTargetMethod::getMethodSignature)
                .collect(Collectors.toList());
    }

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
     * 检查是否是查询方法
     */
    public boolean isQueryMethod() {
        return this == STATEMENT_QUERY || this == PREPARED_STATEMENT_QUERY;
    }

    /**
     * 检查是否是更新方法
     */
    public boolean isUpdateMethod() {
        return this == STATEMENT_UPDATE || this == STATEMENT_LARGE_UPDATE ||
                this == PREPARED_STATEMENT_UPDATE;
    }

    /**
     * 检查是否是执行方法
     */
    public boolean isExecuteMethod() {
        return this == STATEMENT_EXECUTE || this == PREPARED_STATEMENT_EXECUTE;
    }

    /**
     * 检查是否是 Connection 方法
     */
    public boolean isConnectionMethod() {
        return this == PREPARE_STATEMENT || this == PREPARE_CALL || this == CREATE_STATEMENT;
    }

    /**
     * 检查是否是 Statement 方法
     */
    public boolean isStatementMethod() {
        return this == STATEMENT_QUERY || this == STATEMENT_UPDATE ||
                this == STATEMENT_EXECUTE || this == STATEMENT_LARGE_UPDATE ||
                this == STATEMENT_ADD_BATCH;
    }

    /**
     * 检查是否是 PreparedStatement 方法（包括 setXxx）
     */
    public boolean isPreparedStatementMethod() {
        return this == PREPARED_STATEMENT_QUERY || this == PREPARED_STATEMENT_UPDATE ||
                this == PREPARED_STATEMENT_EXECUTE ||
                this == SET_STRING || this == SET_INT || this == SET_LONG ||
                this == SET_DOUBLE || this == SET_FLOAT || this == SET_BOOLEAN ||
                this == SET_BYTE || this == SET_SHORT || this == SET_BIG_DECIMAL ||
                this == SET_DATE || this == SET_TIME || this == SET_TIMESTAMP ||
                this == SET_OBJECT || this == SET_OBJECT_WITH_TYPE || this == SET_NULL ||
                this == SET_ARRAY || this == SET_BLOB || this == SET_CLOB;
    }

    /**
     * 检查是否是 CallableStatement 方法
     */
    public boolean isCallableStatementMethod() {
        return this == PREPARE_CALL || this == REGISTER_OUT_PARAMETER ||
                this == REGISTER_OUT_PARAMETER_WITH_NAME ||
                this == GET_STRING || this == GET_INT || this == GET_LONG || this == GET_OBJECT;
    }

    /**
     * 检查是否是参数设置方法（setXxx）
     */
    public boolean isSetterMethod() {
        return this == SET_STRING || this == SET_INT || this == SET_LONG ||
                this == SET_DOUBLE || this == SET_FLOAT || this == SET_BOOLEAN ||
                this == SET_BYTE || this == SET_SHORT || this == SET_BIG_DECIMAL ||
                this == SET_DATE || this == SET_TIME || this == SET_TIMESTAMP ||
                this == SET_OBJECT || this == SET_OBJECT_WITH_TYPE || this == SET_NULL ||
                this == SET_ARRAY || this == SET_BLOB || this == SET_CLOB;
    }

    /**
     * 获取方法分类
     */
    public String getCategory() {
        if (isConnectionMethod()) return "Connection";
        if (isStatementMethod()) return "Statement";
        if (isPreparedStatementMethod()) return isSetterMethod() ? "Setter" : "PreparedStatement";
        if (isCallableStatementMethod()) return "CallableStatement";
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
        int lastDot = type.lastIndexOf('.');
        return lastDot >= 0 ? type.substring(lastDot + 1) : type;
    }

    /**
     * 获取参数类型的简短描述
     */
    public String getParamTypesDescription() {
        if (paramTypes == null || paramTypes.length == 0) {
            return "无参数";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("param").append(i).append(": ").append(simplifyTypeName(paramTypes[i]));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return getJimpleSignature();
    }
}