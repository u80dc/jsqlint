package org.u80dc.jsqlint.mybatis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.node.MixedSqlNode;
import org.u80dc.jsqlint.core.node.ValueSqlNode;
import org.u80dc.jsqlint.core.resource.PathResourceSource;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 定义索引独立于调用与扫描次序，保留筛选、歧义和会话边界。 @author u80dc */
class MybatisDefinitionTest {
    @TempDir Path temporary;

    @Test void unusedXmlDefinitionsExposeParameterizedSamplesAndTheirSourceIdentity() throws Exception {
        Path resources = Files.createDirectory(temporary.resolve("resources"));
        Files.writeString(resources.resolve("mapper.xml"), """
                <mapper namespace='sample.Mapper'>
                  <select id='query'>SELECT * FROM users WHERE id=#{id,javaType=int,jdbcType=INTEGER} OR parent=#{id}</select>
                  <select id='dynamic'>SELECT * FROM users<where><if test='id != null'>AND id=#{id}</if></where></select>
                </mapper>
                """);
        var result = resources(resources, MybatisOptions.defaults());
        assertEquals(AnalysisResult.Status.SUCCESS, result.status());
        assertTrue(result.extractions().isEmpty()); assertEquals(2, result.sqlResults().size());
        var definition = definition(result, "sample.Mapper.query");
        var unknown = definition.samples().getFirst();
        assertEquals("SELECT * FROM users WHERE id=? OR parent=?", unknown.sql().orElseThrow());
        assertTrue(unknown.bindings().stream().allMatch(binding -> binding.value() instanceof SqlValue.Unknown));
        assertEquals(SqlSample.Feasibility.UNVERIFIED, unknown.feasibility());
        var known = definition.sample(Map.of("id", 7));
        assertEquals(List.of(new SqlValue.Known(7), new SqlValue.Known(7)), known.bindings().stream().map(SqlSample.Binding::value).toList());
        assertEquals("java.lang.Integer", known.bindings().getFirst().type());
        assertEquals("javaType=int,jdbcType=INTEGER", known.bindings().getFirst().options());
        assertEquals(resources.resolve("mapper.xml").toUri(), known.definition().orElseThrow().location().resource());
        assertEquals("SELECT * FROM users WHERE id=?", definition(result, "sample.Mapper.dynamic").sample(Map.of("id", 7)).sql().orElseThrow());
        assertEquals(1, result.rawSqls().size());
    }

