package org.u80dc.jsqlint.mybatis;

import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Mapper 调用通过真实字节码元数据接入统一 SQL 样例，覆盖别名和路径关联。 @author u80dc */
class MybatisExtractionTest {
    @TempDir Path temporary;

    @Test void annotationUsesOneSolvedInputModelForRawTextAndRepeatedTypedBindings() throws Exception {
        Path classes = compile("""
                interface Mapper {
                    @Select("SELECT ${id},${next} FROM ${table} WHERE a=#{id,jdbcType=INTEGER} AND b=#{_parameter.next} OR a=#{param1}")
                    Object query(@Param("id") int id, @Param("next") int next, @Param("table") String table);
                }
                public class Sample {
                    public void run(Mapper mapper, int number) {
                        if (number != 100) return;
                        mapper.query(number, number + 1, "users");
                    }
                }
                """, false);
        var result = analyze(classes, AnalyzerConfig.defaults(), MybatisOptions.defaults());
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        var sample = onlyCalls(result);
        assertEquals("SELECT 100,101 FROM users WHERE a=? AND b=? OR a=?", normalize(sample.sql().orElseThrow()));
        assertEquals(List.of(new SqlValue.Known(100), new SqlValue.Known(101), new SqlValue.Known(100)), values(sample));
        assertEquals(List.of("int", "int", "int"), sample.bindings().stream().map(SqlSample.Binding::type).toList());
        assertEquals(SqlSample.Feasibility.SATISFIABLE, sample.feasibility(), sample.issues().toString());
        assertEquals(new SqlAssessment(true, true, true, Optional.of(true)), sample.assessment());
        assertEquals("sample.Mapper.query", sample.definition().orElseThrow().id());
        assertEquals("jdbcType=INTEGER", sample.bindings().getFirst().options());
        assertTrue(sample.bindings().stream().allMatch(binding -> binding.trace().isPresent()));
        assertEquals(sample.bindings().getFirst().trace(), sample.bindings().getLast().trace());
        var statement = result.extractions().getFirst().statements().getFirst();
        assertTrue(statement.sql().isEmpty(), "定义来源不伪造 Java SQL 字符串传播树");
        assertEquals(3, statement.templateInference().orElseThrow().sources().size());
        assertSame(sample.rawSql().orElseThrow(), statement.rawSql().variants().getFirst());
        assertEquals(3, sample.combination().orElseThrow().bindings().size());
        assertEquals(1, result.sqlResults().stream().filter(SqlTemplateResult.class::isInstance).count());
        assertThrows(UnsupportedOperationException.class, () -> statement.templateInference().orElseThrow().aliases().clear());
    }

