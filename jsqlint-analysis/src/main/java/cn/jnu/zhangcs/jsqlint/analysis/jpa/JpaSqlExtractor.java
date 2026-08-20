package cn.jnu.zhangcs.jsqlint.analysis.jpa;

import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.*;
import cn.jnu.zhangcs.jsqlint.analysis.spring.SpringUtils;
import cn.jnu.zhangcs.jsqlint.analysis.util.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import sootup.core.model.SootClass;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.*;

/**
 * 从 JPA 和 Spring Data JPA 提取 SQL/JPQL.
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
        List<SqlStatement> statements = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        Map<String, JpaNamedQuery> namedQueries = xmlNamedQueries(context.source(), diagnostics);
        extractSpringDataRepositoryMethods(context, namedQueries, statements, diagnostics);

        SqlExtractionResult callSiteResult = SqlExtractionSupport.extractCallSites(
                context,
                this::matchEntityManagerTarget);
        statements.addAll(callSiteResult.statements());
        diagnostics.addAll(callSiteResult.diagnostics());
        return new SqlExtractionResult(statements, diagnostics);
    }

    private void extractSpringDataRepositoryMethods(
            SqlExtractorContext context,
            Map<String, JpaNamedQuery> namedQueries,
            List<SqlStatement> statements,
            List<String> diagnostics) {

        context.view()
                .getClasses()
                .filter(SootClass::isApplicationClass)
                .filter(this::isSpringDataRepository)
                .forEach(clazz -> clazz.getMethods().forEach(method -> {
                    if (method instanceof JavaSootMethod javaMethod) {
                        extractSpringDataRepositoryMethod(clazz, javaMethod, namedQueries, statements, diagnostics);
                    }
                }));
    }

    private void extractSpringDataRepositoryMethod(
            JavaSootClass repository,
            JavaSootMethod method,
            Map<String, JpaNamedQuery> namedQueries,
            List<SqlStatement> statements,
            List<String> diagnostics) {

        Optional<AnnotationUsage> annotationOptional = queryAnnotation(method);
        if (annotationOptional.isPresent()) {
            extractSpringDataQueryAnnotation(method, annotationOptional.get(), statements, diagnostics);
            return;
        }

        String queryName = entityName(repository) + "." + method.getName();
        JpaNamedQuery namedQuery = namedQueries.get(queryName);
        if (namedQuery != null) {
            extractSpringDataNamedQuery(method, namedQuery, statements, diagnostics);
            return;
        }

        extractSpringDataDerivedQuery(repository, method, statements);
    }

    private void extractSpringDataQueryAnnotation(
            JavaSootMethod method,
            AnnotationUsage annotation,
            List<SqlStatement> statements,
            List<String> diagnostics) {

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
        SqlStatement statement = SqlExtractionSupport.buildStaticStatement(
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

    private void extractSpringDataNamedQuery(
            JavaSootMethod method,
            JpaNamedQuery namedQuery,
            List<SqlStatement> statements,
            List<String> diagnostics) {

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("queryName", namedQuery.name());
        extra.put("nativeQuery", namedQuery.nativeQuery());

        List<String> tags = namedQuery.nativeQuery()
                ? List.of(name(), "spring-data-jpa", "xml", "named-native-query")
                : List.of(name(), "spring-data-jpa", "xml", "named-query");
        SqlStatement statement = SqlExtractionSupport.buildStaticStatement(
                method,
                SqlExtractionSupport.statementId(method.getSignature()),
                SqlSourceType.XML_SQL,
                namedQuery.sql(),
                namedQuery.sourceFile(),
                tags,
                extra);
        if (statement == null) {
            diagnostics.add("JPA XML SQL解析失败: " + namedQuery.name());
            return;
        }
        statements.add(statement);
    }

    private void extractSpringDataDerivedQuery(
            JavaSootClass repository,
            JavaSootMethod method,
            List<SqlStatement> statements) {

        Optional<DerivedQuery> derivedQuery = deriveQuery(repository, method);
        if (derivedQuery.isEmpty()) {
            return;
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("derivedMethod", method.getName());
        extra.put("entityName", entityName(repository));

        SqlStatement statement = SqlExtractionSupport.buildStaticStatement(
                method,
                SqlExtractionSupport.statementId(method.getSignature()),
                SqlSourceType.STATIC_SQL,
                derivedQuery.get().sql(),
                repository.getName(),
                List.of(name(), "spring-data-jpa", "derived-query"),
                extra);
        if (statement != null) {
            statements.add(statement);
        }
    }

    private Map<String, JpaNamedQuery> xmlNamedQueries(Path source, List<String> diagnostics) {
        Map<String, JpaNamedQuery> result = new HashMap<>();
        if (source == null) {
            return result;
        }

        for (Path xml : FileUtils.findXmlFiles(source)) {
            if (!isJpaMappingFile(xml)) {
                continue;
            }
            parseJpaMappingFile(xml, result, diagnostics);
        }
        return result;
    }

    private boolean isJpaMappingFile(Path xml) {
        String normalized = xml.toString().replace('\\', '/');
        return normalized.endsWith("/META-INF/orm.xml") || normalized.endsWith("/orm.xml");
    }

    private void parseJpaMappingFile(
            Path xml,
            Map<String, JpaNamedQuery> result,
            List<String> diagnostics) {

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // 部分 JAXP 实现未暴露该特性，忽略后仍按默认安全策略解析。
            }
            Document document = factory.newDocumentBuilder().parse(xml.toFile());
            collectNamedQueries(document, "named-query", false, xml, result);
            collectNamedQueries(document, "named-native-query", true, xml, result);
        } catch (Exception exception) {
            diagnostics.add("JPA XML解析失败: " + xml + ": " + exception.getMessage());
        }
    }

    private void collectNamedQueries(
            Document document,
            String elementName,
            boolean nativeQuery,
            Path sourceFile,
            Map<String, JpaNamedQuery> result) {

        NodeList elements = document.getElementsByTagNameNS("*", elementName);
        if (elements.getLength() == 0) {
            elements = document.getElementsByTagName(elementName);
        }

        for (int i = 0; i < elements.getLength(); i++) {
            if (!(elements.item(i) instanceof Element element)) {
                continue;
            }
            String name = element.getAttribute("name");
            String sql = queryText(element);
            if (name == null || name.isBlank() || sql.isBlank()) {
                continue;
            }
            result.put(name, new JpaNamedQuery(name, sql, nativeQuery, sourceFile.toString()));
        }
    }

    private String queryText(Element queryOwner) {
        NodeList queries = queryOwner.getElementsByTagNameNS("*", "query");
        if (queries.getLength() == 0) {
            queries = queryOwner.getElementsByTagName("query");
        }
        if (queries.getLength() == 0) {
            return "";
        }
        return queries.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }

    private Optional<DerivedQuery> deriveQuery(JavaSootClass repository, JavaSootMethod method) {
        String methodName = method.getName();
        String entity = entityName(repository);
        if (entity.isBlank() || methodName == null || methodName.isBlank()) {
            return Optional.empty();
        }

        if (methodName.startsWith("countBy")) {
            String predicate = predicate(methodName.substring("countBy".length()), "e", 1);
            if (predicate.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new DerivedQuery("select count(e) from " + entity + " e where " + predicate));
        }
        if (methodName.startsWith("existsBy")) {
            String predicate = predicate(methodName.substring("existsBy".length()), "e", 1);
            if (predicate.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new DerivedQuery("select count(e) from " + entity + " e where " + predicate));
        }
        if (methodName.startsWith("deleteBy")) {
            String predicate = predicate(methodName.substring("deleteBy".length()), "e", 1);
            if (predicate.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new DerivedQuery("delete from " + entity + " e where " + predicate));
        }
        if (methodName.startsWith("removeBy")) {
            String predicate = predicate(methodName.substring("removeBy".length()), "e", 1);
            if (predicate.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new DerivedQuery("delete from " + entity + " e where " + predicate));
        }

        String criteria = selectCriteria(methodName);
        if (criteria == null || criteria.isBlank()) {
            return Optional.empty();
        }

        String orderBy = "";
        int orderIndex = criteria.indexOf("OrderBy");
        if (orderIndex >= 0) {
            orderBy = orderBy(criteria.substring(orderIndex + "OrderBy".length()), "e");
            criteria = criteria.substring(0, orderIndex);
        }

        String predicate = predicate(criteria, "e", 1);
        if (predicate.isBlank()) {
            return Optional.empty();
        }

        String sql = "select e from " + entity + " e where " + predicate;
        if (!orderBy.isBlank()) {
            sql += " order by " + orderBy;
        }
        return Optional.of(new DerivedQuery(sql));
    }

    private String selectCriteria(String methodName) {
        for (String prefix : List.of("find", "read", "get", "query", "search", "stream")) {
            if (!methodName.startsWith(prefix)) {
                continue;
            }
            int byIndex = methodName.indexOf("By", prefix.length());
            if (byIndex >= 0) {
                return methodName.substring(byIndex + 2);
            }
        }
        return null;
    }

    private String predicate(String criteria, String alias, int firstParameterIndex) {
        if (criteria == null || criteria.isBlank()) {
            return "";
        }

        List<String> parts = splitByKeyword(criteria, "And");
        List<String> predicates = new ArrayList<>();
        int parameterIndex = firstParameterIndex;
        for (String part : parts) {
            Condition condition = condition(part, alias, parameterIndex);
            if (condition.expression().isBlank()) {
                return "";
            }
            predicates.add(condition.expression());
            parameterIndex += condition.parameterCount();
        }
        return String.join(" and ", predicates);
    }

    private Condition condition(String rawPart, String alias, int parameterIndex) {
        String part = rawPart;
        boolean ignoreCase = false;
        if (part.endsWith("IgnoreCase")) {
            ignoreCase = true;
            part = part.substring(0, part.length() - "IgnoreCase".length());
        }

        if (part.endsWith("IsNotNull")) {
            return new Condition(alias + "." + property(part, "IsNotNull") + " is not null", 0);
        }
        if (part.endsWith("NotNull")) {
            return new Condition(alias + "." + property(part, "NotNull") + " is not null", 0);
        }
        if (part.endsWith("IsNull")) {
            return new Condition(alias + "." + property(part, "IsNull") + " is null", 0);
        }
        if (part.endsWith("True")) {
            return new Condition(alias + "." + property(part, "True") + " = true", 0);
        }
        if (part.endsWith("False")) {
            return new Condition(alias + "." + property(part, "False") + " = false", 0);
        }
        if (part.endsWith("Between")) {
            String property = alias + "." + property(part, "Between");
            return new Condition(property + " between ?" + parameterIndex + " and ?" + (parameterIndex + 1), 2);
        }
        if (part.endsWith("GreaterThanEqual")) {
            return new Condition(alias + "." + property(part, "GreaterThanEqual") + " >= ?" + parameterIndex, 1);
        }
        if (part.endsWith("LessThanEqual")) {
            return new Condition(alias + "." + property(part, "LessThanEqual") + " <= ?" + parameterIndex, 1);
        }
        if (part.endsWith("GreaterThan")) {
            return new Condition(alias + "." + property(part, "GreaterThan") + " > ?" + parameterIndex, 1);
        }
        if (part.endsWith("LessThan")) {
            return new Condition(alias + "." + property(part, "LessThan") + " < ?" + parameterIndex, 1);
        }
        if (part.endsWith("Containing")) {
            return new Condition(alias + "." + property(part, "Containing") + " like concat('%', ?" + parameterIndex + ", '%')", 1);
        }
        if (part.endsWith("Contains")) {
            return new Condition(alias + "." + property(part, "Contains") + " like concat('%', ?" + parameterIndex + ", '%')", 1);
        }
        if (part.endsWith("StartingWith")) {
            return new Condition(alias + "." + property(part, "StartingWith") + " like concat(?" + parameterIndex + ", '%')", 1);
        }
        if (part.endsWith("EndingWith")) {
            return new Condition(alias + "." + property(part, "EndingWith") + " like concat('%', ?" + parameterIndex + ")", 1);
        }
        if (part.endsWith("Not")) {
            return new Condition(alias + "." + property(part, "Not") + " <> ?" + parameterIndex, 1);
        }

        String left = alias + "." + decapitalize(part);
        String right = "?" + parameterIndex;
        if (ignoreCase) {
            left = "lower(" + left + ")";
            right = "lower(" + right + ")";
        }
        return new Condition(left + " = " + right, 1);
    }

    private String property(String part, String suffix) {
        return decapitalize(part.substring(0, part.length() - suffix.length()));
    }

    private String orderBy(String orderPart, String alias) {
        if (orderPart == null || orderPart.isBlank()) {
            return "";
        }
        String direction = "asc";
        String property = orderPart;
        if (orderPart.endsWith("Desc")) {
            direction = "desc";
            property = orderPart.substring(0, orderPart.length() - "Desc".length());
        } else if (orderPart.endsWith("Asc")) {
            property = orderPart.substring(0, orderPart.length() - "Asc".length());
        }
        return alias + "." + decapitalize(property) + " " + direction;
    }

    private List<String> splitByKeyword(String value, String keyword) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = value.indexOf(keyword, start + 1)) >= 0) {
            parts.add(value.substring(start, index));
            start = index + keyword.length();
        }
        parts.add(value.substring(start));
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }

    private boolean isSpringDataRepository(JavaSootClass clazz) {
        if (clazz.getName().endsWith("Repository")) {
            return true;
        }
        for (AnnotationUsage annotation : clazz.getAnnotations()) {
            if (SpringUtils.isRepository(annotation)) {
                return true;
            }
        }
        return false;
    }

    private String entityName(JavaSootClass repository) {
        String className = repository.getName();
        int dot = className.lastIndexOf('.');
        String simpleName = dot >= 0 ? className.substring(dot + 1) : className;
        if (simpleName.endsWith("Repository")) {
            return simpleName.substring(0, simpleName.length() - "Repository".length());
        }
        return simpleName;
    }

    private String decapitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() == 1) {
            return value.toLowerCase();
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
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

    private record JpaNamedQuery(
            String name,
            String sql,
            boolean nativeQuery,
            String sourceFile) {
    }

    private record DerivedQuery(String sql) {
    }

    private record Condition(String expression, int parameterCount) {
    }
}
