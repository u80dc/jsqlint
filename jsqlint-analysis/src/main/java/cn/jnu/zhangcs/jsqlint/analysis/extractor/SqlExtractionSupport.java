package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.SourceLocation;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.jdbc.JdbcTargetMethod;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeBuilder;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeRenderer;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ValueModelStringResolver;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModelResolver;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.AnnotationUsage;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SQL 提取通用工具集
 * <p>
 * 为各 {@link SqlExtractor} 提供共用的提取与构造能力，包括调用点扫描、SQL 值解析、
 * 语句构造、语句类型识别、注解取值、诊断信息聚合等。所有方法均为静态，不可实例化。
 * </p>
 *
 * @author zhangcs
 */
public final class SqlExtractionSupport {

    private static final ValueModelStringResolver STRING_VALUE_RESOLVER = new ValueModelStringResolver();

    private SqlExtractionSupport() {
    }

    /**
     * 扫描应用类中所有匹配 {@code targetMatcher} 的调用点，提取对应的 SQL 语句。
     * <p>
     * 仅处理应用类（{@link SootClass#isApplicationClass()}），遍历其每个方法的语句，
     * 借助 {@code targetMatcher} 判断调用是否为目标 SQL 调用点。
     * 匹配到目标但 SQL 文本无法解析、或语句无法构造的调用点不会静默丢弃，
     * 而是记入结果诊断，便于定位遗漏。
     * </p>
     *
     * @param context       共享分析上下文
     * @param targetMatcher 方法签名到 SQL 调用目标的匹配器
     * @return 提取结果
     */
    public static SqlExtractionResult extractCallSites(
            SqlExtractorContext context,
            Function<MethodSignature, Optional<SqlCallTarget>> targetMatcher) {

        List<SqlStatement> statements = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        context.view()
                .getClasses()
                .filter(SootClass::isApplicationClass)
                .forEach(clazz -> clazz.getMethods().forEach(method ->
                        extractCallSites(method, targetMatcher, statements, diagnostics)));
        return new SqlExtractionResult(statements, diagnostics);
    }

    /**
     * 扫描单个方法中的语句，提取匹配目标调用点的 SQL 语句。
     *
     * <p>除 SQL 文本实参自身的参数依赖外，还会归并同一语句对象上
     * {@code setXxx(index, value)} 绑定调用的实参依赖：常量绑定的值来源为
     * {@link cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.InferredValueOrigin#LITERAL LITERAL}，
     * 依赖调用者形参的为 EXTERNAL_INPUT，二者统一进入参数推断结果。</p>
     *
     * @param method        当前方法
     * @param targetMatcher 方法签名到 SQL 调用目标的匹配器
     * @param statements    用于收集提取结果
     * @param diagnostics   用于收集被跳过调用点的诊断
     */
    private static void extractCallSites(
            SootMethod method,
            Function<MethodSignature, Optional<SqlCallTarget>> targetMatcher,
            List<SqlStatement> statements,
            List<String> diagnostics) {

        if (!method.hasBody()) {
            return;
        }

        List<Stmt> stmts = method.getBody().getStmts();
        ValueModelResolver resolver = new ValueModelResolver(method);
        for (int i = 0; i < stmts.size(); i++) {
            Stmt stmt = stmts.get(i);
            Optional<AbstractInvokeExpr> invokeOptional = invokeOf(stmt);
            if (invokeOptional.isEmpty()) {
                continue;
            }

            AbstractInvokeExpr invoke = invokeOptional.get();
            Optional<SqlCallTarget> targetOptional = targetMatcher.apply(invoke.getMethodSignature());
            if (targetOptional == null || targetOptional.isEmpty()) {
                continue;
            }

            SqlCallTarget target = targetOptional.get();
            if (invoke.getArgCount() <= target.sqlArgumentIndex()) {
                continue;
            }

            Optional<SqlValue> sqlValue = resolveSqlValue(
                    resolver,
                    invoke,
                    target.sqlArgumentIndex(),
                    i);
            if (sqlValue.isEmpty()) {
                diagnostics.add("跳过SQL调用点（SQL文本无法解析为字符串）: "
                        + method.getSignature() + " @语句" + i);
                continue;
            }

            // 归并绑定调用（setXxx）的记录：prepareStatement 返回的语句局部变量上
            // 的后续 set 调用，其参数下标与绑定值随语句传递，供组装阶段合并推断
            List<BoundParameter> boundParameters = collectBoundParameters(resolver, stmts, invoke, i);

            SqlStatement statement = buildCallSiteStatement(
                    method,
                    stmt,
                    i,
                    invoke,
                    target,
                    sqlValue.get(),
                    boundParameters);
            if (statement != null) {
                statements.add(statement);
            } else {
                diagnostics.add("跳过SQL调用点（无法识别SQL语句）: "
                        + method.getSignature() + " @语句" + i
                        + ": " + abbreviate(sqlValue.get().rawSql()));
            }
        }
    }

