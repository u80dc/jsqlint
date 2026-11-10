package org.u80dc.jsqlint.mybatis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** MyBatis 适配保持框架语法边界，同时复用通用 DSL 并保留 XML 文本语义。 @author u80dc */
class MybatisAdapterTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {
            "<literal>SELECT 1</literal>",
            "SELECT <value expression='table'/>",
            "SELECT <foreach collection='ids' item='id' separator=',' skipBlank='false'>#{id}</foreach>"
    })
    void genericDslExtensionsRemainAvailableButAreNotAcceptedAsMybatis(String body) throws Exception {
        assertNotNull(SqlNodeBuilder.buildScript(body));
        assertThrows(IllegalArgumentException.class, () -> MybatisSqlAdapter.annotation("<script>" + body + "</script>"));
        Files.writeString(temporary.resolve("mapper.xml"), "<mapper namespace='Mapper'><select id='bad'>" + body
                + "</select><select id='good'>SELECT 7</select></mapper>");
        var result = new Analyzer(List.of(new MybatisAnalysisModule()), List.of()).analyze(
                new AnalysisRequest(List.of(), List.of(new PathResourceSource(temporary))));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertEquals(2, result.sqlResults().size());
        assertEquals(List.of("SELECT 7"), result.rawSqls().stream().map(RawSql.Variant::text).toList());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("Unsupported MyBatis")));
    }

    @Test
    void annotationScriptPreservesBindChooseAndTypedLiteralRendering() {
        var node = MybatisSqlAdapter.annotation("""
                <script><bind name='name2' value="name + '!'"/>SELECT #{id},
                <choose><when test='enabled'>#{name2}</when><otherwise>'off'</otherwise></choose></script>
                """);
        var rendered = SqlNodeRenderer.render(node, Map.of("id", 7, "name", "O'Neil", "enabled", true));
        assertEquals("SELECT 7, 'O''Neil!'", rendered.sql().orElseThrow().replaceAll("\\s+", " ").strip());
        assertFalse(SqlNodeRenderer.render(node, Map.of()).complete());
        assertEquals(new StaticTextSqlNode("SELECT a < b"), MybatisSqlAdapter.annotation("SELECT a < b"));
    }

    @Test
    void mapperDomCanBeAdaptedRepeatedlyWithoutChangingSharedFragments() throws Exception {
        var builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        var document = builder.parse(new InputSource(new StringReader("""
                <mapper namespace='shared.Columns'>
                  <sql id='columns'><![CDATA['a < b & c']]></sql>
                  <sql id='nested'><include refid='columns'/></sql>
                  <select id='query' resultType='java.lang.String'>SELECT <include refid='nested'/>
                FROM users -- keep newline
                WHERE id=1</select>
                </mapper>
                """)));
        Element mapper = document.getDocumentElement();
        Element statement = (Element) mapper.getElementsByTagName("select").item(0);
        Map<String, List<Element>> fragments = new LinkedHashMap<>();
        var sqls = mapper.getElementsByTagName("sql");
        for (int i = 0; i < sqls.getLength(); i++) {
            Element fragment = (Element) sqls.item(i);
            fragments.put("shared.Columns." + fragment.getAttribute("id"), List.of(fragment));
        }
        String before = mapper.getTextContent();
        int references = mapper.getElementsByTagName("include").getLength();
        var first = MybatisSqlAdapter.statement(statement, "shared.Columns", fragments);
        var second = MybatisSqlAdapter.statement(statement, "shared.Columns", fragments);
        String text = SqlNodeRenderer.render(first, Map.of()).sql().orElseThrow();
        assertEquals(text, SqlNodeRenderer.render(second, Map.of()).sql().orElseThrow());
        assertTrue(text.contains("'a < b & c'"));
        assertTrue(text.contains("-- keep newline\n"));
        assertEquals(before, mapper.getTextContent());
        assertEquals(references, mapper.getElementsByTagName("include").getLength());
    }
}