    @Test void databaseSpecificStatementsAndFragmentsAreSelectedAfterAllResourcesAreRead() throws Exception {
        Path resources = Files.createDirectory(temporary.resolve("database"));
        Files.writeString(resources.resolve("a-statement.xml"), """
                <mapper namespace='sample.Mapper'>
                  <select id='query'>SELECT <include refid='shared.Columns.values'/> FROM defaults</select>
                  <select id='query' databaseId='postgresql'>SELECT <include refid='shared.Columns.values'/> FROM postgres</select>
                  <select id='query' databaseId='mysql'>SELECT 9 FROM mysql</select>
                  <select id='fallback'>SELECT 2</select>
                </mapper>
                """);
        Files.writeString(resources.resolve("z-fragment.xml"), """
                <mapper namespace='shared.Columns'><sql id='values'>1</sql><sql id='values' databaseId='postgresql'>7</sql></mapper>
                """);
        var selected = resources(resources, MybatisOptions.defaults().withDatabaseId("postgresql"));
        assertEquals(AnalysisResult.Status.SUCCESS, selected.status(), selected.diagnostics().toString());
        assertEquals(Set.of("SELECT 7 FROM postgres", "SELECT 2"), selected.rawSqls().stream().map(sql -> normalize(sql.text())).collect(java.util.stream.Collectors.toSet()));
        assertEquals("postgresql", definition(selected, "sample.Mapper.query").definition().orElseThrow().metadata().get("databaseId"));
        var defaults = resources(resources, MybatisOptions.defaults());
        assertEquals(Set.of("SELECT 1 FROM defaults", "SELECT 2"), defaults.rawSqls().stream().map(sql -> normalize(sql.text())).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void includePropertiesExpandWithoutMutatingOrLosingNestedNamespace() throws Exception {
        Path resources = Files.createDirectory(temporary.resolve("include"));
        Files.writeString(resources.resolve("mapper.xml"), """
                <mapper namespace='sample.Mapper'><select id='query'>SELECT <include refid='${fragment}'><property name='alias' value='u'/></include>
                FROM ${schema}.users u WHERE id=#{id}</select></mapper>
                """);
        Files.writeString(resources.resolve("columns.xml"), """
                <mapper namespace='shared.Columns'><sql id='columns'>${alias}.id,<include refid='name'/></sql><sql id='name'>${alias}.name</sql></mapper>
                """);
        var options = MybatisOptions.defaults().withVariables(Map.of("fragment", "shared.Columns.columns", "schema", "app"));
        var result = resources(resources, options);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals("SELECT u.id, u.name FROM app.users u WHERE id=?", normalize(definition(result, "sample.Mapper.query").sample(Map.of("id", 7)).sql().orElseThrow()));
        assertEquals(result.rawSqls(), resources(resources, options).rawSqls(), "重复分析不修改共享 DOM 或残留 include 状态");
    }

    @Test void duplicatesMissingIncludesCyclesAndProvidersRemainDiagnosedCandidates() throws Exception {
        Path resources = Files.createDirectory(temporary.resolve("invalid"));
        Files.writeString(resources.resolve("mapper.xml"), """
                <mapper namespace='sample.Mapper'>
                  <sql id='cycle'><include refid='cycle'/></sql>
                  <select id='duplicate'>SELECT 1</select><select id='duplicate'>SELECT 2</select>
                  <select id='missing'>SELECT <include refid='absent'/></select>
                  <select id='cycle'>SELECT <include refid='cycle'/></select><select id='good'>SELECT 7</select>
                </mapper>
                """);
        var result = resources(resources, MybatisOptions.defaults());
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertEquals(5, result.sqlResults().size());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("Duplicate")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("cyclic")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("absent")));
        assertEquals("SELECT 7", definition(result, "sample.Mapper.good").samples().getFirst().sql().orElseThrow());
        Path classes = compile("""
                interface Mapper { @SelectProvider(type=Sample.class, method="sql") Object query(); }
                public class Sample { public static String sql() { return System.getProperty("provider.sql"); } public void run(Mapper mapper) { mapper.query(); } }
                """);
        var provider = new Analyzer(List.of(new MybatisAnalysisModule()), List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.PARTIAL, provider.status());
        assertTrue(provider.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("getProperty")));
        assertEquals(1, provider.extractions().size()); assertTrue(provider.extractions().getFirst().samples().getFirst().sql().isEmpty());
    }

    @Test void repeatableAnnotationsSelectDatabaseVariantAndConflictingXmlDoesNotWinSilently() throws Exception {
        Path classes = compile("""
                interface Mapper {
                    @Select("SELECT #{id}") @Select(value="SELECT #{id} FROM postgres", databaseId="postgresql")
                    Object query(@Param("id") int id);
                }
                public class Sample { public void run(Mapper mapper) { mapper.query(7); } }
                """);
        var options = MybatisOptions.defaults().withDatabaseId("postgresql");
        var result = new Analyzer(List.of(new MybatisAnalysisModule(options)), List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals("SELECT ? FROM postgres", result.extractions().getFirst().samples().getFirst().sql().orElseThrow());
        assertEquals(1, result.sqlResults().stream().filter(SqlTemplateResult.class::isInstance).count());
        Files.writeString(classes.resolve("mapper.xml"), "<mapper namespace='sample.Mapper'><select id='query' databaseId='postgresql'>SELECT 8</select></mapper>");
        var conflict = new Analyzer(List.of(new MybatisAnalysisModule(options)), List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.PARTIAL, conflict.status());
        var sample = conflict.extractions().getFirst().samples().getFirst();
        assertTrue(sample.sql().isEmpty()); assertEquals(SqlSample.Feasibility.UNKNOWN, sample.feasibility());
        assertTrue(sample.issues().stream().anyMatch(issue -> issue.contains("Ambiguous")));
    }

    @Test void inheritedMethodsUseChildXmlThenParentAnnotationWhileOverridesKeepTheirBoundaries() throws Exception {
        Path classes = compile("""
                interface Parent { @Select("SELECT #{id} FROM parent") Object query(@Param("id") int id); }
                interface Child extends Parent {}
                interface Override extends Parent { Object query(int id); }
                interface DefaultOverride extends Parent { default Object query(int id) { return null; } }
                public class Sample { public void run(Child child, Parent parent, Override overridden, DefaultOverride helper) {
                    child.query(7); parent.query(8); overridden.query(9); helper.query(10);
                } }
                """);
        Files.writeString(classes.resolve("mapper.xml"), "<mapper namespace='sample.Child'><select id='query'>SELECT #{id} FROM child</select></mapper>");
        var result = new Analyzer(List.of(new MybatisAnalysisModule()), List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        assertEquals(2, result.extractions().size());
        assertEquals(Set.of("sample.Child.query", "sample.Parent.query"), result.extractions().stream()
                .map(value -> value.statements().getFirst().definition().orElseThrow().id()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("SELECT ? FROM child", "SELECT ? FROM parent"), result.extractions().stream()
                .flatMap(value -> value.samples().stream()).map(value -> value.sql().orElseThrow()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void xmlAndAnnotationsAreFilteredIndependentlyBeforeDetectingConflicts() throws Exception {
        Path classes = compile("""
                interface Mapper { @Select("SELECT #{id}") Object query(@Param("id") int id); }
                public class Sample { public void run(Mapper mapper) { mapper.query(7); } }
                """);
        Files.writeString(classes.resolve("mapper.xml"), "<mapper namespace='sample.Mapper'><select id='query' databaseId='postgresql'>SELECT 8</select></mapper>");
        var result = new Analyzer(List.of(new MybatisAnalysisModule(MybatisOptions.defaults().withDatabaseId("postgresql"))), List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(result.extractions().getFirst().samples().getFirst().sql().isEmpty(), "数据库专属 XML 不能掩盖默认注解的重复注册");
        assertEquals(2, result.sqlResults().stream().filter(SqlTemplateResult.class::isInstance).count());
    }

    @Test void conflictingInactiveAnnotationsStillMakeTheMethodDefinitionInvalid() throws Exception {
        Path classes = compile("""
                interface Mapper {
                    @Select("SELECT #{id}") @Select(value="SELECT 1",databaseId="mysql") @Select(value="SELECT 2",databaseId="mysql")
                    Object query(@Param("id") int id);
                }
                public class Sample { public void run(Mapper mapper) { mapper.query(7); } }
                """);
        var result = new Analyzer(List.of(new MybatisAnalysisModule()), List.of()).analyze(classes);
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("Conflicting")));
        assertEquals(SqlSample.Feasibility.UNKNOWN, result.extractions().getFirst().samples().getFirst().feasibility());
    }

    @Test void inheritedAnnotationsFromDependencyInterfacesAreIndexedOnDemand() throws Exception {
        Path library = compile("""
                interface Parent { @Select("SELECT #{id} FROM library") Object query(@Param("id") int id); }
                public class Sample {}
                """);
        Path application = compile("""
                interface Child extends Parent {}
                public class Sample { public void run(Child mapper) { mapper.query(7); } }
                """, library);
        var result = new Analyzer(List.of(new MybatisAnalysisModule()), List.of()).analyze(application, library, AnalysisOptions.defaults());
        assertEquals(AnalysisResult.Status.SUCCESS, result.status(), result.diagnostics().toString());
        var sample = result.extractions().getFirst().samples().getFirst();
        assertEquals("sample.Parent.query", sample.definition().orElseThrow().id());
        assertEquals("SELECT ? FROM library", sample.sql().orElseThrow());
        assertEquals(new SqlValue.Known(7), sample.bindings().getFirst().value());
    }

    @Test void unsupportedExpressionKeepsItsTemplateAndIsDiagnosedBeforeCallInference() throws Exception {
        Path resources = Files.createDirectory(temporary.resolve("expression"));
        Files.writeString(resources.resolve("mapper.xml"), """
                <mapper namespace='sample.Mapper'><select id='query'>SELECT ${@java.lang.System@getProperty('java.home')}</select></mapper>
                """);
        var result = resources(resources, MybatisOptions.defaults());
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        var source = definition(result, "sample.Mapper.query");
        var contents = assertInstanceOf(MixedSqlNode.class, source.template()).contents();
        assertEquals("@java.lang.System@getProperty('java.home')", contents.stream().filter(ValueSqlNode.class::isInstance)
                .map(ValueSqlNode.class::cast).findFirst().orElseThrow().getExpression(), "保留未支持表达式的来源模板");
        assertTrue(source.samples().getFirst().sql().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("SQL_DEFINITION") && diagnostic.message().contains("ASTStaticMethod")));
    }

    private AnalysisResult resources(Path directory, MybatisOptions options) {
        return new Analyzer(List.of(new MybatisAnalysisModule(options)), List.of()).analyze(new AnalysisRequest(List.of(), List.of(new PathResourceSource(directory))));
    }
    private Path compile(String source) throws Exception {
        return compile(source, null);
    }
    private Path compile(String source, Path dependency) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Path java = Files.writeString(root.resolve("Sample.java"), "package sample; import org.apache.ibatis.annotations.*; " + source);
        Path classes = Files.createDirectory(root.resolve("classes"));
        String classpath = System.getProperty("java.class.path") + (dependency == null ? "" : System.getProperty("path.separator") + dependency);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-classpath", classpath, "-d", classes.toString(), java.toString()));
        return classes;
    }
    private static SqlTemplateResult definition(AnalysisResult result, String id) {
        return result.sqlResults().stream().filter(SqlTemplateResult.class::isInstance).map(SqlTemplateResult.class::cast)
                .filter(value -> value.definition().orElseThrow().id().equals(id)).findFirst().orElseThrow();
    }
    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").strip(); }
}