    /**
     * 将 SQL 文本截断为单行短串，避免诊断信息过长。
     */
    private static String abbreviate(String sql) {
        if (sql == null) {
            return "";
        }
        String flattened = sql.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 80 ? flattened : flattened.substring(0, 80) + "...";
    }

    /**
     * 收集语句对象上的绑定调用（{@code setXxx(index, value)}）信息。
     *
     * <p>语句对象是 SQL 调用的<b>返回值</b>（{@code l3 = connection.prepareStatement(...)} 赋予
     * 的局部变量），set 调用发生在该局部变量上。每个绑定返回一条记录：
     * SQL 参数下标、绑定值模型、绑定值依赖的调用者形参。常量绑定（无依赖）同样记录，
     * 由组装阶段生成 origin 为 BIND_CONSTANT 的轻量推断。</p>
     */
    private static List<BoundParameter> collectBoundParameters(
            ValueModelResolver resolver,
            List<Stmt> stmts,
            AbstractInvokeExpr sqlInvoke,
            int sqlIndex) {

        // SQL 调用所在语句为赋值型（l3 = invoke ...）时，语句对象即左侧局部变量；
        // 直接调用型（Statement.executeQuery）无返回值可跟踪
        if (!(stmts.get(sqlIndex) instanceof JAssignStmt assign)
                || !(assign.getLeftOp() instanceof Local statementLocal)) {
            return List.of();
        }
        String statementLocalName = statementLocal.getName();

        List<BoundParameter> bound = new ArrayList<>();
        for (int i = sqlIndex + 1; i < stmts.size(); i++) {
            Optional<AbstractInvokeExpr> next = invokeOf(stmts.get(i));
            if (next.isEmpty()) {
                continue;
            }
            AbstractInvokeExpr candidate = next.get();
            if (!(candidate instanceof AbstractInstanceInvokeExpr instanceInvoke)
                    || !(instanceInvoke.getBase() instanceof Local base)
                    || !base.getName().equals(statementLocalName)) {
                continue;
            }

            JdbcTargetMethod bindMethod = JdbcTargetMethod.fromMethod(
                    candidate.getMethodSignature().getDeclClassType().getFullyQualifiedName(),
                    candidate.getMethodSignature().getName());
            if (bindMethod == null || !bindMethod.isSetterMethod()
                    || candidate.getArgCount() < 2) {
                continue;
            }

            // 第 0 参为参数下标常量（int），第 1 参为绑定值
            Integer placeholderIndex = constantInt(candidate.getArg(0));
            if (placeholderIndex == null || placeholderIndex < 1) {
                continue;
            }

            Value boundValue = candidate.getArg(1);
            ValueModel model = resolver.resolve(boundValue, i);
            List<MethodParameterRef> dependencies = model.parameterDependencies().stream()
                    .map(dependency -> MethodParameterRef.of(
                            resolver.methodSignature(),
                            dependency.index(),
                            dependency.name()))
                    .filter(MethodParameterRef::isValid)
                    .toList();
            bound.add(new BoundParameter(
                    placeholderIndex,
                    model,
                    dependencies,
                    resolver.methodSignature(),
                    candidate.getMethodSignature().getName()));
        }
        return bound;
    }

