package org.u80dc.jsqlint.core.node;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.semantics.ValueEvaluationLimitException;
import org.u80dc.jsqlint.core.template.SqlTemplateProjection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SqlNodeGraphCapacityTest {
    @Test void deeplyNestedStaticNodesHaveIterativeQueriesAndBoundedRendering() {
        SqlNode node = new StaticTextSqlNode("SELECT 1");
        for (int i = 0; i < 6000; i++) node = new MixedSqlNode(List.of(node));
        assertFalse(node.isDynamic());
        var rendered = SqlNodeRenderer.render(node, Map.of());
        assertFalse(rendered.complete());
        assertSame(node, rendered.residual());
        assertTrue(rendered.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")
                && diagnostic.message().contains("depth")));
    }

    @Test void sharedGraphsUseStructuralKeysWithoutRecursiveHashing() {
        SqlNode first = shared(40, "A");
        SqlNode equal = shared(40, "A");
        SqlNode different = shared(40, "B");
        var nodes = SqlNodeGraphs.distinct(List.of(first, first, equal, different), ReplayBudgets.defaults(), () -> {});
        assertEquals(2, nodes.size()); assertSame(first, nodes.getFirst()); assertSame(different, nodes.getLast());
        assertEquals(2, assertInstanceOf(ChooseSqlNode.class,
                SqlTemplateProjection.alternatives(List.of(first, equal, different))).whenContents().size());
        assertEquals(2, SqlNodeGraphs.distinct(List.of(ValueSqlNode.parameter("id", "jdbcType=INTEGER"),
                ValueSqlNode.parameter("id", "jdbcType=VARCHAR")), ReplayBudgets.defaults(), () -> {}).size());
    }

    @Test void limitedControlQueriesStayConservativeAndReportTheCapacityReason() {
        List<String> limits = new ArrayList<>();
        var staticNode = new MixedSqlNode(Collections.nCopies(20, new StaticTextSqlNode("x")));
        assertTrue(SqlNodeGraphs.containsControl(staticNode, ReplayBudgets.defaults().withSteps(4), () -> {}, limits::add));
        assertEquals(1, limits.size());
        assertThrows(ValueEvaluationLimitException.class,
                () -> SqlTemplateProjection.alternatives(List.of(shared(40, "A"), shared(40, "B")),
                        ReplayBudgets.defaults().withSteps(10), () -> {}));
    }

    @Test void outputLengthIsCheckedBeforeLargeForeachConcatenation() {
        var node = new ForEachSqlNode("items", "item", "", "(", ")", ",", ValueSqlNode.raw("item"));
        var rendered = SqlNodeRenderer.render(node, Map.of("items", List.of("abcdef", "abcdef")),
                SqlExpressionEvaluator.simple(), ReplayBudgets.defaults().withLength(12), () -> {});
        assertFalse(rendered.complete());
        assertTrue(rendered.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")));
    }

    private static SqlNode shared(int depth, String text) {
        SqlNode node = new StaticTextSqlNode(text);
        for (int i = 0; i < depth; i++) node = new MixedSqlNode(List.of(node, node));
        return node;
    }
}
