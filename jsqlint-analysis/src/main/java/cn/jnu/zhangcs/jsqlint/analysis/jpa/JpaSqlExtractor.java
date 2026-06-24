package cn.jnu.zhangcs.jsqlint.analysis.jpa;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.*;
import sootup.core.model.SootClass;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootMethod;

import java.util.*;

/**
 * 从 JPA 和 Spring Data JPA 入口点提取 SQL/JPQL.
 *
 * @author zhangcs
 */
public final class JpaSqlExtractor implements SqlExtractor {

    private static final Set<String> QUERY_ANNOTATIONS = Set.of(
            "org.springframework.data.jpa.repository.Query",
            "org.springframework.data.jpa.repository.NativeQuery");

    private static final Set<String> ENTITY_MANAGER_TYPES = Set.of(
            "jakarta.persistence.EntityManager",
            "javax.persistence.EntityManager");

    @Override
    public String name() {
        return "jpa";
    }

    @Override
    public SqlExtractionResult extract(SqlExtractorContext context) {
        List<AnalyzeResult.SqlStatementResult> statements = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        extractSpringDataQueryAnnotations(context, statements, diagnostics);

        SqlExtractionResult callSiteResult = SqlExtractionSupport.extractCallSites(
                context,
                this::matchEntityManagerTarget);
        statements.addAll(callSiteResult.statements());
        diagnostics.addAll(callSiteResult.diagnostics());
        return new SqlExtractionResult(statements, diagnostics);
    }

    private void extractSpringDataQueryAnnotations(
            SqlExtractorContext context,
            List<AnalyzeResult.SqlStatementResult> statements,
            List<String> diagnostics) {

        context.view()
                .getClasses()
                .filter(SootClass::isApplicationClass)
                .forEach(clazz -> clazz.getMethods().forEach(method -> {
                    if (method instanceof JavaSootMethod javaMethod) {
                        extractSpringDataQueryAnnotation(javaMethod, statements, diagnostics);
                    }
                }));
    }

    private void extractSpringDataQueryAnnotation(
            JavaSootMethod method,
            List<AnalyzeResult.SqlStatementResult> statements,
            List<String> diagnostics) {

        Optional<AnnotationUsage> annotationOptional = queryAnnotation(method);
        if (annotationOptional.isEmpty()) {
            return;
        }

        AnnotationUsage annotation = annotationOptional.get();
        Optional<String> sqlOptional = SqlExtractionSupport.stringAnnotationValue(annotation, "value");
        if (sqlOptional.isEmpty() || sqlOptional.get().isBlank()) {
            return;
        }

        String annotationName = annotationName(annotation);
        boolean nativeQuery = isNativeQuery(annotationName, annotation);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("annotation", annotationName);
        extra.put("nativeQuery", nativeQuery);

        List<String> tags = nativeQuery
                ? List.of(name(), "spring-data-jpa", "annotation", "native-query")
                : List.of(name(), "spring-data-jpa", "annotation", "jpql");
        AnalyzeResult.SqlStatementResult statement = SqlExtractionSupport.buildStaticStatement(
                method,
                SqlExtractionSupport.statementId(method.getSignature()),
                SqlSourceType.STATIC_SQL,
                sqlOptional.get(),
                method.getDeclaringClassType().getFullyQualifiedName(),
                tags,
                extra);
        if (statement == null) {
            diagnostics.add("JPA SQL解析失败: " + SqlExtractionSupport.statementId(method.getSignature()));
            return;
        }
        statements.add(statement);
    }

    private Optional<AnnotationUsage> queryAnnotation(JavaSootMethod method) {
        for (AnnotationUsage annotation : method.getAnnotations()) {
            if (QUERY_ANNOTATIONS.contains(annotationName(annotation))) {
                return Optional.of(annotation);
            }
        }
        return Optional.empty();
    }

    private boolean isNativeQuery(String annotationName, AnnotationUsage annotation) {
        if ("org.springframework.data.jpa.repository.NativeQuery".equals(annotationName)) {
            return true;
        }
        return SqlExtractionSupport.booleanAnnotationValue(annotation, "nativeQuery").orElse(false);
    }

    private Optional<SqlCallTarget> matchEntityManagerTarget(MethodSignature signature) {
        if (!hasStringSqlArgument(signature)) {
            return Optional.empty();
        }

        String className = signature.getDeclClassType().getFullyQualifiedName();
        if (!ENTITY_MANAGER_TYPES.contains(className)) {
            return Optional.empty();
        }

        String methodName = signature.getName();
        if (!"createQuery".equals(methodName) && !"createNativeQuery".equals(methodName)) {
            return Optional.empty();
        }

        boolean nativeQuery = "createNativeQuery".equals(methodName);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("jpaApi", className + "#" + methodName);
        extra.put("nativeQuery", nativeQuery);

        List<String> tags = nativeQuery
                ? List.of(name(), "entity-manager", "native-query")
                : List.of(name(), "entity-manager", "jpql");
        return Optional.of(new SqlCallTarget(
                name(),
                className + "#" + methodName,
                0,
                SqlSourceType.PARAM_SQL,
                tags,
                extra));
    }

    private boolean hasStringSqlArgument(MethodSignature signature) {
        return signature != null
                && signature.getParameterCount() > 0
                && "java.lang.String".equals(signature.getParameterTypes().getFirst().toString());
    }

    private String annotationName(AnnotationUsage annotation) {
        return annotation.getAnnotation().getFullyQualifiedName();
    }
}