    private static Integer constantInt(Value value) {
        if (value instanceof sootup.core.jimple.common.constant.IntConstant intConstant) {
            return intConstant.getValue();
        }
        return null;
    }

    /**
     * 语句对象上一次 setXxx 绑定的记录。
     *
     * @param parameterIndex SQL 参数下标（第几个 ? 占位符，从 1 开始）
     * @param value          绑定值的结构化模型（常量或含形参依赖的表达式）
     * @param dependencies   绑定值依赖的调用者形参；常量绑定为空列表
     * @param bindCaller     绑定调用所在方法签名
     * @param bindMethod     绑定方法名（如 setLong），用于展示
     */
    public record BoundParameter(
            int parameterIndex,
            ValueModel value,
            List<MethodParameterRef> dependencies,
            sootup.core.signatures.MethodSignature bindCaller,
            String bindMethod) {

        public BoundParameter {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            bindMethod = bindMethod == null || bindMethod.isBlank() ? "bind" : bindMethod;
        }

        /** 该绑定是否为常量（无任何形参依赖）。 */
        public boolean isConstant() {
            return dependencies.isEmpty();
        }
    }

    /**
     * 提取语句中的调用表达式。仅处理 {@link JAssignStmt}（赋值型调用）与 {@link JInvokeStmt}（直接调用）。
     *
     * @param stmt 语句
     * @return 调用表达式；非调用语句返回 {@link Optional#empty()}
     */
    public static Optional<AbstractInvokeExpr> invokeOf(Stmt stmt) {
        if (stmt instanceof JAssignStmt assign) {
            return assign.getInvokeExpr();
        }
        if (stmt instanceof JInvokeStmt invokeStmt) {
            return invokeStmt.getInvokeExpr();
        }
        return Optional.empty();
    }

    /**
     * 解析调用点指定位置的 SQL 参数值。
     * <p>
     * 先用 {@link ValueModelResolver} 求出参数值的数据流模型，再通过字符串解析器渲染为 SQL 文本，
     * 同时记录该参数对应的 {@link MethodParameterRef}。
     * </p>
     *
     * @param resolver         值模型解析器
     * @param invoke           调用表达式
     * @param sqlArgumentIndex SQL 文本所在的参数下标
     * @param statementIndex   语句在方法中的下标
     * @return 解析出的 SQL 值；无法解析返回 {@link Optional#empty()}
     */
    public static Optional<SqlValue> resolveSqlValue(
            ValueModelResolver resolver,
            AbstractInvokeExpr invoke,
            int sqlArgumentIndex,
            int statementIndex) {

        ValueModel model = resolver.resolve(invoke.getArg(sqlArgumentIndex), statementIndex);
        MethodParameterRef target = MethodParameterRef.of(invoke.getMethodSignature(), sqlArgumentIndex);
        // SQL 依赖的调用者形参（拼接、数组、对象字段里的参数依赖）作为参数追踪入口，
        // 缺失该列表时调用点语句的参数推断与轨迹为空。
        List<MethodParameterRef> parameters = model.parameterDependencies().stream()
                .map(dependency -> MethodParameterRef.of(
                        resolver.methodSignature(),
                        dependency.index(),
                        dependency.name()))
                .filter(MethodParameterRef::isValid)
                .toList();
        return STRING_VALUE_RESOLVER.resolve(model, target)
                .map(value -> new SqlValue(value.value(), model, parameters, value.diagnostics()));
    }

