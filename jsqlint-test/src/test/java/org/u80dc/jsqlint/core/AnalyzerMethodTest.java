package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.jdbc.JdbcAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.AnalysisResult.Status.*;

/** 验证两种完整签名、方法入口范围、独立参数上下文与没有调用方的 Mapper 定义。 */
class AnalyzerMethodTest {
    @TempDir Path temporary;

    @Test void sootAndJvmSignaturesProduceTheSameSqlAndCallChains() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static void entry(java.sql.Connection c) throws Exception {
                        c.prepareStatement("SELECT 1"); query(c, "selected");
                    }
                    public static void entry(java.sql.Connection c, int id) throws Exception { c.prepareStatement("SELECT 2"); }
                    static void query(java.sql.Connection c, String table) throws Exception {
                        c.prepareStatement("SELECT * FROM " + table);
                    }
                    public static void other(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 9"); }
                }
                """);
        var analyzer = jdbc(AnalyzerConfig.defaults());
        var soot = analyzer.analyze(classes, AnalysisOptions.defaults(),
                signature("Sample", "void", "entry", "java.sql.Connection"));
        var jvm = analyzer.analyze(classes, AnalysisOptions.defaults(),
                "  sample/Sample.entry:(Ljava/sql/Connection;)V  ");
        assertEquals(SUCCESS, jvm.status(), jvm.diagnostics().toString());
        assertEquals(soot.status(), jvm.status());
        assertEquals(Set.of("SELECT 1", "SELECT * FROM selected"), texts(jvm));
        assertEquals(texts(soot), texts(jvm));
        assertEquals(soot.rawSqls().stream().map(sql -> sql.text()).toList(),
                jvm.rawSqls().stream().map(sql -> sql.text()).toList());
        assertEquals(soot.calls().stream().map(SqlExtraction::callSite).toList(),
                jvm.calls().stream().map(SqlExtraction::callSite).toList());
        assertEquals(soot.calls().stream().map(SqlExtraction::callChain).toList(),
                jvm.calls().stream().map(SqlExtraction::callChain).toList());
    }

    @Test void jvmSignatureSelectsMapperWithoutBodyUsingErasedTypes() throws Exception {
        var classes = compile("""
                interface Mapper {
                    @Select("SELECT #{name} FROM users") java.util.List<String> query(@Param("name") String name);
                    @Select("SELECT 9") java.util.List<String> other(int id);
                }
                public class Sample {}
                """);
        var analyzer = mybatis();
        var soot = analyzer.analyze(classes, AnalysisOptions.defaults(),
                signature("Mapper", "java.util.List", "query", "java.lang.String"));
        var jvm = analyzer.analyze(classes, AnalysisOptions.defaults(),
                "sample/Mapper.query:(Ljava/lang/String;)Ljava/util/List;");
        assertEquals(SUCCESS, jvm.status(), jvm.diagnostics().toString());
        assertTrue(jvm.calls().isEmpty()); assertEquals(1, jvm.definitions().size());
        assertEquals(Set.of("SELECT ? FROM users"), texts(jvm));
        assertEquals(texts(soot), texts(jvm));
        var sootDefinition = soot.definitions().getFirst().definition().orElseThrow();
        var jvmDefinition = jvm.definitions().getFirst().definition().orElseThrow();
        assertEquals(sootDefinition.id(), jvmDefinition.id());
        assertEquals(sootDefinition.template(), jvmDefinition.template());
        assertEquals(sootDefinition.metadata(), jvmDefinition.metadata());
        assertEquals(signature("Mapper", "java.util.List", "query", "java.lang.String"), jvmDefinition.metadata().get("method"));
        assertEquals(soot.definitions().getFirst().location(), jvm.definitions().getFirst().location());
    }

    @Test void jvmSignatureSupportsAllPrimitiveTypesArraysAndNestedClasses() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static class Nested {
                        public static long query(java.sql.Connection c, byte b, char ch, short s, int i,
                                float f, long l, double d, boolean z, String[] names, int[][] ids) throws Exception {
                            c.prepareStatement("SELECT 6"); return 0L;
                        }
                        public static String[][] arrays(int[] ids) { return null; }
                    }
                }
                """);
        var analyzer = jdbc(AnalyzerConfig.defaults());
        var result = analyzer.analyze(classes, AnalysisOptions.defaults(),
                "sample/Sample$Nested.query:(Ljava/sql/Connection;BCSIFJDZ[Ljava/lang/String;[[I)J");
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(Set.of("SELECT 6"), texts(result));
        assertEquals(signature("Sample$Nested", "long", "query",
                "java.sql.Connection,byte,char,short,int,float,long,double,boolean,java.lang.String[],int[][]"),
                result.calls().getFirst().callSite().caller().orElseThrow().toString());
        var arrays = analyzer.analyze(classes, AnalysisOptions.defaults(),
                "sample/Sample$Nested.arrays:([I)[[Ljava/lang/String;");
        assertEquals(SUCCESS, arrays.status(), arrays.diagnostics().toString());
        assertTrue(arrays.sqlResults().isEmpty());
    }

    @Test void jvmSignatureSupportsConstructorsAndClassInitializers() throws Exception {
        var classes = compile("""
                public class Sample {
                    static int value = 7;
                    public Sample(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 7"); }
                }
                """);
        var analyzer = jdbc(AnalyzerConfig.defaults());
        var constructor = analyzer.analyze(classes, AnalysisOptions.defaults(),
                "sample/Sample.<init>:(Ljava/sql/Connection;)V");
        assertEquals(SUCCESS, constructor.status(), constructor.diagnostics().toString());
        assertEquals(Set.of("SELECT 7"), texts(constructor));
        var initializer = analyzer.analyze(classes, AnalysisOptions.defaults(), "sample/Sample.<clinit>:()V");
        assertEquals(SUCCESS, initializer.status(), initializer.diagnostics().toString());
        assertTrue(initializer.sqlResults().isEmpty());
    }

    @Test void jvmSignaturePreservesUnicodeClassAndMethodNames() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static class 用户 {
                        public static void 查询(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 8"); }
                    }
                }
                """);
        var result = jdbc(AnalyzerConfig.defaults()).analyze(classes, AnalysisOptions.defaults(),
                "sample/Sample$用户.查询:(Ljava/sql/Connection;)V");
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(Set.of("SELECT 8"), texts(result));
    }

    @Test void dependencyOverloadAcceptsJvmSignature() throws Exception {
        var classes = compile("public class Sample { public static void entry(java.sql.Connection c) throws Exception { c.prepareStatement(\"SELECT 3\"); } }");
        var dependencies = Files.createDirectory(temporary.resolve("dependencies"));
        var result = jdbc(AnalyzerConfig.defaults()).analyze(classes, dependencies, AnalysisOptions.defaults(),
                "sample/Sample.entry:(Ljava/sql/Connection;)V");
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(Set.of("SELECT 3"), texts(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "(Ljava/lang/String;)Ljava/util/List;", "entry:()V", ".entry:()V", "sample/Sample.:()V",
            "sample.Sample.entry:()V", "/sample/Sample.entry:()V", "sample//Sample.entry:()V",
            "sample/Sample/.entry:()V", "sample/Sample.<query>:()V", "sample/Sample.query/name:()V",
            "sample/Sample.query:V", "sample/Sample.query:(I", "sample/Sample.query:()", "sample/Sample.query:())V",
            "sample/Sample.query:(V)V", "sample/Sample.query:([V)V", "sample/Sample.query:()Q",
            "sample/Sample.query:()VV", "sample/Sample.query:()Vgarbage", "sample/Sample.query:()V:extra",
            "sample/Sample.query:(Ljava/lang/String)V", "sample/Sample.query:(L;)V",
            "sample/Sample.query:(Ljava.lang.String;)V", "sample/Sample.query:()Lfoo//Bar;",
            "sample/Sample.query:(I I)V", "sample/Sample.query:()Lfoo/Bar", "sample/Sample.query:()[V",
            "sample/Sample.<init>:()I", "sample/Sample.<clinit>:(I)V", "sample/Sample.<clinit>:()I"
    })
    void rejectsMalformedJvmReferencesBeforePreparingInput(String signature) {
        var analyzer = jdbc(AnalyzerConfig.defaults());
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(temporary, AnalysisOptions.defaults(), signature));
    }

    @Test void rejectsDescriptorDimensionsAndParameterSlotsBeyondJvmLimits() {
        var analyzer = jdbc(AnalyzerConfig.defaults());
        for (var descriptor : List.of("(" + "[".repeat(256) + "I)V", "()" + "[".repeat(256) + "I",
                "(" + "I".repeat(256) + ")V", "(" + "J".repeat(128) + ")V")) {
            assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(temporary, AnalysisOptions.defaults(),
                    "sample/Sample.query:" + descriptor), descriptor);
        }
    }

    @Test void collectsDirectAndDownstreamSqlWithoutOtherCallersOrDefinitions() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static void entry(java.sql.Connection c) throws Exception {
                        c.prepareStatement("SELECT 1"); query(c, "selected");
                    }
                    static void query(java.sql.Connection c, String table) throws Exception {
                        c.prepareStatement("SELECT * FROM " + table);
                    }
                    public static void other(java.sql.Connection c) throws Exception {
                        query(c, "unrelated"); c.prepareStatement("SELECT 9");
                    }
                }
                """);
        var sites = new ArrayList<SqlCallSite>();
        var options = AnalysisOptions.defaults().withListener(new AnalysisListener() {
            public void onSqlCallSite(String module, SqlCallSite site) { sites.add(site); }
        });
        var analyzer = jdbc(AnalyzerConfig.defaults());
        var result = analyzer.analyze(classes, options, signature("Sample", "void", "entry", "java.sql.Connection"));
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(Set.of("SELECT 1", "SELECT * FROM ${table}"), result.rawSqls().stream()
                .map(sql -> sql.text().strip()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("SELECT 1", "SELECT * FROM selected"), texts(result));
        assertTrue(sites.stream().noneMatch(site -> site.caller().map(caller -> caller.getName().equals("other")).orElse(false)));
        assertEquals(Set.of("SELECT 1", "SELECT 9", "SELECT * FROM selected", "SELECT * FROM unrelated"),
                texts(analyzer.analyze(classes)), "复用 Analyzer 后原全程序入口仍有独立范围");
    }

    @Test void preservesDifferentCallsWithinTheSelectedEntry() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static void entry(java.sql.Connection c) throws Exception { query(c,"first"); query(c,"second"); }
                    static void query(java.sql.Connection c,String table) throws Exception { c.prepareStatement("SELECT * FROM "+table); }
                    public static void other(java.sql.Connection c) throws Exception { query(c,"other"); }
                }
                """);
        var result = analyzeJdbc(classes, "void", "entry", "java.sql.Connection");
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(Set.of("SELECT * FROM first", "SELECT * FROM second"), texts(result));
        assertEquals(2, result.calls().size());
        assertTrue(result.calls().stream().allMatch(call -> call.callChain().getFirst().caller().orElseThrow().getName().equals("entry")));
    }

    @Test void treatsEntryParametersAsIndependentInputs() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static void entry(java.sql.Connection c,String table) throws Exception { query(c,table); }
                    static void query(java.sql.Connection c,String table) throws Exception { c.prepareStatement("SELECT * FROM "+table); }
                    public static void upstream(java.sql.Connection c) throws Exception { entry(c,"upstream_only"); }
                }
                """);
        var result = analyzeJdbc(classes, "void", "entry", "java.sql.Connection,java.lang.String");
        assertFalse(result.rawSqls().isEmpty());
        assertTrue(texts(result).stream().noneMatch(text -> text.contains("upstream_only")));
        assertTrue(result.calls().stream().flatMap(call -> call.callChain().stream())
                .noneMatch(site -> site.caller().orElseThrow().getName().equals("upstream")));
    }

    @Test void requestOverloadProducesFrozenResultsAfterCallerClosesInputs() throws Exception {
        var classes = compile("public class Sample { public static void entry(java.sql.Connection c) throws Exception { c.prepareStatement(\"SELECT 3\"); } }");
        for (var target : List.of(signature("Sample", "void", "entry", "java.sql.Connection"),
                "sample/Sample.entry:(Ljava/sql/Connection;)V")) {
            AnalysisResult result;
            try (var code = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
                 var runtime = new DefaultRuntimeAnalysisInputLocation()) {
                result = jdbc(AnalyzerConfig.defaults()).analyze(new AnalysisRequest(List.of(code, runtime), List.of()), target);
                try (var sources = code.getClassSources(new sootup.java.core.views.JavaView(List.of(code, runtime)))) {
                    assertTrue(sources.findAny().isPresent());
                }
            }
            assertEquals(SUCCESS, result.status());
            assertEquals(Set.of("SELECT 3"), texts(result));
        }
    }

    @Test void distinguishesEmptySuccessInvalidSignatureAndMissingMethod() throws Exception {
        var classes = compile("public class Sample { public static void entry() {} }");
        var analyzer = jdbc(AnalyzerConfig.defaults());
        var empty = analyzer.analyze(classes, AnalysisOptions.defaults(), signature("Sample", "void", "entry", ""));
        assertEquals(SUCCESS, empty.status()); assertTrue(empty.sqlResults().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(classes, AnalysisOptions.defaults(), "Sample.entry"));
        for (var target : List.of(signature("Sample", "void", "missing", ""), "sample/Sample.missing:()V")) {
            var missing = analyzer.analyze(classes, AnalysisOptions.defaults(), target);
            assertEquals(FAILED, missing.status());
            assertTrue(missing.diagnostics().stream().anyMatch(issue -> issue.message().contains("missing")));
        }
    }

    @Test void depthLimitAndRecursionRetainAlreadyExtractedSql() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static void entry(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 1"); first(c); }
                    static void first(java.sql.Connection c) throws Exception { second(c); }
                    static void second(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 2"); }
                    public static void recursive(java.sql.Connection c,int n) throws Exception { c.prepareStatement("SELECT 7"); if(n>0) recursive(c,n-1); }
                }
                """);
        var limited = jdbc(AnalyzerConfig.defaults().withMaxCallDepth(1)).analyze(classes, AnalysisOptions.defaults(),
                signature("Sample", "void", "entry", "java.sql.Connection"));
        assertEquals(PARTIAL, limited.status()); assertEquals(Set.of("SELECT 1"), texts(limited));
        assertTrue(limited.diagnostics().stream().anyMatch(issue -> issue.code().equals("METHOD_SCOPE_LIMIT")));
        var recursive = analyzeJdbc(classes, "void", "recursive", "java.sql.Connection,int");
        assertEquals(PARTIAL, recursive.status()); assertEquals(Set.of("SELECT 7"), texts(recursive));
    }

    @Test void usesExistingVirtualDispatchForDownstreamSql() throws Exception {
        var classes = compile("""
                interface Repository { void query(java.sql.Connection c) throws Exception; }
                class First implements Repository { public void query(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 1"); } }
                class Second implements Repository { public void query(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 2"); } }
                public class Sample { public static void entry(java.sql.Connection c,Repository r) throws Exception { r.query(c); } }
                """);
        var result = analyzeJdbc(classes, "void", "entry", "java.sql.Connection,sample.Repository");
        assertEquals(Set.of("SELECT 1", "SELECT 2"), texts(result));
    }

    @Test void unresolvedDownstreamCallProducesPartialEmptyResult() throws Exception {
        var classes = compile("interface Remote { void run(); } public class Sample { public static void entry(Remote remote) { remote.run(); } }");
        var result = analyzeJdbc(classes, "void", "entry", "sample.Remote");
        assertEquals(PARTIAL, result.status()); assertTrue(result.sqlResults().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("METHOD_SCOPE_UNRESOLVED") && issue.incomplete()));
    }

    @Test void selectsOnlyTheRequestedXmlMapperDefinitionWithoutAnyCaller() throws Exception {
        var classes = compile("interface Mapper { Object query(int id); Object other(); } public class Sample {} ");
        Files.writeString(classes.resolve("mapper.xml"), """
                <mapper namespace='sample.Mapper'>
                  <select id='query'>SELECT #{id} FROM users</select>
                  <select id='other'>SELECT <include refid='missing'/></select>
                </mapper>
                """);
        var result = mybatis().analyze(classes, AnalysisOptions.defaults(), signature("Mapper", "java.lang.Object", "query", "int"));
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertTrue(result.calls().isEmpty()); assertEquals(1, result.definitions().size());
        assertEquals("sample.Mapper.query", result.definitions().getFirst().definition().orElseThrow().id());
        assertEquals("SELECT ? FROM users", result.samples().getFirst().sql().orElseThrow());
    }

    @Test void supportsInheritedMapperNamespacesAndAnnotationParameters() throws Exception {
        var classes = compile("""
                interface Parent { @Select("SELECT #{id} FROM parent") Object query(@Param("id") int id); }
                interface Child extends Parent {}
                public class Sample { public static void unrelated(Parent p) { p.query(7); } }
                """);
        Files.writeString(classes.resolve("mapper.xml"), "<mapper namespace='sample.Child'><select id='query'>SELECT #{id} FROM child</select></mapper>");
        var result = mybatis().analyze(classes, AnalysisOptions.defaults(), signature("Child", "java.lang.Object", "query", "int"));
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(1, result.definitions().size()); assertTrue(result.calls().isEmpty());
        assertEquals("sample.Child.query", result.definitions().getFirst().definition().orElseThrow().id());
        assertEquals("SELECT ? FROM child", result.samples().getFirst().sql().orElseThrow());
    }

    @Test void reusesProviderDefinitionTracingAndSkipsUnrelatedProviders() throws Exception {
        var classes = compile("""
                interface Mapper {
                    @SelectProvider(type=Sample.class,method="sql") Object query();
                    @SelectProvider(type=Sample.class,method="missing") Object other();
                }
                public class Sample { public static String sql() { return helper(); } static String helper() { return "SELECT 7"; } }
                """);
        var result = mybatis().analyze(classes, AnalysisOptions.defaults(), signature("Mapper", "java.lang.Object", "query", ""));
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(1, result.definitions().size()); assertEquals(Set.of("SELECT 7"), texts(result));
        var missing = mybatis().analyze(classes, AnalysisOptions.defaults(), signature("Mapper", "java.lang.Object", "other", ""));
        assertEquals(PARTIAL, missing.status());
        assertTrue(missing.diagnostics().stream().anyMatch(issue -> issue.incomplete() && issue.message().contains("missing")));
    }

    @Test void missingMapperDefinitionOrModuleReturnsPartialWithoutFakeSql() throws Exception {
        var classes = compile("interface Mapper { Object query(); } public class Sample {} ");
        var target = signature("Mapper", "java.lang.Object", "query", "");
        for (var analyzer : List.of(mybatis(), jdbc(AnalyzerConfig.defaults()))) {
            var result = analyzer.analyze(classes, AnalysisOptions.defaults(), target);
            assertEquals(PARTIAL, result.status()); assertTrue(result.sqlResults().isEmpty());
            assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.incomplete() && issue.message().contains(target)));
        }
    }

    @Test void modelCopiesRetainTheIndependentEntryBoundary() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static void entry(java.sql.Connection c) throws Exception { query(c); }
                    static void query(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 5"); }
                    public static void upstream(java.sql.Connection c) throws Exception { entry(c); }
                }
                """);
        var inspected = new java.util.concurrent.atomic.AtomicInteger();
        var module = new SqlAnalysisModule() {
            public String id() { return "checking"; }
            public void register(DiscoveryRegistry registry) {
                new JdbcAnalysisModule().register(registry);
                registry.extraction(site -> site.invocation().getMethodSignature().getName().equals("prepareStatement"), (point, flow) -> {
                    var copy = flow.withModel(active -> (input, dependency) -> Optional.empty());
                    assertEquals(flow.contexts(point.method()), copy.contexts(point.method()));
                    assertTrue(copy.contexts(point.method()).stream().allMatch(context ->
                            context.context().calls().getFirst().method().getName().equals("entry")));
                    inspected.incrementAndGet();
                    return List.of();
                });
            }
        };
        var result = new Analyzer(List.of(module), List.of()).analyze(classes, AnalysisOptions.defaults(),
                signature("Sample", "void", "entry", "java.sql.Connection"));
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(1, inspected.get()); assertEquals(Set.of("SELECT 5"), texts(result));
    }

    @Test void supportsDirectAnnotationsDefaultMethodsAndExactOverloads() throws Exception {
        var classes = compile("""
                interface Mapper {
                    @Select("SELECT #{id} FROM users") Object query(@Param("id") int id);
                    default Object helper() { return query(8); }
                }
                public class Sample {
                    public static void entry(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 1"); }
                    public static void entry(java.sql.Connection c,int value) throws Exception { c.prepareStatement("SELECT 2"); }
                }
                """);
        var definition = mybatis().analyze(classes, AnalysisOptions.defaults(), signature("Mapper", "java.lang.Object", "query", "int"));
        assertEquals(SUCCESS, definition.status(), definition.diagnostics().toString());
        assertEquals(1, definition.definitions().size()); assertTrue(definition.calls().isEmpty());
        assertEquals("SELECT ? FROM users", definition.samples().getFirst().sql().orElseThrow());
        var helper = mybatis().analyze(classes, AnalysisOptions.defaults(), signature("Mapper", "java.lang.Object", "helper", ""));
        assertEquals(SUCCESS, helper.status(), helper.diagnostics().toString());
        assertEquals(1, helper.calls().size()); assertTrue(helper.definitions().isEmpty());
        assertEquals(new org.u80dc.jsqlint.core.node.SqlValue.Known(8), helper.samples().getFirst().bindings().getFirst().value());
        assertEquals(Set.of("SELECT 1"), texts(analyzeJdbc(classes, "void", "entry", "java.sql.Connection")));
        assertEquals(Set.of("SELECT 2"), texts(analyzeJdbc(classes, "void", "entry", "java.sql.Connection,int")));
    }

    @Test void reflectiveCallsAndUnsupportedLambdasCannotClaimCompleteSqlCoverage() throws Exception {
        var classes = compile("""
                public class Sample {
                    public static void reflected(java.lang.reflect.Method method,Object target) throws Exception { method.invoke(target); }
                    public static void lambda(java.sql.Connection c) { Runnable r=()->{try{c.prepareStatement("SELECT 1");}catch(Exception e){throw new RuntimeException(e);}};r.run(); }
                }
                """);
        var reflected = analyzeJdbc(classes, "void", "reflected", "java.lang.reflect.Method,java.lang.Object");
        assertEquals(PARTIAL, reflected.status());
        var lambda = analyzeJdbc(classes, "void", "lambda", "java.sql.Connection");
        assertEquals(PARTIAL, lambda.status());
        assertTrue(lambda.diagnostics().stream().anyMatch(issue -> issue.code().equals("METHOD_SCOPE_UNRESOLVED")));
    }

    @Test void preservesExistingDeclarationBeforeMethodHandlerOrdering() throws Exception {
        var classes = compile("public class Sample { public static void entry() {} public static void other() {} }");
        var events = new ArrayList<String>();
        var module = new SqlAnalysisModule() {
            public String id() { return "ordering"; }
            public void register(DiscoveryRegistry registry) {
                registry.sqlMethod((method, view) -> true);
                registry.method(method -> true, (method, seeds) -> events.add("method:" + method.getSignature()));
            }
        };
        var listener = new AnalysisListener() {
            public void onSqlCallSite(String module, SqlCallSite site) { events.add("declaration:" + site.target()); }
        };
        var result = new Analyzer(List.of(module), List.of()).analyze(classes, AnalysisOptions.defaults().withListener(listener));
        assertEquals(SUCCESS, result.status()); assertFalse(events.isEmpty());
        for (int i = 0; i < events.size(); i += 2) {
            assertTrue(events.get(i).startsWith("declaration:"));
            assertEquals(events.get(i).substring("declaration:".length()), events.get(i + 1).substring("method:".length()));
        }
    }

    @Test void inheritedLookupDoesNotInventPrivateMethodsOrConstructors() throws Exception {
        var classes = compile("""
                class Parent {
                    Parent(String value) {}
                    private static void secret(java.sql.Connection c) throws Exception { c.prepareStatement("SELECT 9"); }
                }
                class Child extends Parent { Child() { super("child"); } }
                public class Sample {}
                """);
        for (var target : List.of(signature("Child", "void", "secret", "java.sql.Connection"),
                signature("Child", "void", "<init>", "java.lang.String"))) {
            var result = jdbc(AnalyzerConfig.defaults()).analyze(classes, AnalysisOptions.defaults(), target);
            assertEquals(FAILED, result.status()); assertTrue(result.sqlResults().isEmpty());
        }
    }

    @Test void unindexedLibraryEntryCannotClaimCompleteCoverage() throws Exception {
        var classes = compile("public class Sample {} ");
        var result = jdbc(AnalyzerConfig.defaults()).analyze(classes, AnalysisOptions.defaults(), "<java.lang.Math: int abs(int)>");
        assertEquals(PARTIAL, result.status()); assertTrue(result.sqlResults().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(issue -> issue.code().equals("METHOD_SCOPE_EXTERNAL_ENTRY")));
    }

    @Test void unmatchedModuleDoesNotInvalidateAnotherModulesMethodDefinition() throws Exception {
        var classes = compile("interface OtherMapper { Object query(); } public class Sample {} ");
        var other = new SqlAnalysisModule() {
            public String id() { return "other"; }
            public void register(DiscoveryRegistry registry) {
                registry.methodDefinition((signature, method, view, seeds) -> seeds.accept(new AnalysisSeed() {
                    public SourceLocation location() { return SqlCallSite.declaration(method).location(); }
                    public SqlInput input() { return new SqlInput.Template(new org.u80dc.jsqlint.core.node.StaticTextSqlNode("SELECT 4")); }
                }));
            }
        };
        var result = new Analyzer(List.of(new MybatisAnalysisModule(), other), List.of()).analyze(classes,
                AnalysisOptions.defaults(), signature("OtherMapper", "java.lang.Object", "query", ""));
        assertEquals(SUCCESS, result.status(), result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty()); assertEquals(Set.of("SELECT 4"), texts(result));
    }

    private Path compile(String source) throws Exception {
        var root = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        var java = Files.writeString(root.resolve("Sample.java"), "package sample; import org.apache.ibatis.annotations.*; " + source);
        var classes = Files.createDirectory(root.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), java.toString()));
        return classes;
    }
    private static Analyzer jdbc(AnalyzerConfig config) { return new Analyzer(config, List.of(new JdbcAnalysisModule()), List.of()); }
    private static Analyzer mybatis() { return new Analyzer(List.of(new MybatisAnalysisModule()), List.of()); }
    private AnalysisResult analyzeJdbc(Path classes, String type, String method, String parameters) {
        return jdbc(AnalyzerConfig.defaults()).analyze(classes, AnalysisOptions.defaults(), signature("Sample", type, method, parameters));
    }
    private static String signature(String owner, String type, String method, String parameters) {
        return "<sample." + owner + ": " + type + " " + method + "(" + parameters + ")>";
    }
    private static Set<String> texts(AnalysisResult result) {
        var values = new LinkedHashSet<String>(); result.samples().forEach(sample -> sample.sql().ifPresent(sql -> values.add(sql.strip()))); return values;
    }
}