    @Test void parameterNamesMatchRuntimeResolverWithAndWithoutMethodParametersAndSpecialArguments() throws Exception {
        for (boolean compiledNames : List.of(false, true)) for (boolean useActual : List.of(false, true)) {
            Path classes = compile("""
                    interface Mapper { Object query(int first, RowBounds bounds, String second, ResultHandler<String> handler); }
                    public class Sample { public void run(Mapper mapper) { mapper.query(7, null, "ok", null); } }
                    """, compiledNames);
            String first = useActual ? compiledNames ? "first" : "arg0" : "0";
            String second = useActual ? compiledNames ? "second" : "arg2" : "1";
            xml(classes, "<select id='query'>SELECT #{" + first + "},#{" + second + "},#{param1},#{_parameter." + second + "}</select>");
            var options = MybatisOptions.defaults().withUseActualParamName(useActual);
            var result = analyze(classes, AnalyzerConfig.defaults(), options);
            var sample = onlyCalls(result);
            assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
            assertEquals(List.of(new SqlValue.Known(7), new SqlValue.Known("ok"), new SqlValue.Known(7), new SqlValue.Known("ok")), values(sample));
            var inferred = result.extractions().getFirst().statements().getFirst().templateInference().orElseThrow();
            assertEquals(2, inferred.sources().size(), "RowBounds/ResultHandler 不能成为普通 SQL 参数");
            var configuration = new Configuration(); configuration.setUseActualParamName(useActual);
            try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
                var method = Class.forName("sample.Mapper", false, loader).getDeclaredMethod("query", int.class, RowBounds.class, String.class, ResultHandler.class);
                var runtime = (Map<?, ?>) new ParamNameResolver(configuration, method).getNamedParams(new Object[]{7, null, "ok", null});
                assertEquals(runtime.keySet(), inferred.aliases().keySet());
                runtime.forEach((name, value) -> assertEquals(new SqlValue.Known(value), sample.combination().orElseThrow().parameters().get(inferred.aliases().get(name)).value()));
            }
        }
    }

    @Test void singleScalarAllowsFallbackNamesAndNullRemainsAnActualBindingValue() throws Exception {
        Path classes = compile("""
                interface Mapper { Object query(String name); }
                public class Sample { public void run(Mapper mapper) { mapper.query(null); } }
                """, false);
        xml(classes, "<select id='query'>SELECT #{anything},#{_parameter},#{value}</select>");
        var result = analyze(classes, AnalyzerConfig.defaults(), MybatisOptions.defaults());
        var sample = onlyCalls(result);
        assertEquals("SELECT ?,?,?", sample.sql().orElseThrow());
        assertEquals(Collections.nCopies(3, new SqlValue.Known(null)), values(sample));
        assertTrue(sample.assessment().valuesComplete());
        assertEquals(1, result.extractions().getFirst().statements().getFirst().templateInference().orElseThrow().sources().size());
    }

    @Test void explicitParamNamesAreNotOverwrittenByGenericAliases() throws Exception {
        Path classes = compile("""
                interface Mapper { Object query(@Param("param2") int first, @Param("id") int second); }
                public class Sample { public void run(Mapper mapper) { mapper.query(7, 8); } }
                """, false);
        xml(classes, "<select id='query'>SELECT #{param1},#{param2},#{id}</select>");
        assertEquals(List.of(new SqlValue.Known(7), new SqlValue.Known(7), new SqlValue.Known(8)),
                values(onlyCalls(analyze(classes, AnalyzerConfig.defaults(), MybatisOptions.defaults()))));
    }

    @Test void scalarFallbackBindsKeepEachTransformInThePropagationEvidence() throws Exception {
        Path classes = compile("""
                interface Mapper {
                    @Select("<script><bind name='pattern' value='_parameter + &quot;!&quot;'/><bind name='next' value='pattern + &quot;?&quot;'/>SELECT #{next},#{_parameter}</script>")
                    Object query(String value);
                }
                public class Sample { public void run(Mapper mapper) { mapper.query("abc"); } }
                """, false);
        var sample = onlyCalls(analyze(classes, AnalyzerConfig.defaults(), MybatisOptions.defaults()));
        assertEquals(List.of(new SqlValue.Known("abc!?"), new SqlValue.Known("abc")), values(sample));
        var trace = sample.bindings().getFirst().trace().orElseThrow();
        assertEquals(ValueTrace.Kind.EXPRESSION, trace.root().kind());
        assertEquals("pattern + \"?\"", trace.root().computation().text());
        var firstTransform = trace.root().inputs().getFirst();
        assertEquals(ValueTrace.Kind.EXPRESSION, firstTransform.kind());
        assertEquals("_parameter + \"!\"", firstTransform.computation().text());
        assertEquals(ValueTrace.Operation.LITERAL, firstTransform.inputs().getFirst().computation().operation());
        assertEquals("abc", firstTransform.inputs().getFirst().computation().text());
        assertEquals(ValueTrace.Kind.CONSTANT, sample.bindings().getLast().trace().orElseThrow().root().kind());
    }

    @Test void bindsShadowOnlyTheirOwnNameAndDoNotAcquireUnrelatedArgumentSources() throws Exception {
        Path classes = compile("""
                interface Mapper {
                    @Select("<script><bind name='name' value='name + &quot;!&quot;'/>SELECT #{name},#{param1},#{_parameter.name}</script>")
                    Object query(@Param("name") String name, @Param("unused") int unused);
                }
                public class Sample { public void run(Mapper mapper) { mapper.query("abc", 9); } }
                """, false);
        var sample = onlyCalls(analyze(classes, AnalyzerConfig.defaults(), MybatisOptions.defaults()));
        assertEquals(List.of(new SqlValue.Known("abc!"), new SqlValue.Known("abc"), new SqlValue.Known("abc")), values(sample));
        var transformed = sample.bindings().getFirst().trace().orElseThrow().root();
        assertEquals(ValueTrace.Kind.EXPRESSION, transformed.kind()); assertEquals(1, transformed.inputs().size());
        assertEquals("abc", transformed.inputs().getFirst().computation().text());
        assertEquals(ValueTrace.Kind.CONSTANT, sample.bindings().get(1).trace().orElseThrow().root().kind());
        assertTrue(sample.assessment().sourcesComplete()); assertTrue(sample.assessment().valuesComplete());
    }

    @Test void extractAndParameterModesRetainSymbolicBindingsWithoutCombiningIndependentCandidates() throws Exception {
        Path classes = compile("""
                interface Mapper { Object query(@Param("id") int id, @Param("next") int next); }
                public class Sample { public void run(Mapper mapper, int number) { mapper.query(number, number + 1); } }
                """, false);
        xml(classes, "<select id='query'>SELECT #{id},#{next}</select>");
        for (var mode : List.of(InferenceOptions.Mode.EXTRACT, InferenceOptions.Mode.PARAMETERS)) {
            var result = analyze(classes, AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withMode(mode)), MybatisOptions.defaults());
            var sample = onlyCalls(result);
            assertEquals("SELECT ?,?", sample.sql().orElseThrow());
            assertEquals(SqlSample.Feasibility.UNVERIFIED, sample.feasibility());
            assertTrue(values(sample).stream().allMatch(SqlValue.Unknown.class::isInstance));
            assertTrue(sample.combination().isEmpty()); assertTrue(sample.assessment().pathCoverageComplete().isEmpty());
            var parameters = result.extractions().getFirst().statements().getFirst().templateInference().orElseThrow().parameters();
            assertEquals(mode == InferenceOptions.Mode.PARAMETERS, parameters.values().stream().allMatch(value -> !value.possibleValues().isEmpty()));
        }
    }

    @Test void crossMethodArgumentsAndJdbcResultsKeepTheirOwnSources() throws Exception {
        Path classes = compile("""
                interface Mapper { Object query(@Param("id") int id); }
                public class Sample {
                    static { System.setProperty("jsqlint.mybatis.target.executed", "true"); }
                    public static void main(String[] args) throws Exception { service(null, null, 7); }
                    static void service(Mapper mapper, java.sql.Connection connection, int id) throws Exception {
                        helper(mapper, id + 1); connection.prepareStatement("SELECT jdbc");
                    }
                    static void helper(Mapper mapper, int id) { mapper.query(id); }
                }
                """, false);
        xml(classes, "<select id='query'>SELECT #{id}</select>");
        var result = new Analyzer(AnalyzerConfig.defaults(), List.of(new MybatisAnalysisModule(), new JdbcAnalysisModule()), List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        var sample = result.samples().stream().filter(value -> value.source() instanceof SqlExtraction && value.moduleId().equals("mybatis")).findFirst().orElseThrow();
        assertEquals(List.of(new SqlValue.Known(8)), values(sample));
        var source = (SqlExtraction) sample.source();
        assertEquals(List.of("service", "helper"), source.callChain().stream().map(site -> site.target().getName()).toList());
        assertTrue(result.samples().stream().anyMatch(value -> value.moduleId().equals("jdbc") && value.sql().orElse("").equals("SELECT jdbc")));
        assertNull(System.getProperty("jsqlint.mybatis.target.executed"), "分析不得执行目标项目的初始化或方法");
    }

    @Test void dynamicXmlRendersTheChosenModelButDoesNotClaimCompleteTemplatePathCoverage() throws Exception {
        Path classes = compile("""
                interface Mapper { Object query(@Param("id") int id); }
                public class Sample { public void run(Mapper mapper) { mapper.query(7); } }
                """, false);
        xml(classes, "<select id='query'>SELECT * FROM t<where><if test='id > 0'>AND id=#{id}</if></where></select>");
        var result = analyze(classes, AnalyzerConfig.defaults(), MybatisOptions.defaults());
        var sample = onlyCalls(result);
        assertEquals("SELECT * FROM t WHERE id=?", sample.sql().orElseThrow());
        assertEquals(List.of(new SqlValue.Known(7)), values(sample));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertEquals(SqlSample.Feasibility.UNKNOWN, sample.feasibility());
        assertEquals(Optional.of(false), sample.assessment().pathCoverageComplete());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("JOINT_ANALYSIS_INCOMPLETE")));
    }

    @Test void failuresAndCancellationKeepTheExistingSessionSemantics() throws Exception {
        Path classes = compile("""
                interface Mapper { Object query(int id); }
                public class Sample { public void run(Mapper mapper, int id) { mapper.query(id); } }
                """, false);
        xml(classes, "<select id='query'>SELECT #{id}</select>");
        var config = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withGeneratorFactory(() ->
                (type, name) -> { throw new IllegalStateException("sample generator failed"); }));
        var failed = analyze(classes, config, MybatisOptions.defaults());
        assertEquals(AnalysisResult.Status.PARTIAL, failed.status());
        assertEquals("SELECT ?", onlyCalls(failed).sql().orElseThrow());
        assertInstanceOf(SqlValue.Unknown.class, onlyCalls(failed).bindings().getFirst().value());
        var cancelled = new AtomicBoolean();
        var listener = new AnalysisListener() { @Override public void onSqlResult(SqlResult result) { cancelled.set(true); } };
        var options = AnalysisOptions.defaults().withListener(listener).withCancelled(cancelled::get);
        var result = new Analyzer(AnalyzerConfig.defaults(), List.of(new MybatisAnalysisModule()), List.of()).analyze(classes, options);
        assertEquals(AnalysisResult.Status.CANCELLED, result.status());
        assertEquals(1, result.sqlResults().size()); assertTrue(result.extractions().isEmpty());
    }

    private Path compile(String source, boolean names) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Path java = Files.writeString(root.resolve("Sample.java"), "package sample; import org.apache.ibatis.annotations.*; import org.apache.ibatis.session.*; " + source);
        Path classes = Files.createDirectory(root.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-g", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        if (names) arguments.add("-parameters"); arguments.add(java.toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return classes;
    }
    private void xml(Path classes, String statements) throws Exception { Files.writeString(classes.resolve("mapper.xml"), "<mapper namespace='sample.Mapper'>" + statements + "</mapper>"); }
    private AnalysisResult analyze(Path classes, AnalyzerConfig config, MybatisOptions options) {
        return new Analyzer(config, List.of(new MybatisAnalysisModule(options)), List.of()).analyze(classes);
    }
    private static SqlSample onlyCalls(AnalysisResult result) {
        var samples = result.samples().stream().filter(value -> value.source() instanceof SqlExtraction).toList();
        assertEquals(1, samples.size(), samples.toString()); return samples.getFirst();
    }
    private static List<SqlValue> values(SqlSample sample) { return sample.bindings().stream().map(SqlSample.Binding::value).toList(); }
    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").strip(); }
}
