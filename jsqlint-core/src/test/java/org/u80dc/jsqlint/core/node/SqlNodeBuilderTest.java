package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlNodeBuilderTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT '<if' AS text", "SELECT 'a  b', '( x )'",
            "SELECT id -- note\nFROM users", "SELECT id -- note\r\nFROM users",
            "SELECT /* a\n b */ id FROM users", "SELECT * FROM users WHERE a < 3",
            " SELECT '\t雪  峰\n' ", "SELECT #{id}", "SELECT $VALUE", ""
    })
    void plainSqlIsNeverParsedAsXmlOrReformatted(String sql) {
        SqlNode node = SqlNodeBuilder.build(sql);
        assertInstanceOf(StaticTextSqlNode.class, node);
        assertEquals(sql, SqlNodeRenderer.render(node, Map.of()).sql().orElseThrow());
    }

    @Test
    void nestedChooseRetainsCorrectBranchAndTrailingSql() {
        String script = """
                <script>SELECT <choose><when test="a"><choose><when test="b">1</when><otherwise>2</otherwise></choose></when><otherwise>3</otherwise></choose> AS n</script>""";
        assertEquals("SELECT 2 AS n", SqlNodeRenderer.render(SqlNodeBuilder.build(script),
                Map.of("a", true, "b", false)).sql().orElseThrow());
    }

    @Test
    void acceptsXmlAttributeWhitespaceAndBothQuoteStyles() {
        var node = SqlNodeBuilder.buildScript("SELECT * FROM users <if test = 'active'>WHERE active = 1</if>");
        assertEquals("SELECT * FROM users WHERE active = 1",
                SqlNodeRenderer.render(node, Map.of("active", true)).sql().orElseThrow());
    }

    @Test
    void attributesDoNotLeakFromNestedElements() {
        var outer = assertInstanceOf(TrimSqlNode.class,
                SqlNodeBuilder.buildScript("<trim><trim prefix='(' suffix=')'>id</trim></trim>"));
        assertEquals("", outer.getPrefix());
        assertEquals("", outer.getSuffix());
        var inner = assertInstanceOf(TrimSqlNode.class, outer.getContents());
        assertEquals("(", inner.getPrefix());
        assertEquals(")", inner.getSuffix());
    }

    @Test
    void preservesCdataAndDecodesXmlEntities() {
        String script = "<script><![CDATA[SELECT '<if', 'a  b' -- note\n]]>FROM users WHERE id &lt; 3</script>";
        assertEquals("SELECT '<if', 'a  b' -- note\nFROM users WHERE id < 3",
                SqlNodeRenderer.render(SqlNodeBuilder.build(script), Map.of()).sql().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1 <if test='enabled'>WHERE active=1",
            "SELECT 1 <unknown/>",
            "<script>SELECT 1</script> trailing",
            "<choose><when test='a'>1</when><otherwise>2</otherwise><otherwise>3</otherwise></choose>",
            "<choose><if test='a'>1</if></choose>",
            "<if>WHERE active=1</if>",
            "<foreach>#{item}</foreach>",
            "<if test='a' unsupported='true'>x</if>",
            "<foreach collection='items' nullable='invalid'>#{item}</foreach>",
            "<foreach collection='items' skipBlank='invalid'>#{item}</foreach>",
            "<value expression='id' unexpected='true'/>",
            "<value expression='id'>body</value>",
            "<value/>",
            "<literal><if test='enabled'>x</if></literal>",
            "<literal><?unexpected content?></literal>",
            "SELECT #{id",
            "<!DOCTYPE script [<!ENTITY x SYSTEM 'file:///unavailable'>]><script>&x;</script>"
    })
    void failureReturnsFullSourceAndNoPartialTree(String source) {
        var result = SqlNodeBuilder.parseScript(source);
        assertFalse(result.successful());
        assertTrue(result.node().isEmpty());
        assertEquals(source, result.source());
        assertFalse(result.diagnostics().isEmpty());
        var exception = assertThrows(SqlNodeParseException.class, () -> SqlNodeBuilder.buildScript(source));
        assertEquals(source, exception.result().source());
    }

    @Test
    void emptyScriptAndPairedBindAreWellDefined() {
        assertEquals("", SqlNodeRenderer.render(SqlNodeBuilder.build("<script/>"), Map.of()).sql().orElseThrow());
        assertEquals("7", SqlNodeRenderer.render(
                SqlNodeBuilder.buildScript("<bind name='id' value='7'></bind>#{id}"), Map.of()).sql().orElseThrow());
    }

    @Test
    void nodeCollectionsAreDefensivelyCopiedAndUnmodifiable() {
        var children = new ArrayList<SqlNode>(List.of(new StaticTextSqlNode("SELECT 1")));
        var mixed = new MixedSqlNode(children);
        children.clear();
        assertEquals(1, mixed.getContents().size());
        assertThrows(UnsupportedOperationException.class, () -> mixed.getContents().clear());

        var branches = new ArrayList<>(List.of(new IfSqlNode("flag", mixed)));
        var choose = new ChooseSqlNode(branches, null);
        branches.clear();
        assertEquals(1, choose.getWhenContents().size());
        assertThrows(UnsupportedOperationException.class, () -> choose.getWhenContents().clear());
        assertThrows(NullPointerException.class, () -> new IfSqlNode("flag", null));
        assertThrows(IllegalArgumentException.class, () -> new BindSqlNode("", "1"));
    }

    @Test
    void equivalentTrimmingNodesShareDynamicClassification() {
        var literal = new StaticTextSqlNode("id=1");
        assertFalse(new WhereSqlNode(literal).isDynamic());
        assertFalse(new SetSqlNode(literal).isDynamic());
        assertFalse(new TrimSqlNode("", "", "", "", literal).isDynamic());
        assertEquals(SqlNodeType.BIND, new BindSqlNode("id", "1").getType());
    }
}
