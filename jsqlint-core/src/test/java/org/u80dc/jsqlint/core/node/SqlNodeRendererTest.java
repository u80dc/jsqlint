package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlNodeRendererTest {
    private static SqlNode script(String source) { return SqlNodeBuilder.buildScript(source); }

    @Test
    void joinsFragmentsWithoutInventingWhitespace() {
        var node = new MixedSqlNode(List.of(new StaticTextSqlNode("SELECT pre"),
                new IfSqlNode("enabled", new StaticTextSqlNode("fix")), new StaticTextSqlNode(" FROM users")));
        assertEquals("SELECT prefix FROM users", SqlNodeRenderer.render(node, Map.of("enabled", true)).sql().orElseThrow());
    }

    @Test
    void keepsWhitespaceInsideSubstitutedLiteral() {
        var result = SqlNodeRenderer.render(script("SELECT #{name}"), Map.of("name", "a  b\n( x )'s"));
        assertEquals("SELECT 'a  b\n( x )''s'", result.sql().orElseThrow());
    }

    @Test
    void knownNullIsDifferentFromMissingParameter() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", null);
        var node = script("SELECT #{id,jdbcType=INTEGER}");
        assertEquals("SELECT NULL", SqlNodeRenderer.render(node, values).sql().orElseThrow());
        assertEquals("SELECT 7", SqlNodeRenderer.render(node, Map.of("id", 7)).sql().orElseThrow());
        var missing = SqlNodeRenderer.render(node, Map.of());
        assertFalse(missing.complete());
        assertTrue(missing.sql().isEmpty());
        assertFalse(missing.diagnostics().isEmpty());
        assertEquals("SELECT 8", SqlNodeRenderer.render(missing.residual(), Map.of("id", 8)).sql().orElseThrow());
    }

    @Test
    void unknownRawInsertionDoesNotBecomeEmptyText() {
        var node = script("SELECT * FROM users ORDER BY " + "$" + "{column}");
        var result = SqlNodeRenderer.render(node, Map.of());
        assertFalse(result.complete());
        assertFalse(result.diagnostics().isEmpty());
        assertEquals("SELECT * FROM users ORDER BY id",
                SqlNodeRenderer.render(result.residual(), Map.of("column", "id")).sql().orElseThrow());
    }

    @Test
    void unknownIfRetainsTheBranchInsteadOfTreatingItAsFalse() {
        var node = script("SELECT * FROM users <if test='enabled'>WHERE active=1</if> ORDER BY id");
        var result = SqlNodeRenderer.render(node, Map.of());
        assertFalse(result.complete());
        assertFalse(result.diagnostics().isEmpty());
        assertEquals("SELECT * FROM users WHERE active=1 ORDER BY id",
                SqlNodeRenderer.render(result.residual(), Map.of("enabled", true)).sql().orElseThrow());
        assertEquals("SELECT * FROM users  ORDER BY id",
                SqlNodeRenderer.render(result.residual(), Map.of("enabled", false)).sql().orElseThrow());
    }

    @Test
    void unknownChooseCannotFallThroughToOtherwise() {
        var node = script("SELECT <choose><when test='enabled'>1</when><otherwise>2</otherwise></choose>");
        var result = SqlNodeRenderer.render(node, Map.of());
        assertFalse(result.complete());
        assertEquals("SELECT 1", SqlNodeRenderer.render(result.residual(), Map.of("enabled", true)).sql().orElseThrow());
        assertEquals("SELECT 2", SqlNodeRenderer.render(result.residual(), Map.of("enabled", false)).sql().orElseThrow());
    }

    @Test
    void unsupportedExpressionIsDiagnosedAndCanUseAnInjectedEvaluator() {
        var node = script("SELECT * FROM users <if test=\"name.startsWith('A')\">WHERE active=1</if>");
        var result = SqlNodeRenderer.render(node, Map.of("name", "Alice"));
        assertFalse(result.complete());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("Unsupported method")));
        var supplied = SqlNodeRenderer.render(node, Map.of("name", "Alice"),
                (expression, parameters) -> new SqlValue.Known(true));
        assertEquals("SELECT * FROM users WHERE active=1", supplied.sql().orElseThrow());
    }

    @Test
    void evaluatorExceptionsNeverSilentlyDropACondition() {
        var result = SqlNodeRenderer.render(new IfSqlNode("flag", new StaticTextSqlNode("WHERE id=1")),
                Map.of(), (expression, values) -> { throw new IllegalStateException("unavailable"); });
        assertFalse(result.complete());
        assertTrue(result.diagnostics().getFirst().message().contains("unavailable"));
    }

    @Test
    void unknownBindStopsDependentTailUntilItCanBeResolved() {
        var result = SqlNodeRenderer.render(script("<bind name='pattern' value=\"'%' + name + '%'\"/>SELECT #{pattern}"), Map.of());
        assertFalse(result.complete());
        assertEquals("SELECT '%Alice%'", SqlNodeRenderer.render(result.residual(), Map.of("name", "Alice")).sql().orElseThrow());
    }

    @Test
    void unresolvedWrapperDoesNotCommitPartiallyEvaluatedBinds() {
        var node = script("<where><bind name='x' value='x + 1'/>id=#{missing}</where> #{x}");
        var result = SqlNodeRenderer.render(node, Map.of("x", 1));
        assertFalse(result.complete());
        assertEquals(1, result.parameters().get("x"));
        Map<String, Object> completed = new LinkedHashMap<>(result.parameters());
        completed.put("missing", 8);
        assertEquals("WHERE id=8 2", SqlNodeRenderer.render(result.residual(), completed).sql().orElseThrow());
    }

    @Test
    void knownTextIsNotReinterpretedAsAPlaceholderDuringResume() {
        var node = script("SELECT #{known}, #{missing}");
        var result = SqlNodeRenderer.render(node, Map.of("known", "#{literal}"));
        assertFalse(result.complete());
        assertEquals("SELECT '#{literal}', 9",
                SqlNodeRenderer.render(result.residual(), Map.of("missing", 9)).sql().orElseThrow());
    }

    @Test
    void distinguishesMissingCollectionFromKnownEmptyCollection() {
        var node = script("SELECT <foreach collection='ids' item='id' open='(' close=')' separator=','>#{id}</foreach>");
        var missing = SqlNodeRenderer.render(node, Map.of());
        assertFalse(missing.complete());
        assertEquals("SELECT ", SqlNodeRenderer.render(node, Map.of("ids", List.of())).sql().orElseThrow());
        assertEquals("SELECT (1,2)", SqlNodeRenderer.render(node, Map.of("ids", List.of(1, 2))).sql().orElseThrow());
        Map<String, Object> actualNull = new LinkedHashMap<>();
        actualNull.put("ids", null);
        assertFalse(SqlNodeRenderer.render(node, actualNull).complete());
    }

    @Test
    void foreachBindingsAreScopedAndMapIndexesAreAvailable() {
        var values = new LinkedHashMap<String, Integer>();
        values.put("a", 1);
        values.put("b", 2);
        var node = script("<foreach collection='values' item='item' index='key' separator=','>#{key}=#{item}</foreach> #{item}");
        assertEquals("'a'=1,'b'=2 'outer'", SqlNodeRenderer.render(node,
                Map.of("values", values, "item", "outer")).sql().orElseThrow());
    }

    @Test
    void trimmingPreservesCommentTerminatingNewline() {
        assertEquals("SELECT * FROM users WHERE active=1 -- note\n ORDER BY id",
                SqlNodeRenderer.render(script("SELECT * FROM users <where>AND active=1 -- note\n</where> ORDER BY id"),
                        Map.of()).sql().orElseThrow());
        assertEquals("UPDATE users SET name='a  b'\n WHERE id=1",
                SqlNodeRenderer.render(script("UPDATE users <set>name='a  b',\n</set> WHERE id=1"),
                        Map.of()).sql().orElseThrow());
    }

    @Test
    void malformedPlaceholderHasAnExplicitIncompleteResult() {
        var result = SqlNodeRenderer.render(new TextSqlNode("SELECT #{id"), Map.of("id", 1));
        assertFalse(result.complete());
        assertEquals("INVALID_PARAMETER", result.diagnostics().getFirst().code());
    }

    @Test
    void providedSamplesDoNotGenerateExtraCombinations() {
        var samples = List.of(Map.of("a", "x", "b", 1), Map.of("a", "y", "b", 2));
        var results = SqlNodeRenderer.renderSamples(script("SELECT #{a}, #{b}"), samples);
        assertEquals(List.of("SELECT 'x', 1", "SELECT 'y', 2"),
                results.stream().map(r -> r.sql().orElseThrow()).toList());
    }
}
