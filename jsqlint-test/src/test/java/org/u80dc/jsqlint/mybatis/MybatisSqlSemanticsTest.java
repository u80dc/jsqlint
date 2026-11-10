package org.u80dc.jsqlint.mybatis;

import org.apache.ibatis.mapping.*;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.u80dc.jsqlint.core.node.*;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 与真实 MyBatis BoundSql 和 OGNL 比较，防止适配器悄悄改变框架语义。 @author u80dc */
class MybatisSqlSemanticsTest {
    static Stream<String> scripts() {
        return Stream.of(
                "<script>SELECT #{id,jdbcType=INTEGER,javaType=int},#{id} FROM ${table}</script>",
                "<script>SELECT #{id:INTEGER}</script>",
                "<script>SELECT<if test='ids != null and ids.size() > 0'> #{id}</if></script>",
                "<script>SELECT<where><if test='id != null and id > 0'>AND id=#{id}</if><if test='enabled'>OR name=#{name}</if></where></script>",
                "<script>UPDATE t<set><if test='enabled'>name=#{name},</if>id=#{id},</set>WHERE id=#{id}</script>",
                "<script><bind name='pattern' value=\"'%' + name + '%'\"/>SELECT * FROM t WHERE name LIKE #{pattern}</script>",
                "<script>SELECT <choose><when test='enabled'>#{name}</when><otherwise>#{id}</otherwise></choose></script>",
                "<script>SELECT<![CDATA[ id < 10 ]]>FROM ${table} -- keep newline\nWHERE id=#{id}</script>",
                "<script>SELECT * FROM t WHERE id IN <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach></script>",
                "<script>SELECT * FROM t <trim prefix='WHERE' prefixOverrides='AND |OR ' suffixOverrides=','>AND id=#{id},</trim></script>");
    }
    @ParameterizedTest @MethodSource("scripts")
    void supportedTemplatesMatchMybatisSqlAndOrderedParameterValues(String script) {
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of("id", 7, "table", "users", "enabled", true, "name", "O'Neil", "ids", List.of(1, 2)));
        var configuration = new Configuration();
        var expected = new XMLLanguageDriver().createSqlSource(configuration, script, Map.class).getBoundSql(parameters);
        var options = MybatisOptions.defaults();
        var actual = SqlNodeRenderer.renderBound(MybatisSqlAdapter.annotation(script, options), parameters, new MybatisExpressionEvaluator(options));
        assertEquals(normalize(expected.getSql()), normalize(actual.rendered().sql().orElseThrow()), actual.rendered().diagnostics().toString());
        assertEquals(expected.getParameterMappings().size(), actual.bindings().size());
        List<Object> expectedValues = expected.getParameterMappings().stream().map(mapping -> bindingValue(configuration, expected, mapping, parameters)).toList();
        assertEquals(expectedValues, actual.bindings().stream().map(binding -> ((SqlValue.Known) binding.value()).value()).toList());
    }

    @Test void numericBindingNamesAndOgnlNumericConstantsHaveDifferentMeaning() {
        var evaluator = new MybatisExpressionEvaluator(MybatisOptions.defaults());
        var result = SqlNodeRenderer.renderBound(MybatisSqlAdapter.annotation("SELECT ${0},#{0},#{_parameter.1}"),
                Map.of("0", 7, "_parameter", Map.of("1", 8)), evaluator);
        assertEquals("SELECT 0,?,?", result.rendered().sql().orElseThrow());
        assertEquals(List.of(new SqlValue.Known(7), new SqlValue.Known(8)), result.bindings().stream().map(SqlNodeRenderer.BoundParameter::value).toList());
    }

    @Test void ognlCharacterConstantsAndPureOperationsKeepMybatisTypes() throws Exception {
        var evaluator = new MybatisExpressionEvaluator(MybatisOptions.defaults());
        var parameters = Map.of("name", "Alice", "n", 7, "ids", List.of(1, 2), "params", Map.of("id", 7));
        for (String expression : List.of("'x'", "'long'", "'!' + name", "n + 2", "n > 0 and name != null", "n > 0 ? n : 0", "name.length()", "ids != null", "params != null"))
            assertEquals(new SqlValue.Known(Ognl.getValue(expression, parameters)), evaluator.evaluate(expression, parameters), expression);
        assertInstanceOf(SqlValue.Unknown.class, evaluator.evaluate("missing != null", parameters));
        assertEquals(new SqlValue.Known(false), evaluator.evaluate("false and missing", parameters));
    }

    @Test void evaluatorDoesNotRunBeanAccessorsStaticMethodsOrConstructors() {
        var evaluator = new MybatisExpressionEvaluator(MybatisOptions.defaults());
        var bean = new ObservableBean();
        for (String expression : List.of("bean.value", "bean.getValue()", "bean", "@java.lang.System@getProperty('java.home')", "new java.lang.String('x')"))
            assertInstanceOf(SqlValue.Unknown.class, evaluator.evaluate(expression, Map.of("bean", bean)), expression);
        assertEquals(0, bean.reads);
        assertInstanceOf(SqlValue.Unknown.class, evaluator.evaluateParameter("bean.value", Map.of("bean", bean)));
        assertEquals(0, bean.reads);
    }
    static final class ObservableBean { int reads; public String getValue() { reads++; return "unsafe"; } }

    private static Object bindingValue(Configuration configuration, BoundSql bound, ParameterMapping mapping, Object parameters) {
        return bound.hasAdditionalParameter(mapping.getProperty()) ? bound.getAdditionalParameter(mapping.getProperty())
                : configuration.newMetaObject(parameters).getValue(mapping.getProperty());
    }
    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").strip(); }
}
