package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 节点结构相等性及构造归一化的公共契约。
 *
 * @author u80dc
 */
class SqlNodeContractTest {
    @Test
    void independentlyParsedTreesCanBeUsedAsStructuralMapKeys() {
        String script = """
                <script><bind name="table" value="'users'"/>UPDATE <value expression="table"/>
                <set>active=#{active},</set>
                <where><choose><when test="enabled"><trim prefix="(" suffix=")" prefixOverrides="AND ">
                <foreach collection="ids" item="id" separator=" OR ">id=#{id,jdbcType=INTEGER}</foreach>
                </trim></when><otherwise>1=1</otherwise></choose></where></script>
                """;
        SqlNode first = SqlNodeBuilder.buildScript(script);
        SqlNode second = SqlNodeBuilder.buildScript(script);

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals("parsed", Map.of(first, "parsed").get(second));
    }

    @Test
    void structuralComparisonPreservesNodeTypesTextAndChildOrder() {
        var literal = new StaticTextSqlNode("SELECT 1");
        assertNotEquals(literal, new TextSqlNode("SELECT 1"));
        assertEquals(new TextSqlNode("SELECT #{id}"), new TextSqlNode("SELECT #{id}"));
        assertNotEquals(literal, new StaticTextSqlNode("SELECT  1"));
        assertNotEquals(literal, new MixedSqlNode(List.of(literal)));
        assertNotEquals(new MixedSqlNode(List.of(literal, ValueSqlNode.raw("tail"))),
                new MixedSqlNode(List.of(ValueSqlNode.raw("tail"), literal)));
    }

    @Test
    void valueEqualityIncludesExpressionOutputKindNullTextAndBindingOptions() {
        var raw = ValueSqlNode.raw("id", "NULL");
        var parameter = ValueSqlNode.parameter("id");
        assertEquals(raw, ValueSqlNode.raw("id", "NULL"));
        assertEquals("raw", Map.of(raw, "raw").get(ValueSqlNode.raw("id", "NULL")));
        assertEquals(parameter, ValueSqlNode.parameter("id", ""));
        assertEquals(ValueSqlNode.raw("id"), ValueSqlNode.raw("id", ""));
        assertEquals("parameter", Map.of(parameter, "parameter").get(ValueSqlNode.parameter("id")));

        assertNotEquals(raw, parameter);
        assertNotEquals(parameter, raw);
        assertNotEquals(raw, ValueSqlNode.raw("other", "NULL"));
        assertNotEquals(raw, ValueSqlNode.raw("id", "null"));
        assertNotEquals(parameter, ValueSqlNode.parameter("id", "jdbcType=INTEGER"));
    }

    @Test
    void optionalAttributesAndConstructorOverloadsNormalizeConsistently() {
        var contents = ValueSqlNode.parameter("item");
        var defaults = new ForEachSqlNode("ids", "item", "", "", "", "", contents, false, true);
        assertEquals(defaults, new ForEachSqlNode("ids", null, null, null, null, null, contents));
        assertEquals(defaults, new ForEachSqlNode("ids", " ", "\t", null, null, null, contents, false, true));
        assertNotEquals(defaults, new ForEachSqlNode("ids", "item", "", "", "", "", contents, true, true));
        assertNotEquals(defaults, new ForEachSqlNode("ids", "item", "", "", "", "", contents, false, false));
        assertEquals(new TrimSqlNode("", "", "", "", contents),
                new TrimSqlNode(null, null, null, null, contents));
    }
}
