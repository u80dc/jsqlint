package org.u80dc.jsqlint.core.template;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.node.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlTemplateChoiceIsolationTest {
    @Test void ordinarySingleLayerKeepsTheLegacyChoiceName() {
        var node = assertInstanceOf(ChooseSqlNode.class,
                SqlTemplateProjection.alternatives(List.of(SqlNodeBuilder.text("A"), SqlNodeBuilder.text("B"))));
        assertEquals("choice_0 == 0", node.whenContents().getFirst().test());
        assertEquals("B", SqlNodeRenderer.render(node, Map.of("choice_0", 1)).sql().orElseThrow());
    }

    @Test void nestedCandidateGroupsCanSelectAllFourAlternatives() {
        var first = SqlTemplateProjection.alternatives(List.of(SqlNodeBuilder.text("A"), SqlNodeBuilder.text("B")));
        var second = SqlTemplateProjection.alternatives(List.of(SqlNodeBuilder.text("C"), SqlNodeBuilder.text("D")));
        var node = assertInstanceOf(ChooseSqlNode.class, SqlTemplateProjection.alternatives(List.of(first, second)));
        assertEquals("choice_1 == 0", node.whenContents().getFirst().test());
        assertEquals(List.of("A", "B", "C", "D"), List.of(
                render(node, 0, 0), render(node, 0, 1), render(node, 1, 0), render(node, 1, 1)));
    }

    @Test void syntheticSelectorsAvoidBusinessReferencesAndLocalNames() {
        var business = SqlNodeBuilder.templateText("SELECT ${choice_0},#{choice_1}");
        var node = assertInstanceOf(ChooseSqlNode.class,
                SqlTemplateProjection.alternatives(List.of(business, SqlNodeBuilder.text("SELECT 2"))));
        assertEquals("choice_2 == 0", node.whenContents().getFirst().test());
        var rendered = SqlNodeRenderer.renderBound(node, Map.of("choice_2", 0, "choice_0", "id", "choice_1", 7),
                SqlExpressionEvaluator.simple());
        assertEquals("SELECT id,?", rendered.rendered().sql().orElseThrow());
        assertEquals(new SqlValue.Known(7), rendered.bindings().getFirst().value());

        var locals = SqlNodeBuilder.buildScript("<bind name='choice_0' value=\"'x'\"/><foreach collection='values' item='choice_1' index='choice_2'>${choice_1}</foreach>");
        var localChoice = assertInstanceOf(ChooseSqlNode.class,
                SqlTemplateProjection.alternatives(List.of(locals, SqlNodeBuilder.text("other"))));
        assertEquals("choice_3 == 0", localChoice.whenContents().getFirst().test());
        assertEquals("ab", SqlNodeRenderer.render(localChoice,
                Map.of("choice_3", 0, "values", List.of("a", "b"))).sql().orElseThrow());
    }

    private static String render(SqlNode node, int outer, int inner) {
        return SqlNodeRenderer.render(node, Map.of("choice_1", outer, "choice_0", inner)).sql().orElseThrow();
    }
}
