package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.resource.AnalysisResource;
import org.u80dc.jsqlint.core.spi.*;
import org.w3c.dom.*;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootMethod;
import sootup.core.signatures.MethodSignature;

import java.net.URI;
import java.util.*;
import java.util.function.Consumer;

/** 会话独占的 SQL 定义/片段索引，统一选择 databaseId 并保留重复定义诊断。 @author u80dc */
final class MybatisDefinitionIndex {
    private static final Set<String> COMMANDS = Set.of("select", "insert", "update", "delete");
    private final MybatisOptions options;
    private final MybatisExpressionEvaluator evaluator;
    private final Map<String, List<Candidate>> statements = new LinkedHashMap<>();
    private final Map<String, List<Element>> fragments = new LinkedHashMap<>();
    private final Map<Candidate, SqlDefinition> definitions = new HashMap<>();
    private final Map<String, SqlDefinition> resolved = new HashMap<>();
    private final Set<MethodSignature> indexedMethods = new HashSet<>();
    private final Map<String, JavaSootMethod> mapperMethods = new HashMap<>();

    MybatisDefinitionIndex(MybatisOptions options) { this.options = options; evaluator = new MybatisExpressionEvaluator(options); }

    void resource(AnalysisResource resource, MapperMethodMatcher methods) throws Exception {
        Element mapper = MybatisSqlAdapter.parseMapper(resource).getDocumentElement();
        if (!mapper.getTagName().equals("mapper")) return;
        String namespace = mapper.getAttribute("namespace");
        for (Node child = mapper.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element element)) continue;
            String localId = element.getAttribute("id"); String id = namespace + "." + localId;
            if (element.getTagName().equals("sql")) fragments.computeIfAbsent(id, ignored -> new ArrayList<>()).add(element);
            else if (COMMANDS.contains(element.getTagName())) {
                String databaseId = element.getAttribute("databaseId");
                var location = new SourceLocation(resource.uri(), id + (databaseId.isEmpty() ? "" : ":" + databaseId));
                add(new Candidate(id, namespace, databaseId, element.getTagName(), location, element, null, "", element.getAttribute("lang")));
                if (databaseId.isEmpty() || options.databaseId().filter(databaseId::equals).isPresent()) methods.addStatement(namespace, localId);
            }
        }
    }

    void method(JavaSootMethod method) {
        if (!indexedMethods.add(method.getSignature())) return;
        mapperMethods.put(method.getSignature().toString(), method);
        for (var annotation : MapperMethodMatcher.sqlAnnotations(method)) {
            String annotationType = annotation.getAnnotation().toString();
            String name = annotationType.substring(annotationType.lastIndexOf('.') + 1);
            String namespace = method.getDeclClassType().toString(); String id = namespace + "." + method.getName();
            var location = new SourceLocation(URI.create("java:" + namespace), method.getSignature() + " @" + name);
            String lang = "";
            for (var metadata : method.getAnnotations()) if (metadata.getAnnotation().toString().equals("org.apache.ibatis.annotations.Lang"))
                lang = String.valueOf(metadata.getValues().get("value"));
            add(new Candidate(id, namespace, string(annotation.getValues().get("databaseId"), ""), name.toLowerCase(Locale.ROOT),
                    location, null, annotation, method.getSignature().toString(), lang));
        }
    }

    private void add(Candidate candidate) {
        statements.computeIfAbsent(candidate.id(), ignored -> new ArrayList<>()).add(candidate);
    }

    void seeds(Consumer<AnalysisSeed> seeds) {
        for (var entry : statements.entrySet()) {
            var selected = activeStatements(entry.getValue());
            for (var candidate : selected) {
                var definition = definition(candidate);
                if (candidate.command().endsWith("provider") && definition.complete()) {
                    var mapper = mapperMethods.get(candidate.signature());
                    seeds.accept(new ProviderSeed(definition, mapper, new ProviderSqlExtractor(options)));
                } else seeds.accept(new DefinitionSeed(definition));
            }
        }
    }

    Optional<SqlDefinition> resolve(String id) {
        var candidates = activeStatements(statements.getOrDefault(id, List.of()));
        if (candidates.isEmpty()) return Optional.empty();
        return Optional.of(resolved.computeIfAbsent(id, ignored -> {
            if (candidates.size() == 1) return definition(candidates.getFirst());
            var first = candidates.getFirst();
            return new SqlDefinition(id, first.location(), ValueSqlNode.raw("ambiguousSqlDefinition"), evaluator,
                    metadata(first), List.of("Ambiguous MyBatis statement: " + id + " (" + candidates.size() + " definitions)"));
        }));
    }

    private SqlDefinition definition(Candidate candidate) {
        return definitions.computeIfAbsent(candidate, ignored -> {
            List<String> issues = new ArrayList<>(); SqlNode template = ValueSqlNode.raw("unresolvedSqlDefinition");
            var selected = activeStatements(statements.get(candidate.id()));
            if (selected.size() > 1) issues.add("Duplicate MyBatis statement: " + candidate.id() + " for databaseId=" + candidate.databaseId());
            if (candidate.annotation() != null) {
                var annotations = statements.get(candidate.id()).stream().filter(value -> value.annotation() != null && value.signature().equals(candidate.signature())).toList();
                if (select(annotations, Candidate::databaseId).isEmpty()) issues.add("No MyBatis SQL annotation matches databaseId=" + options.databaseId().orElse("<default>"));
                Map<String, Long> counts = new HashMap<>(); annotations.forEach(value -> counts.merge(value.databaseId(), 1L, Long::sum));
                if (counts.values().stream().anyMatch(count -> count > 1)) issues.add("Conflicting MyBatis annotations on " + candidate.signature());
            }
            if (candidate.namespace().isBlank() || candidate.id().endsWith(".")) issues.add("MyBatis mapper requires namespace and statement id");
            try {
                if (!candidate.lang().isEmpty() && !candidate.lang().equals("xml") && !candidate.lang().contains("org.apache.ibatis.scripting.xmltags.XMLLanguageDriver")
                        && !candidate.lang().contains("org/apache/ibatis/scripting/xmltags/XMLLanguageDriver"))
                    throw new IllegalArgumentException("Unsupported MyBatis language driver: " + candidate.lang());
                if (candidate.command().endsWith("provider")) {
                    String type = ProviderMethodResolver.type(candidate.annotation().getValues().get("type"));
                    String value = ProviderMethodResolver.type(candidate.annotation().getValues().get("value"));
                    if (!type.isEmpty() && !value.isEmpty() && !type.equals(value))
                        throw new IllegalArgumentException("MyBatis Provider value and type refer to different classes");
                    template = ValueSqlNode.raw("providerSql");
                } else if (candidate.xml() != null) template = MybatisSqlAdapter.statement(candidate.xml(), candidate.namespace(), selectedFragments(), options);
                else {
                    Object value = candidate.annotation().getValues().get("value");
                    List<?> values = value instanceof List<?> list ? list : Collections.singletonList(value);
                    List<String> sql = new ArrayList<>();
                    for (Object item : values) sql.add(string(item, null));
                    template = MybatisSqlAdapter.annotation(String.join(" ", sql).strip(), options);
                }
                evaluator.validate(template);
            } catch (RuntimeException e) {
                issues.add(Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
            }
            return new SqlDefinition(candidate.id(), candidate.location(), template, evaluator, metadata(candidate), issues);
        });
    }

    private Map<String, List<Element>> selectedFragments() {
        Map<String, List<Element>> selected = new LinkedHashMap<>();
        fragments.forEach((id, values) -> selected.put(id, select(values, element -> element.getAttribute("databaseId"))));
        return selected;
    }
    /** XML 与每个注解方法分别筛选，二者同时注册同一 id 时必须报告冲突。 */
    private List<Candidate> activeStatements(List<Candidate> values) {
        List<Candidate> active = new ArrayList<>(select(values.stream().filter(value -> value.xml() != null).toList(), Candidate::databaseId));
        Map<String, List<Candidate>> annotated = new LinkedHashMap<>();
        values.stream().filter(value -> value.annotation() != null).forEach(value -> annotated.computeIfAbsent(value.signature(), ignored -> new ArrayList<>()).add(value));
        for (var annotations : annotated.values()) {
            var selected = select(annotations, Candidate::databaseId);
            active.addAll(selected.isEmpty() ? annotations : selected);
        }
        return active;
    }
    private <T> List<T> select(List<T> values, java.util.function.Function<T, String> database) {
        if (options.databaseId().isPresent()) {
            var matched = values.stream().filter(value -> database.apply(value).equals(options.databaseId().get())).toList();
            if (!matched.isEmpty()) return matched;
        }
        return values.stream().filter(value -> database.apply(value).isEmpty()).toList();
    }
    private static Map<String, String> metadata(Candidate candidate) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("namespace", candidate.namespace()); metadata.put("databaseId", candidate.databaseId());
        metadata.put("command", candidate.command()); metadata.put("origin", candidate.xml() == null ? "annotation" : "xml");
        if (!candidate.signature().isEmpty()) metadata.put("method", candidate.signature());
        if (candidate.command().endsWith("provider")) {
            String type = ProviderMethodResolver.type(candidate.annotation().getValues().get("type"));
            String value = ProviderMethodResolver.type(candidate.annotation().getValues().get("value"));
            metadata.put("providerType", value.isEmpty() ? type : value);
            metadata.put("providerMethod", string(candidate.annotation().getValues().get("method"), ""));
        }
        if (candidate.xml() != null && candidate.xml().hasAttribute("parameterType")) metadata.put("parameterType", candidate.xml().getAttribute("parameterType"));
        return metadata;
    }
    static String string(Object value, String fallback) {
        if (value instanceof StringConstant constant) return constant.getValue();
        if (value instanceof String string) return string;
        if (fallback != null) return fallback;
        throw new IllegalArgumentException("Unresolved MyBatis annotation value: " + value);
    }
    private record Candidate(String id, String namespace, String databaseId, String command, SourceLocation location,
                             Element xml, AnnotationUsage annotation, String signature, String lang) {}
    private record DefinitionSeed(SqlDefinition definition) implements AnalysisSeed {
        public SourceLocation location() { return definition.location(); }
        public SqlInput input() { return new SqlInput.Definition(definition); }
    }
    private record ProviderSeed(SqlDefinition definition, JavaSootMethod mapper, ProviderSqlExtractor extractor) implements AnalysisSeed {
        public SourceLocation location() { return definition.location(); }
        public SqlInput input() { return new SqlInput.ComputedDefinition(flow -> extractor.definition(definition, mapper, flow)); }
    }
}
