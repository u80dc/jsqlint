package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.SourceLocation;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeBuilder;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeRenderer;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeUtils;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ValueModelStringResolver;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModelResolver;
import sootup.core.jimple.common.constant.StringConstant;
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

public final class SqlExtractionSupport {

    private static final ValueModelStringResolver STRING_VALUE_RESOLVER = new ValueModelStringResolver();

    private SqlExtractionSupport() {
    }

    public static SqlExtractionResult extractCallSites(
            SqlExtractorContext context,
            Function<MethodSignature, Optional<SqlCallTarget>> targetMatcher) {

        List<SqlStatement> statements = new ArrayList<>();
        context.view()
                .getClasses()
                .filter(SootClass::isApplicationClass)
                .forEach(clazz -> clazz.getMethods().forEach(method ->
                        extractCallSites(method, targetMatcher, statements)));
        return new SqlExtractionResult(statements, List.of());
    }

    private static void extractCallSites(
            SootMethod method,
            Function<MethodSignature, Optional<SqlCallTarget>> targetMatcher,
            List<SqlStatement> statements) {

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
                continue;
            }

            SqlStatement statement = buildCallSiteStatement(
                    method,
                    stmt,
                    i,
                    invoke,
                    target,
                    sqlValue.get());
            if (statement != null) {
                statements.add(statement);
            }
        }
    }

    public static Optional<AbstractInvokeExpr> invokeOf(Stmt stmt) {
        if (stmt instanceof JAssignStmt assign) {
            return assign.getInvokeExpr();
        }
        if (stmt instanceof JInvokeStmt invokeStmt) {
            return invokeStmt.getInvokeExpr();
        }
        return Optional.empty();
    }

    public static Optional<SqlValue> resolveSqlValue(
            ValueModelResolver resolver,
            AbstractInvokeExpr invoke,
            int sqlArgumentIndex,
            int statementIndex) {

        ValueModel model = resolver.resolve(invoke.getArg(sqlArgumentIndex), statementIndex);
        MethodParameterRef target = MethodParameterRef.of(invoke.getMethodSignature(), sqlArgumentIndex);
        return STRING_VALUE_RESOLVER.resolve(model, target)
                .map(value -> new SqlValue(value.value(), model, value.diagnostics()));
    }

    public static SqlStatement buildCallSiteStatement(
            SootMethod method,
            Stmt stmt,
            int statementIndex,
            AbstractInvokeExpr invoke,
            SqlCallTarget target,
            SqlValue sqlValue) {

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
                0,
                Collections.emptyList(),
                sqlNode,
                target.tags(),
                extra);

        return new SqlStatement(
                callSiteStatementId(caller, target.kind(), invoke.getMethodSignature(), statementIndex),
                statementType,
                target.sourceType(),
                sourceLocation(caller, caller.getDeclClassType().getFullyQualifiedName()),
                rawSql,
                sqlNode,
                List.of(),
                sqlValue.diagnostics(),
                metadata);
    }

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
                0,
                Collections.emptyList(),
                sqlNode,
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

    public static AnalyzeResult.Metadata metadata(
            int callDepth,
            List<String> callers,
            SqlNode sqlNode,
            List<String> tags,
            Map<String, Object> extra) {

        return AnalyzeResult.Metadata.builder()
                .callDepth(callDepth)
                .callers(callers == null ? Collections.emptyList() : List.copyOf(callers))
                .complexity(sqlNode == null ? 0 : SqlNodeUtils.countDynamicNodes(sqlNode))
                .tableNames(Collections.emptyList())
                .tags(tags == null ? Collections.emptyList() : List.copyOf(tags))
                .extra(extra == null ? Collections.emptyMap() : Map.copyOf(extra))
                .build();
    }

    public static SourceLocation sourceLocation(MethodSignature signature, String sourceFile) {
        String className = signature.getDeclClassType().getFullyQualifiedName();
        String methodName = signature.getName();
        String returnType = signature.getType().toString();
        List<String> parameterTypes = signature.getParameterTypes().stream()
                .map(Object::toString)
                .toList();
        return SourceLocation.of(className, methodName, returnType, parameterTypes, sourceFile);
    }

    public static String statementId(MethodSignature signature) {
        String parameters = signature.getParameterTypes().stream()
                .map(Object::toString)
                .collect(Collectors.joining(","));
        return signature.getDeclClassType().getFullyQualifiedName()
                + "." + signature.getName()
                + "(" + parameters + ")";
    }

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
            default -> null;
        };
    }

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

    private static String annotationValuePart(Object value) {
        if (value instanceof StringConstant stringConstant) {
            return stringConstant.getValue();
        }
        return value == null ? null : value.toString();
    }

    private static List<String> cleanDiagnostics(List<String> diagnostics) {
        return diagnostics.stream()
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .toList();
    }

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
