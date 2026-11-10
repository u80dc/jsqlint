package org.u80dc.jsqlint.mybatis;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实 Provider 字节码验证隐式调用、参数对象与返回路径。 @author u80dc */
class MybatisProviderTest {
    @TempDir Path temporary;

    @Test void extractsAllFourAnnotationsAndRetainsUnusedDefinitions() throws Exception {
        var result = analyze("""
                interface Mapper {
                    @SelectProvider(type=Sample.class,method="select") Object select(@Param("id") int id);
                    @InsertProvider(value=Sample.class,method="insert") Object insert(@Param("id") int id);
                    @UpdateProvider(type=Sample.class,method="update") Object update(int id);
                    @DeleteProvider(type=Sample.class,method="delete") Object delete(int id);
                    @SelectProvider(type=Sample.class,method="unused") Object unused();
                }
                public class Sample {
                    public static String select() { return "SELECT name FROM users WHERE id=#{id}"; }
                    public String insert() { return "INSERT INTO users(id) VALUES(#{id})"; }
                    public static String update(int id) { return "UPDATE users SET active=1 WHERE id="+id; }
                    public String delete(int id) { return "DELETE FROM users WHERE id=#{value}"; }
                    public static String unused() { return "SELECT COUNT(*) FROM users"; }
                    public void run(Mapper mapper) { mapper.select(7); mapper.insert(8); mapper.update(9); mapper.delete(10); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(4, result.extractions().size());
        assertEquals(5, definitions(result).size());
        assertEquals("SELECT COUNT(*) FROM users", definition(result, "unused").samples().getFirst().sql().orElseThrow());
        assertEquals("UPDATE users SET active=1 WHERE id=9", sample(result, "update").sql().orElseThrow());
        assertEquals(new SqlValue.Known(7), sample(result, "select").bindings().getFirst().value());
        assertEquals(new SqlValue.Known(10), sample(result, "delete").bindings().getFirst().value());
        assertTrue(result.extractions().stream().flatMap(extraction -> extraction.statements().stream())
                .allMatch(statement -> statement.sql().isPresent() && statement.sql().get().complete()));
    }

    @Test void mapsParamMapIndividualParametersAndProviderContext() throws Exception {
        var result = analyze("""
                interface Mapper {
                    @SelectProvider(type=Sample.class,method="map") Object map(@Param("table") String table,@Param("id") int id);
                    @SelectProvider(type=Sample.class,method="named") Object named(@Param("table") String table,@Param("id") int id);
                    @SelectProvider(type=Sample.class,method="context") Object context(int id);
                }
                public class Sample {
                    public static String map(java.util.Map<String,Object> args) {
                        return "SELECT name FROM "+args.get("table")+" WHERE id=#{id}";
                    }
                    public static String named(@Param("id") int id,@Param("table") String table) {
                        return "SELECT name FROM "+table+" WHERE id="+id;
                    }
                    public static String context(org.apache.ibatis.builder.annotation.ProviderContext context,int id) {
                        return "SELECT '"+context.getDatabaseId()+"' AS db, '"+context.getMapperMethod().getName()
                          +"' AS method, '"+context.getMapperType().getSimpleName()+"' AS mapper WHERE id=#{value}";
                    }
                    public void run(Mapper mapper) { mapper.map("users",7); mapper.named("archived_users",8); mapper.context(9); }
                }
                """, MybatisOptions.defaults().withDatabaseId("postgresql"));
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals("SELECT name FROM users WHERE id=?", sample(result, "map").sql().orElseThrow());
        assertEquals("SELECT name FROM archived_users WHERE id=8", sample(result, "named").sql().orElseThrow());
        assertEquals("SELECT 'postgresql' AS db, 'context' AS method, 'Mapper' AS mapper WHERE id=?", sample(result, "context").sql().orElseThrow());
        assertEquals(new SqlValue.Known(9), sample(result, "context").bindings().getFirst().value());
        assertEquals("SELECT name FROM manual_users WHERE id=?", definition(result, "map").sample(Map.of("table", "manual_users", "id", 11)).sql().orElseThrow());
    }

    @Test void isolatesRepeatedImplicitCallsAndKeepsReturnBranchesConsistent() throws Exception {
        var result = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(@Param("flag") boolean flag); }
                public class Sample {
                    public static String sql(@Param("flag") boolean flag) {
                        if (flag) return helper("active_users");
                        return helper("archived_users");
                    }
                    private static String helper(String table) { return "SELECT name FROM "+table+" WHERE active=#{flag}"; }
                    public void run(Mapper mapper) { mapper.query(true); mapper.query(false); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(2, result.extractions().size());
        for (var extraction : result.extractions()) {
            assertEquals(1, extraction.samples().size(), extraction.samples().toString());
            var sample = extraction.samples().getFirst();
            Object flag = ((SqlValue.Known) sample.bindings().getFirst().value()).value();
            assertEquals("SELECT name FROM " + (Boolean.TRUE.equals(flag) ? "active_users" : "archived_users") + " WHERE active=?", sample.sql().orElseThrow());
            assertEquals(SqlSample.Feasibility.SATISFIABLE, sample.feasibility());
        }
    }

    @Test void supportsDefaultTypeProvideSqlAndStandardMethodResolver() throws Exception {
        var result = analyze("""
                interface Mapper { @SelectProvider Object query(int id); }
                public class Sample { public static String provideSql(int id) { return "SELECT #{value}"; }
                    public void run(Mapper mapper) { mapper.query(7); } }
                """, MybatisOptions.defaults().withDefaultSqlProviderType("sample.Sample"));
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(new SqlValue.Known(7), sample(result, "query").bindings().getFirst().value());
        var resolver = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class) Object query(int id); }
                public class Sample implements org.apache.ibatis.builder.annotation.ProviderMethodResolver {
                    public String query(int id) { return "SELECT #{value}"; }
                    public void run(Mapper mapper) { mapper.query(8); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, resolver.status(), resolver.diagnostics().toString());
        assertEquals(new SqlValue.Known(8), sample(resolver, "query").bindings().getFirst().value());
    }

    @Test void parsesProviderScriptsWithoutReplacingExistingRawExpressions() throws Exception {
        var result = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(@Param("table") String table,@Param("id") int id); }
                public class Sample {
                    public static String sql(@Param("table") String table) {
                        return "<script>SELECT name FROM "+table+" WHERE id=#{id} ORDER BY ${table}.id</script>";
                    }
                    public void run(Mapper mapper) { mapper.query("users",7); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals("SELECT name FROM users WHERE id=? ORDER BY users.id", sample(result, "query").sql().orElseThrow());
    }

    @Test void doesNotExecuteProviderCodeOrStaticInitializers() throws Exception {
        System.clearProperty("jsqlint.provider.executed");
        var result = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(); }
                public class Sample {
                    static { System.setProperty("jsqlint.provider.executed","yes"); }
                    public Sample() { System.setProperty("jsqlint.provider.executed","yes"); }
                    public String sql() { System.setProperty("jsqlint.provider.executed","yes"); return "SELECT 1"; }
                    public void run(Mapper mapper) { mapper.query(); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertNull(System.getProperty("jsqlint.provider.executed"));
        assertEquals("SELECT 1", sample(result, "query").sql().orElseThrow());
    }

    @Test void rejectsOverloadsMissingArgumentsAndCustomResolvers() throws Exception {
        for (var provider : List.of(
                "public static String sql() { return \"SELECT 1\"; } public static String sql(int id) { return \"SELECT 2\"; }",
                "public static String sql(@Param(\"missing\") int id) { return \"SELECT 1\"; }",
                "public static String sql() { return System.getProperty(\"provider.sql\"); }")) {
            var result = analyze("interface Mapper { @SelectProvider(type=Sample.class,method=\"sql\") Object query(@Param(\"id\") int id); } "
                    + "public class Sample { " + provider + " public void run(Mapper mapper) { mapper.query(7); } }");
            assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
            assertTrue(result.extractions().getFirst().samples().stream().allMatch(sample -> sample.sql().isEmpty()));
        }
        var custom = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class) Object query(); }
                public class Sample implements org.apache.ibatis.builder.annotation.ProviderMethodResolver {
                    public java.lang.reflect.Method resolveMethod(org.apache.ibatis.builder.annotation.ProviderContext c) { throw new AssertionError(); }
                    public String query() { return "SELECT 1"; }
                    public void run(Mapper mapper) { mapper.query(); }
                }
                """);
        assertEquals(AnalysisResult.Status.PARTIAL, custom.status());
        assertTrue(custom.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("Custom MyBatis ProviderMethodResolver")));
    }

    @Test void extractsLocalFluentAndAnonymousMybatisSqlBuilders() throws Exception {
        var result = analyze("""
                interface Mapper {
                    @SelectProvider(type=Sample.class,method="select") Object select(@Param("table") String table,@Param("id") int id);
                    @InsertProvider(type=Sample.class,method="insert") Object insert(@Param("id") int id);
                    @UpdateProvider(type=Sample.class,method="update") Object update(@Param("id") int id);
                    @DeleteProvider(type=Sample.class,method="delete") Object delete(@Param("id") int id);
                }
                public class Sample {
                    public static String select(@Param("table") String table) {
                        var sql = new org.apache.ibatis.jdbc.SQL(); sql.SELECT("name"); sql.FROM(table); sql.WHERE("id=#{id}");
                        sql.ORDER_BY("name"); sql.LIMIT(10); sql.OFFSET(2L); return sql.toString();
                    }
                    public static String insert() { return new org.apache.ibatis.jdbc.SQL().INSERT_INTO("users").VALUES("id","#{id}").toString(); }
                    public String update() { return new org.apache.ibatis.jdbc.SQL() {{ UPDATE("users"); SET("active=1"); WHERE("id=#{id}"); }}.toString(); }
                    public static CharSequence delete() { return new org.apache.ibatis.jdbc.SQL().DELETE_FROM("users").WHERE("id=#{id}").toString(); }
                    public void run(Mapper mapper) { mapper.select("users",7); mapper.insert(8); mapper.update(9); mapper.delete(10); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals("SELECT name FROM users WHERE (id=?) ORDER BY name LIMIT 10 OFFSET 2", normalize(sample(result,"select").sql().orElseThrow()));
        assertEquals("INSERT INTO users (id) VALUES (?)", normalize(sample(result,"insert").sql().orElseThrow()));
        assertEquals("UPDATE users SET active=1 WHERE (id=?)", normalize(sample(result,"update").sql().orElseThrow()));
        assertEquals("DELETE FROM users WHERE (id=?)", normalize(sample(result,"delete").sql().orElseThrow()));
        assertEquals(new SqlValue.Known(9), sample(result,"update").bindings().getFirst().value());
    }

    @Test void keepsSqlBuilderConditionalMutationsOnTheirSelectedPaths() throws Exception {
        var result = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(@Param("active") boolean active); }
                public class Sample {
                    public static String sql(@Param("active") boolean active) {
                        var sql = new org.apache.ibatis.jdbc.SQL().SELECT("name").FROM("users");
                        if (active) sql.WHERE("active=#{active}"); return sql.toString();
                    }
                    public void run(Mapper mapper) { mapper.query(true); mapper.query(false); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(2, result.extractions().size());
        assertEquals(Set.of("SELECT name FROM users WHERE (active=?)", "SELECT name FROM users"), result.extractions().stream()
                .flatMap(value -> value.samples().stream()).map(value -> normalize(value.sql().orElseThrow())).collect(java.util.stream.Collectors.toSet()));
        for (var extraction : result.extractions()) {
            assertEquals(1, extraction.samples().size(), extraction.samples().toString());
            assertEquals(SqlSample.Feasibility.SATISFIABLE, extraction.samples().getFirst().feasibility());
        }
    }

    @Test void diagnosesSqlSubclassesThatOverrideFrameworkBehavior() throws Exception {
        for (String override : List.of(
                "public String toString() { return \"SELECT overridden\"; }",
                "public org.apache.ibatis.jdbc.SQL WHERE(String condition) { return super.WHERE(\"active=1\"); }",
                "public org.apache.ibatis.jdbc.SQL getSelf() { return new org.apache.ibatis.jdbc.SQL(); }")) {
            var result = analyze("""
                    interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(); }
                    public class Sample {
                        public static class CustomSQL extends org.apache.ibatis.jdbc.SQL { %s }
                        public static String sql() {
                            org.apache.ibatis.jdbc.SQL sql = new CustomSQL();
                            sql.SELECT("name"); sql.FROM("users"); sql.WHERE("id=7"); return sql.toString();
                        }
                        public void run(Mapper mapper) { mapper.query(); }
                    }
                    """.formatted(override));
            assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
            assertTrue(result.extractions().getFirst().samples().stream().allMatch(sample -> sample.sql().isEmpty()));
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("SQL subclass overrides")),
                    result.diagnostics().toString());
        }
    }

    @Test void modelsSqlBuilderVarargsJoinsAndMultipleValueRows() throws Exception {
        var result = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(); @InsertProvider(type=Sample.class,method="insert") Object insert(); }
                public class Sample {
                    public static String sql() {
                        return new org.apache.ibatis.jdbc.SQL().SELECT_DISTINCT("u.id","u.name").FROM("users u")
                            .LEFT_OUTER_JOIN("roles r ON r.id=u.role_id").WHERE("u.active=1","u.id>0").OR().WHERE("u.name='admin'")
                            .GROUP_BY("u.id","u.name").HAVING("COUNT(*)>0").ORDER_BY("u.id","u.name").toString();
                    }
                    public static String insert() { return new org.apache.ibatis.jdbc.SQL().INSERT_INTO("users").INTO_COLUMNS("id","name")
                        .INTO_VALUES("1","'A'").ADD_ROW().INTO_VALUES("2","'B'").toString(); }
                    public void run(Mapper mapper) { mapper.query(); mapper.insert(); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        var expected = new org.apache.ibatis.jdbc.SQL().SELECT_DISTINCT("u.id","u.name").FROM("users u")
                .LEFT_OUTER_JOIN("roles r ON r.id=u.role_id").WHERE("u.active=1","u.id>0").OR().WHERE("u.name='admin'")
                .GROUP_BY("u.id","u.name").HAVING("COUNT(*)>0").ORDER_BY("u.id","u.name").toString();
        assertEquals(normalize(expected), normalize(sample(result,"query").sql().orElseThrow()));
        var inserted = new org.apache.ibatis.jdbc.SQL().INSERT_INTO("users").INTO_COLUMNS("id","name")
                .INTO_VALUES("1","'A'").ADD_ROW().INTO_VALUES("2","'B'").toString();
        assertEquals(normalize(inserted), normalize(sample(result,"insert").sql().orElseThrow()));
    }

    @Test void diagnosesMutableOrEscapedMapsAndSqlBuilders() throws Exception {
        for (String body : List.of(
                "args.put(\"table\",\"changed_users\"); return \"SELECT name FROM \"+args.get(\"table\");",
                "external(args); return \"SELECT name FROM \"+args.get(\"table\");",
                "held=args; external(); return \"SELECT name FROM \"+args.get(\"table\");")) {
            var result = analyze("interface Mapper { @SelectProvider(type=Sample.class,method=\"sql\") Object query(@Param(\"table\") String table); } "
                    + "public class Sample { static Object held; public static native void external(Object value); public static native void external(); "
                    + "public static String sql(java.util.Map<String,Object> args) { " + body + " } public void run(Mapper mapper) { mapper.query(\"users\"); } }");
            assertEquals(AnalysisResult.Status.PARTIAL, result.status(), result.diagnostics().toString());
            assertTrue(result.extractions().getFirst().samples().stream().allMatch(sample -> sample.sql().isEmpty()));
            assertFalse(result.extractions().getFirst().complete());
        }
        var builder = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(); }
                public class Sample { public static native void external(Object value);
                    public static String sql() { var sql=new org.apache.ibatis.jdbc.SQL().SELECT("name").FROM("users"); external(sql); return sql.toString(); }
                    public void run(Mapper mapper) { mapper.query(); }
                }
                """);
        assertEquals(AnalysisResult.Status.PARTIAL, builder.status(), builder.diagnostics().toString());
        assertTrue(builder.extractions().getFirst().samples().stream().allMatch(sample -> sample.sql().isEmpty()));
    }

    @Test void agreesWithRealMybatisForParamMapContextsAndPrimitiveWidening() throws Exception {
        Path classes = compile("""
                interface Mapper {
                    @SelectProvider(type=Sample.class,method="map") Object map(@Param("table") String table,@Param("id") int id);
                    @SelectProvider(value=Sample.class,method="named") Object named(@Param("table") String table,@Param("id") int id);
                    @SelectProvider(type=Sample.class,method="context") Object context(int id);
                }
                public class Sample {
                    public static String map(java.util.Map<String,Object> args) { return "SELECT name FROM "+args.get("table")+" WHERE id=#{id}"; }
                    public String named(@Param("table") String table,@Param("id") long id) { return "SELECT name FROM "+table+" WHERE id="+id; }
                    public static String context(int id,org.apache.ibatis.builder.annotation.ProviderContext context) {
                        return "SELECT '"+context.getDatabaseId()+"' AS db, '"+context.getMapperType().getName()+"' AS mapper WHERE id=#{value}";
                    }
                    public void run(Mapper mapper) { mapper.map("users",7); mapper.named("archived_users",8); mapper.context(9); }
                }
                """);
        var options = MybatisOptions.defaults().withDatabaseId("postgresql");
        var result = new Analyzer(List.of(new MybatisAnalysisModule(options)),List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        // 仅对这个无副作用的对照夹具运行 MyBatis；分析器从不加载或运行它。
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            Class<?> mapper = loader.loadClass("sample.Mapper");
            var configuration = new org.apache.ibatis.session.Configuration(); configuration.setDatabaseId("postgresql");
            for (String name : List.of("map","named","context")) {
                var method = Arrays.stream(mapper.getDeclaredMethods()).filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
                var annotation = Arrays.stream(method.getAnnotations()).filter(value -> value.annotationType().getSimpleName().endsWith("Provider")).findFirst().orElseThrow();
                Object[] values = name.equals("context") ? new Object[]{9} : new Object[]{name.equals("map") ? "users" : "archived_users", name.equals("map") ? 7 : 8};
                Object parameter = new org.apache.ibatis.reflection.ParamNameResolver(configuration,method).getNamedParams(values);
                var expected = new org.apache.ibatis.builder.annotation.ProviderSqlSource(configuration,annotation,mapper,method).getBoundSql(parameter);
                assertEquals(normalize(expected.getSql()), normalize(sample(result,name).sql().orElseThrow()));
                assertEquals(expected.getParameterMappings().size(), sample(result,name).bindings().size());
            }
        }
    }

    @Test void handlesInheritedProviderMethodsAndUsesTheActualMapperTypeInContext() throws Exception {
        var result = analyze("""
                interface Parent { @SelectProvider(type=Sample.Provider.class,method="sql") Object query(); }
                interface Child extends Parent {}
                public class Sample {
                    public static class Base { public static String sql(org.apache.ibatis.builder.annotation.ProviderContext context) {
                        return "SELECT '"+context.getMapperType().getSimpleName()+"'";
                    } }
                    public static class Provider extends Base {}
                    public void run(Child child,Parent parent) { child.query(); parent.query(); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(Set.of("SELECT 'Child'","SELECT 'Parent'"), result.extractions().stream().flatMap(value -> value.samples().stream())
                .map(value -> value.sql().orElseThrow()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void diagnosesInvalidDeclarationsWhileRetainingTheirDefinitions() throws Exception {
        for (String declaration : List.of(
                "@SelectProvider(type=Sample.class,method=\"absent\")",
                "@SelectProvider(type=Sample.class,value=String.class,method=\"sql\")",
                "@SelectProvider(method=\"sql\")")) {
            var result = analyze("interface Mapper { " + declaration + " Object query(); } public class Sample { public static String sql() { return \"SELECT 1\"; } public void run(Mapper mapper) { mapper.query(); } }");
            assertEquals(AnalysisResult.Status.PARTIAL, result.status());
            assertEquals(1, definitions(result).size());
            assertEquals("sample.Mapper.query", definitions(result).getFirst().definition().orElseThrow().id());
            assertTrue(result.extractions().getFirst().statements().getFirst().definition().isPresent());
        }
        for (String method : List.of(
                "public String sql() { return \"SELECT 1\"; } private Sample() {}",
                "public static String sql(org.apache.ibatis.builder.annotation.ProviderContext a,org.apache.ibatis.builder.annotation.ProviderContext b) { return \"SELECT 1\"; }")) {
            var result = analyze("interface Mapper { @SelectProvider(type=Sample.class,method=\"sql\") Object query(); } public class Sample { " + method + " public void run(Mapper mapper) { mapper.query(); } }");
            assertEquals(AnalysisResult.Status.PARTIAL, result.status());
            assertTrue(result.extractions().getFirst().samples().stream().allMatch(sample -> sample.sql().isEmpty()));
        }
        var explicitVoid = analyze("""
                interface Mapper { @SelectProvider(type=Void.class,method="sql") Object query(); }
                public class Sample { public static String sql() { return "SELECT 1"; } public void run(Mapper mapper) { mapper.query(); } }
                """, MybatisOptions.defaults().withDefaultSqlProviderType("sample.Sample"));
        assertEquals(AnalysisResult.Status.PARTIAL,explicitVoid.status(),explicitVoid.diagnostics().toString());
        assertTrue(explicitVoid.extractions().getFirst().samples().stream().allMatch(sample -> sample.sql().isEmpty()));
    }

    @Test void doesNotFallbackWhenTheDefaultResolverCannotFindTheMapperMethod() throws Exception {
        var result = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class) Object query(); }
                public class Sample implements org.apache.ibatis.builder.annotation.ProviderMethodResolver {
                    public String provideSql() { return "SELECT 1"; }
                    public void run(Mapper mapper) { mapper.query(); }
                }
                """);
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.message().contains("ProviderMethodResolver")));
    }

    @Test void preservesIncompleteStatusForLoopsBudgetsAndCancellation() throws Exception {
        Path classes = compile("""
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(@Param("count") int count); }
                public class Sample {
                    public static String sql(@Param("count") int count) { var sql=new org.apache.ibatis.jdbc.SQL().SELECT("name").FROM("users");
                        for(int i=0;i<count;i++) sql.WHERE("id>"+i); return sql.toString(); }
                    public void run(Mapper mapper) { mapper.query(2); }
                }
                """);
        var modules = List.of(new MybatisAnalysisModule());
        var limited = new Analyzer(AnalyzerConfig.defaults().withMaxTraceNodes(2),modules,List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.PARTIAL, limited.status(), limited.diagnostics().toString());
        assertTrue(limited.diagnostics().stream().anyMatch(value -> value.message().toLowerCase(Locale.ROOT).contains("budget")));
        var loop = new Analyzer(modules,List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.PARTIAL, loop.status(), loop.diagnostics().toString());
        assertTrue(loop.extractions().getFirst().samples().stream().allMatch(value -> value.feasibility() != SqlSample.Feasibility.SATISFIABLE));
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var cancelled = new Analyzer(modules,List.of()).analyze(classes,AnalysisOptions.defaults().withCancelled(() -> checks.incrementAndGet()>120));
        assertEquals(AnalysisResult.Status.CANCELLED,cancelled.status());
    }

    @Test void dispatchesInstanceHelpersOnTheConstructedProviderType() throws Exception {
        var result = analyze("""
                interface Mapper { @SelectProvider(type=Sample.Provider.class,method="sql") Object query(); }
                public class Sample {
                    public static class Provider { public String sql() { return helper(); } public String helper() { return "SELECT 'base'"; } }
                    public static class Other extends Provider { public String helper() { return "SELECT 'other'"; } }
                    public void run(Mapper mapper) { mapper.query(); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS,result.status(),result.diagnostics().toString());
        assertEquals(1,result.extractions().getFirst().samples().size());
        assertEquals("SELECT 'base'",sample(result,"query").sql().orElseThrow());
        assertEquals("SELECT 'base'",definition(result,"query").samples().getFirst().sql().orElseThrow());
        var inherited = analyze("""
                interface Mapper { @SelectProvider(type=Sample.Provider.class,method="sql") Object query(); }
                public class Sample {
                    public static class Base { public String sql() { return helper(); } private String helper() { return "SELECT 'private'"; } }
                    public static class Provider extends Base { private String helper() { return "SELECT 'unrelated'"; } }
                    public void run(Mapper mapper) { mapper.query(); }
                }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS,inherited.status(),inherited.diagnostics().toString());
        assertEquals("SELECT 'private'",sample(inherited,"query").sql().orElseThrow());
    }

    @Test void extractsAnExplicitProviderFromDependencyBytecode() throws Exception {
        Path library = compile("public class Provider { public static String sql(int id) { return \"SELECT #{value}\"; } }", "Provider", null, true);
        Path application = compile("""
                interface Mapper { @SelectProvider(type=Provider.class,method="sql") Object query(int id); }
                public class Application { public void run(Mapper mapper) { mapper.query(7); } }
                """, "Application", library, true);
        var result = new Analyzer(List.of(new MybatisAnalysisModule()),List.of()).analyze(application,library,AnalysisOptions.defaults());
        assertEquals(AnalysisResult.Status.SUCCESS,result.status(),result.diagnostics().toString());
        assertEquals("SELECT ?",sample(result,"query").sql().orElseThrow());
        assertEquals(new SqlValue.Known(7),sample(result,"query").bindings().getFirst().value());
    }

    @Test void respectsActualNamesNumericNamesAndSingleValueAliases() throws Exception {
        String source = """
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(String table,int id); }
                public class Sample { public static String sql(String table,int id) { return "SELECT name FROM "+table+" WHERE id=#{id}"; }
                    public void run(Mapper mapper) { mapper.query("users",7); } }
                """;
        var actual = analyze(source);
        assertEquals(AnalysisResult.Status.SUCCESS,actual.status(),actual.diagnostics().toString());
        assertEquals("SELECT name FROM users WHERE id=?",sample(actual,"query").sql().orElseThrow());
        Path classes = compile(source.replace("#{id}","#{1}"));
        var numeric = new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults().withUseActualParamName(false))),List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.SUCCESS,numeric.status(),numeric.diagnostics().toString());
        assertEquals(new SqlValue.Known(7),sample(numeric,"query").bindings().getFirst().value());
        var single = analyze("""
                interface Mapper { @SelectProvider(type=Sample.class,method="sql") Object query(String table); }
                public class Sample { public static String sql(String table) { return "SELECT name FROM "+table; }
                    public void run(Mapper mapper) { mapper.query("users"); } }
                """);
        assertEquals(AnalysisResult.Status.SUCCESS,single.status(),single.diagnostics().toString());
        assertEquals("SELECT name FROM manual_users",definition(single,"query").sample(Map.of("value","manual_users")).sql().orElseThrow());
        assertTrue(definition(single,"query").samples().getFirst().sql().isEmpty());
    }

    @Test void supportsProvidersInTheDefaultPackage() throws Exception {
        Path classes = compile("""
                interface Mapper { @SelectProvider(type=Sample.class) Object query(int id); }
                public class Sample implements org.apache.ibatis.builder.annotation.ProviderMethodResolver {
                    public String query(int id) { return "SELECT #{value}"; }
                    public void run(Mapper mapper) { mapper.query(7); }
                }
                """, "Sample", null, true, "");
        var result = new Analyzer(List.of(new MybatisAnalysisModule()),List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.SUCCESS,result.status(),result.diagnostics().toString());
        assertEquals("SELECT ?",sample(result,"query").sql().orElseThrow());
        assertEquals(new SqlValue.Known(7),sample(result,"query").bindings().getFirst().value());
    }

    private AnalysisResult analyze(String source) throws Exception { return analyze(source, MybatisOptions.defaults()); }
    private AnalysisResult analyze(String source, MybatisOptions options) throws Exception {
        return new Analyzer(List.of(new MybatisAnalysisModule(options)), List.of()).analyze(compile(source));
    }
    private Path compile(String source) throws Exception {
        return compile(source,"Sample",null,true);
    }
    private Path compile(String source, String className, Path dependency, boolean names) throws Exception {
        return compile(source,className,dependency,names,"sample");
    }
    private Path compile(String source, String className, Path dependency, boolean names, String packageName) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Path java = Files.writeString(root.resolve(className+".java"), (packageName.isEmpty() ? "" : "package "+packageName+"; ")+"import org.apache.ibatis.annotations.*; " + source);
        Path classes = Files.createDirectory(root.resolve("classes"));
        String classpath = System.getProperty("java.class.path")+(dependency==null ? "" : System.getProperty("path.separator")+dependency);
        var options = new ArrayList<>(List.of("-proc:none","-g","-classpath",classpath,"-d",classes.toString(),java.toString()));
        if(names) options.addFirst("-parameters");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,options.toArray(String[]::new)));
        return classes;
    }
    private static List<SqlTemplateResult> definitions(AnalysisResult result) {
        return result.sqlResults().stream().filter(SqlTemplateResult.class::isInstance).map(SqlTemplateResult.class::cast).toList();
    }
    private static SqlTemplateResult definition(AnalysisResult result, String name) {
        return definitions(result).stream().filter(value -> value.definition().orElseThrow().id().endsWith("."+name)).findFirst().orElseThrow();
    }
    private static SqlSample sample(AnalysisResult result, String name) {
        return result.extractions().stream().filter(value -> value.callSite().target().getName().equals(name))
                .flatMap(value -> value.samples().stream()).findFirst().orElseThrow();
    }
    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").strip(); }
}