    /**
     * 根据调用点信息构造一条 SQL 语句。
     * <p>
     * 识别语句类型并构建 {@link SqlNode} 树，将调用点相关上下文（调用者、目标、语句下标、值模型）
     * 写入元数据的扩展属性。无法识别或构建失败时返回 {@code null}。
     * </p>
     *
     * @param method         调用所在方法
     * @param stmt           调用语句
     * @param statementIndex 语句下标
     * @param invoke         调用表达式
     * @param target         SQL 调用目标
     * @param sqlValue       解析出的 SQL 值
     * @param boundParameters 语句对象上 setXxx 绑定记录（下标 + 值模型 + 依赖）
     * @return 构造出的 SQL 语句；无法识别返回 {@code null}
     */
    public static SqlStatement buildCallSiteStatement(
            SootMethod method,
            Stmt stmt,
            int statementIndex,
            AbstractInvokeExpr invoke,
            SqlCallTarget target,
            SqlValue sqlValue,
            List<BoundParameter> boundParameters) {

        String rawSql = sqlValue.rawSql().trim();
        StatementType statementType = detectStatementType(rawSql);
        if (statementType == null) {
            return null;
        }

        SqlNode sqlNode = SqlNodeBuilder.build(rawSql);
        if (sqlNode == null) {
            return null;
        }

        MethodSignature caller = method.getSignature();
        Map<String, Object> extra = new LinkedHashMap<>(target.extra());
        extra.put("sqlTarget", target.displayName());
        extra.put("callSite", stmt.toString());
        extra.put("statementIndex", statementIndex);
        extra.put("sqlValue", sqlValue.model().display());

        AnalyzeResult.Metadata metadata = metadata(
                Collections.emptyList(),
                target.tags(),
                extra);

        // SQL 文本实参依赖 + 绑定调用（setXxx）实参依赖合并，去重保持顺序
        List<MethodParameterRef> parameters = new ArrayList<>(sqlValue.parameters());
        for (BoundParameter bound : boundParameters) {
            for (MethodParameterRef dependency : bound.dependencies()) {
                if (!parameters.contains(dependency)) {
                    parameters.add(dependency);
                }
            }
        }

        return new SqlStatement(
                callSiteStatementId(caller, target.kind(), invoke.getMethodSignature(), statementIndex),
                statementType,
                target.sourceType(),
                sourceLocation(caller, caller.getDeclClassType().getFullyQualifiedName()),
                rawSql,
                sqlNode,
                List.copyOf(parameters),
                boundParameters,
                sqlValue.diagnostics(),
                metadata);
    }

    /**
     * 兼容无绑定参数调用的旧签名。
     */
    public static SqlStatement buildCallSiteStatement(
            SootMethod method,
            Stmt stmt,
            int statementIndex,
            AbstractInvokeExpr invoke,
            SqlCallTarget target,
            SqlValue sqlValue) {
        return buildCallSiteStatement(method, stmt, statementIndex, invoke, target, sqlValue, List.of());
    }

    /**
     * 根据静态 SQL 文本构造一条 SQL 语句（适用于注解、资源等非调用点来源）。
     *
     * @param method      所属方法
     * @param statementId 语句标识
     * @param sourceType  来源类型
     * @param rawSql      原始 SQL 文本
     * @param sourceFile  源文件路径
     * @param tags        自定义标签
     * @param extra       扩展属性
     * @return 构造出的 SQL 语句；文本为空或无法识别返回 {@code null}
     */
    public static SqlStatement buildStaticStatement(
            SootMethod method,
            String statementId,
            SqlSourceType sourceType,
            String rawSql,
            String sourceFile,
            List<String> tags,
            Map<String, Object> extra) {

        if (rawSql == null || rawSql.isBlank()) {
            return null;
        }

        String sql = rawSql.trim();
        StatementType statementType = detectStatementType(sql);
        if (statementType == null) {
            return null;
        }

        SqlNode sqlNode = SqlNodeBuilder.build(sql);
        if (sqlNode == null) {
            return null;
        }

        AnalyzeResult.Metadata metadata = metadata(
                Collections.emptyList(),
                tags,
                extra);

        return new SqlStatement(
                statementId,
                statementType,
                sourceType,
                sourceLocation(method.getSignature(), sourceFile),
                sql,
                sqlNode,
                List.of(),
                List.of(),
                metadata);
    }

    /**
     * 构造语句元数据。
     *
     * @param callDepth 调用链深度
     * @param callers   调用者列表
     * @param sqlNode   SQL 节点树（用于计算复杂度）
     * @param tags      自定义标签
     * @param extra     扩展属性
     * @return 元数据
     */
    public static AnalyzeResult.Metadata metadata(
            List<String> callers,
            List<String> tags,
            Map<String, Object> extra) {

        return AnalyzeResult.Metadata.builder()
                .callers(callers == null ? Collections.emptyList() : List.copyOf(callers))
                .tags(tags == null ? Collections.emptyList() : List.copyOf(tags))
                .extra(extra == null ? Collections.emptyMap() : Map.copyOf(extra))
                .build();
    }

