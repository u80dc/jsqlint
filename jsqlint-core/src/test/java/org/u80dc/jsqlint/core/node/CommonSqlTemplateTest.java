package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 两种来源归一化后共用节点和渲染器，以实际拼接结果校验模板语义。 */
class CommonSqlTemplateTest {
    private static String render(String template, Map<String, ?> parameters) {
        return SqlNodeRenderer.render(SqlNodeBuilder.buildScript(template), parameters).sql().orElseThrow();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"O'Brien", "a  b\n( x )", "#{literal}"})
    void normalizedJdbcTemplateMatchesJavaConcatenation(String name) {
        String template = "<script>SELECT id FROM users WHERE 1=1"
                + "<if test='filter'> AND name='<value expression='name' nullText='null'/>'</if>"
                + " AND id IN (<foreach collection='ids' item='id' separator=',' skipBlank='false'>"
                + "<value expression='id' nullText='null'/></foreach>)"
                + "<choose><when test='descending'> ORDER BY id DESC</when>"
                + "<otherwise> ORDER BY id ASC</otherwise></choose></script>";
        for (boolean filter : List.of(false, true)) {
            for (boolean descending : List.of(false, true)) {
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("name", name);
                parameters.put("filter", filter);
                parameters.put("descending", descending);
                parameters.put("ids", List.of(2, 7));

                StringBuilder jdbc = new StringBuilder("SELECT id FROM users WHERE 1=1");
                if (filter) { jdbc.append(" AND name='").append(name).append("'"); }
                jdbc.append(" AND id IN (");
                for (int i = 0; i < 2; i++) {
                    if (i != 0) { jdbc.append(','); }
                    jdbc.append(List.of(2, 7).get(i));
                }
                jdbc.append(')').append(descending ? " ORDER BY id DESC" : " ORDER BY id ASC");
                assertEquals(jdbc.toString(), render(template, parameters));
            }
        }
    }

    @Test
    void mybatisStyleBindWhereAndForeachUseTheSamePipeline() {
        String template = "<bind name='pattern' value=\"'%' + name + '%'\"/>SELECT * FROM users "
                + "<where><if test='name != null'>AND name LIKE #{pattern}</if>"
                + "<if test='ids != null and !ids.isEmpty()'> AND id IN "
                + "<foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach>"
                + "</if></where>";
        assertEquals("SELECT * FROM users WHERE name LIKE '%O''Brien%' AND id IN (2,7)",
                render(template, Map.of("name", "O'Brien", "ids", List.of(2, 7))));
    }

    @Test
    void rawConcatenationAndParameterBindingKeepDifferentSemantics() {
        assertEquals("'O'Brien' 'O''Brien'",
                render("'<value expression='name'/>' #{name}", Map.of("name", "O'Brien")));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", null);
        assertEquals("[] [null] [NULL]", render("[" + "$" + "{name}] "
                + "[<value expression='name' nullText='null'/>] [#{name}]", values));
    }

    @Test
    void unknownRawValueDoesNotUseTheKnownNullReplacement() {
        var node = SqlNodeBuilder.buildScript("SELECT '<value expression='name' nullText='null'/>'");
        var pending = SqlNodeRenderer.render(node, Map.of());
        assertFalse(pending.complete());
        assertFalse(pending.diagnostics().isEmpty());
        assertEquals("SELECT 'Alice'",
                SqlNodeRenderer.render(pending.residual(), Map.of("name", "Alice")).sql().orElseThrow());
    }

    @Test
    void literalTagProtectsPlaceholderLikeTextInJdbcConstants() {
        String sql = "SELECT '#{constant}', '<if test=\"x\">', 'a  b' -- comment\n";
        assertEquals(sql + "FROM users",
                render("<literal><![CDATA[" + sql + "]]></literal>FROM users", Map.of()));
    }

    @Test
    void nonXmlTemplateTextSupportsEscapedPlaceholdersWithoutChangingSqlWhitespace() {
        String source = "SELECT '\\#{literal}', '\\$" + "{literal}', #{id} WHERE 1 < 2\r\n";
        var node = SqlNodeBuilder.templateText(source);
        assertEquals("SELECT '#{literal}', '$" + "{literal}', 7 WHERE 1 < 2\r\n",
                SqlNodeRenderer.render(node, Map.of("id", 7)).sql().orElseThrow());
    }

    @Test
    void placeholdersAcrossCdataBoundariesAreNotTruncated() {
        assertEquals("SELECT 7 AS id", render("SELECT #<![CDATA[{id}]]> AS id", Map.of("id", 7)));
    }

    @Test
    void nodeTreeExposesParameterExpressionsAndRetainsBindingMetadata() {
        var value = assertInstanceOf(ValueSqlNode.class,
                SqlNodeBuilder.buildScript("#{data['a,b}'],jdbcType=VARCHAR,typeHandler=Example}"));
        assertEquals(ValueSqlNode.Kind.PARAMETER, value.getKind());
        assertEquals("data['a,b}']", value.getExpression());
        assertEquals("jdbcType=VARCHAR,typeHandler=Example", value.getBindingOptions());
        assertEquals("'text'", SqlNodeRenderer.render(value,
                Map.of("data", Map.of("a,b}", "text"))).sql().orElseThrow());
    }

    @Test
    void directlyConstructedNodesMatchTheTagSyntax() {
        var node = new MixedSqlNode(List.of(new StaticTextSqlNode("SELECT "),
                ValueSqlNode.raw("column", "null"), new StaticTextSqlNode(" FROM users WHERE id="),
                ValueSqlNode.parameter("id")));
        var parameters = Map.of("column", "name", "id", 7);
        assertEquals(render("SELECT <value expression='column' nullText='null'/> FROM users WHERE id=#{id}",
                parameters), SqlNodeRenderer.render(node, parameters).sql().orElseThrow());
    }

    @Test
    void exactJdbcLoopKeepsWhitespaceAndCommentTerminatingNewlines() {
        var pieces = List.of(" ", "id", " -- comment", "\n", "FROM", " ", "users");
        String template = "SELECT<foreach collection='pieces' item='piece' skipBlank='false'>"
                + "<value expression='piece' nullText='null'/></foreach>";
        assertEquals("SELECT" + String.join("", pieces), render(template, Map.of("pieces", pieces)));
    }

    @Test
    void nullableForeachStillDistinguishesUnknownFromKnownNull() {
        String template = "SELECT 1<foreach collection='ids' nullable='true'> #{item}</foreach>";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ids", null);
        assertEquals("SELECT 1", render(template, values));
        assertEquals("SELECT 1", render(template, Map.of("ids", List.of())));
        var pending = SqlNodeRenderer.render(SqlNodeBuilder.buildScript(template), Map.of());
        assertFalse(pending.complete());
        assertEquals("SELECT 1 7", SqlNodeRenderer.render(pending.residual(), Map.of("ids", List.of(7)))
                .sql().orElseThrow());
    }
}