    /**
     * 根据方法签名构造来源位置。
     *
     * @param signature  方法签名
     * @param sourceFile 源文件路径
     * @return 来源位置
     */
    public static SourceLocation sourceLocation(MethodSignature signature, String sourceFile) {
        String className = signature.getDeclClassType().getFullyQualifiedName();
        String methodName = signature.getName();
        String returnType = signature.getType().toString();
        List<String> parameterTypes = signature.getParameterTypes().stream()
                .map(Object::toString)
                .toList();
        return SourceLocation.of(className, methodName, returnType, parameterTypes, sourceFile);
    }

    /**
     * 根据方法签名生成语句标识，格式为 {@code 类名.方法名(参数类型1,参数类型2)}。
     *
     * @param signature 方法签名
     * @return 语句标识
     */
    public static String statementId(MethodSignature signature) {
        String parameters = signature.getParameterTypes().stream()
                .map(Object::toString)
                .collect(Collectors.joining(","));
        return signature.getDeclClassType().getFullyQualifiedName()
                + "." + signature.getName()
                + "(" + parameters + ")";
    }

    /**
     * 为调用点生成语句标识，格式为 {@code 调用者签名#类别:目标方法名:语句下标}。
     *
     * @param caller         调用者方法签名
     * @param kind           调用类别
     * @param target         目标方法签名
     * @param statementIndex 语句下标
     * @return 调用点语句标识
     */
    public static String callSiteStatementId(
            MethodSignature caller,
            String kind,
            MethodSignature target,
            int statementIndex) {

        return statementId(caller)
                + "#" + kind
                + ":" + target.getName()
                + ":" + statementIndex;
    }

    /**
     * 根据 SQL 首关键字识别语句类型。
     *
     * @param sql SQL 文本
     * @return 语句类型；无法识别返回 {@code null}
     */
    public static StatementType detectStatementType(String sql) {
        if (sql == null) {
            return null;
        }

        String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        String firstToken = normalized.split("\\s+", 2)[0];
        return switch (firstToken) {
            case "select", "with", "from" -> StatementType.SELECT;
            case "insert" -> StatementType.INSERT;
            case "update" -> StatementType.UPDATE;
            case "delete" -> StatementType.DELETE;
            case "create" -> StatementType.CREATE;
            case "alter" -> StatementType.ALTER;
            case "drop" -> StatementType.DROP;
            case "truncate" -> StatementType.TRUNCATE;
            // PG/Oracle 特有语句起始词：按语义归入最近的大类
            case "merge" -> StatementType.UPDATE;          // Oracle MERGE INTO
            case "explain", "analyze" -> StatementType.SELECT; // PG EXPLAIN/ANALYZE（读导向）
            case "grant", "revoke" -> StatementType.CREATE;   // DCL 归入 CREATE 大类（原有分类无 DCL）
            default -> null;
        };
    }

    /**
     * 收集参数推断结果与渲染 SQL 中的诊断信息。
     *
     * @param parameters 参数推断结果列表
     * @param sqls       渲染后的 SQL 列表
     * @return 去重后的诊断信息列表
     */
    public static List<String> collectParameterDiagnostics(
            List<ParameterValueInferenceResult> parameters,
            List<SqlNodeRenderer.RenderedSql> sqls) {

        List<String> diagnostics = new ArrayList<>();
        if (parameters != null) {
            parameters.stream()
                    .filter(parameter -> parameter != null && parameter.diagnostics() != null)
                    .flatMap(parameter -> parameter.diagnostics().stream())
                    .forEach(diagnostics::add);
        }
        if (sqls != null) {
            sqls.stream()
                    .filter(sql -> sql != null && sql.diagnostics() != null)
                    .flatMap(sql -> sql.diagnostics().stream())
                    .forEach(diagnostics::add);
        }
        return cleanDiagnostics(diagnostics);
    }

    /**
     * 合并 SQL 诊断信息与渲染 SQL 中携带的诊断信息。
     *
     * @param sqlDiagnostics 原始 SQL 诊断信息
     * @param renderedSqls   渲染后的 SQL 列表
     * @return 去重后的诊断信息列表
     */
    public static List<String> collectSqlDiagnostics(
            List<String> sqlDiagnostics,
            List<SqlNodeRenderer.RenderedSql> renderedSqls) {

        List<String> diagnostics = new ArrayList<>();
        if (sqlDiagnostics != null) {
            diagnostics.addAll(sqlDiagnostics);
        }
        if (renderedSqls != null) {
            renderedSqls.stream()
                    .filter(rendered -> rendered != null && rendered.diagnostics() != null)
                    .flatMap(rendered -> rendered.diagnostics().stream())
                    .forEach(diagnostics::add);
        }
        return cleanDiagnostics(diagnostics);
    }

    /**
     * 从注解中读取字符串属性值，支持 {@link StringConstant}、字符串数组（拼接）及纯字符串。
     *
     * @param annotation    注解
     * @param attributeName 属性名
     * @return 字符串属性值；不存在或为空返回 {@link Optional#empty()}
     */
    public static Optional<String> stringAnnotationValue(AnnotationUsage annotation, String attributeName) {
        if (annotation == null || attributeName == null) {
            return Optional.empty();
        }

        Object value = annotation.getValues().get(attributeName);
        if (value instanceof StringConstant stringConstant) {
            return Optional.ofNullable(stringConstant.getValue());
        }
        if (value instanceof List<?> values) {
            String joined = values.stream()
                    .map(SqlExtractionSupport::annotationValuePart)
                    .filter(part -> part != null && !part.isBlank())
                    .collect(Collectors.joining(""));
            return joined.isBlank() ? Optional.empty() : Optional.of(joined);
        }
        if (value instanceof String string) {
            return Optional.of(string);
        }
        return Optional.empty();
    }

    /**
     * 从注解中读取布尔属性值，兼容 {@link Boolean}、{@code true/false} 文本与 {@code 1/0}。
     *
     * @param annotation    注解
     * @param attributeName 属性名
     * @return 布尔属性值；无法识别返回 {@link Optional#empty()}
     */
    public static Optional<Boolean> booleanAnnotationValue(AnnotationUsage annotation, String attributeName) {
        if (annotation == null || attributeName == null) {
            return Optional.empty();
        }

        Object value = annotation.getValues().get(attributeName);
        if (value instanceof Boolean bool) {
            return Optional.of(bool);
        }
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString().trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Optional.of(true);
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    /**
     * 提取注解数组中单个元素的字符串表示，{@link StringConstant} 取其常量值。
     *
     * @param value 元素值
     * @return 字符串表示；为 {@code null} 时返回 {@code null}
     */
    private static String annotationValuePart(Object value) {
        if (value instanceof StringConstant stringConstant) {
            return stringConstant.getValue();
        }
        return value == null ? null : value.toString();
    }

    /**
     * 清洗诊断信息：去除空白项并去重。
     *
     * @param diagnostics 原始诊断信息
     * @return 清洗后的诊断信息列表
     */
    private static List<String> cleanDiagnostics(List<String> diagnostics) {
        return diagnostics.stream()
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 根据参数类型生成一个示例外部值（数值类型给 1、布尔给 true、其余给参数名）。
     * <p>
     * 用于在缺少实际值时为参数填充一个合理的占位值，便于后续渲染。
     * </p>
     *
     * @param type 参数类型
     * @param name 参数名
     * @return 示例值
     */
    private static Object sampleExternalValue(String type, String name) {
        return switch (type) {
            case "byte", "java.lang.Byte", "short", "java.lang.Short", "int", "java.lang.Integer" -> 1;
            case "long", "java.lang.Long" -> 1L;
            case "float", "java.lang.Float" -> 1.0F;
            case "double", "java.lang.Double" -> 1.0D;
            case "boolean", "java.lang.Boolean" -> true;
            default -> name == null || name.isBlank() ? "param" : name;
        };
    }
}
