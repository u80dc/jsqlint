package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.node.ValueSqlNode;
import org.u80dc.jsqlint.core.parameter.InferredParameterValue;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import sootup.java.core.JavaIdentifierFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

class SqlBindingGraphBudgetTest {
    private final SourceLocation location = new SourceLocation(URI.create("memory:/Graph.java"), "query");
    private final SqlNode template = ValueSqlNode.parameter("value");
    private final SqlDefinition definition = new SqlDefinition("graph", location, template);

    @Test void equivalentIndependentDeepSharedEvidenceCreatesOneBinding() {
        var first = model(shared(6000, 7), 7);
        var second = model(shared(6000, 7), 7);
        var limits = new ArrayList<String>();
        var bindings = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> SqlBindingSnapshots.modelBindings(
                List.of(first, second), ReplayBudgets.defaults().withDepth(6000).withSteps(75_000), () -> {}, limits::add));
        assertEquals(1, bindings.size());
        assertSame(first.bindings().getFirst(), bindings.getFirst(), "The first saved evidence must represent an equivalent binding");
        assertTrue(limits.isEmpty());
    }

    @Test void comparisonLimitKeepsEveryModelAndReportsTheFallback() {
        var first = model(shared(30, 7), 7);
        var second = model(shared(30, 7), 7);
        var limits = new ArrayList<String>();
        var bindings = SqlBindingSnapshots.modelBindings(List.of(first, second),
                ReplayBudgets.defaults().withSteps(1), () -> {}, limits::add);
        assertEquals(2, bindings.size(), "Capacity cannot erase an available binding model");
        assertSame(first.bindings().getFirst(), bindings.get(0));
        assertSame(second.bindings().getFirst(), bindings.get(1));
        assertEquals(1, limits.size());
    }

    @Test void cancellationEscapesWithoutBecomingACapacityFallback() {
        var cancellation = new IllegalStateException("cancelled");
        var limits = new ArrayList<String>();
        var models = List.of(model(shared(30, 7), 7));
        var thrown = assertThrows(IllegalStateException.class, () -> SqlBindingSnapshots.modelBindings(models,
                ReplayBudgets.defaults(), () -> { throw cancellation; }, limits::add));
        assertSame(cancellation, thrown);
        assertTrue(limits.isEmpty());
    }

    @Test void modeledStatementKeepsEveryModelWithoutDeepEvidenceComparisonOnRead() {
        var first = model(shared(6000, 7), 7);
        var equivalent = model(shared(6000, 7), 7);
        var different = model(shared(0, 8), 8);
        var models = List.of(first, equivalent, different);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var inference = new TemplateInferenceResult(Map.of(), Map.of(), TemplateInputLayout.empty(), Optional.empty(), models, List.of());
            var statement = new SqlExtraction.Statement(Optional.empty(), Optional.empty(), List.of(),
                    new SqlExtraction.Statement.Content.Modeled(definition, inference));
            assertEquals(3, statement.bindings().size());
            assertSame(first.bindings().getFirst(), statement.bindings().getFirst());
            assertSame(equivalent.bindings().getFirst(), statement.bindings().get(1));
            assertSame(different.bindings().getFirst(), statement.bindings().getLast());
            assertSame(statement.bindings(), statement.bindings());
            assertSame(statement.rawSql(), statement.rawSql());
            assertEquals(3, statement.rawSql().variants().size());
        });
    }

    private TemplateInferenceResult.BoundModel model(Node root, int value) {
        ValueTrace source = trace(root);
        var inferred = new InferredParameterValue(new SqlValue.Known(value), source, List.of(), List.of());
        var inference = new ParameterValueInferenceResult("value", "int", List.of(inferred), List.of());
        var binding = new SqlExtraction.Binding("1", trace(shared(0, 1)), source, location, inference);
        return new TemplateInferenceResult.BoundModel(definition.render(Map.of("value", value)), List.of(binding), Optional.empty(), List.of());
    }

    private static ValueTrace trace(Node root) { return new ValueTrace(root.variable(), List.of(), root); }

    private static Node shared(int depth, int value) {
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Graph", "query", "void", List.of("int"));
        Node node = new Node(new Variable(signature, -1, "input", "int"), Kind.CONSTANT, Integer.toString(value), List.of(),
                new Computation(Operation.LITERAL, Integer.toString(value)));
        for (int i = 1; i <= depth; i++) node = new Node(new Variable(signature, -1, "v" + i, "int"), Kind.EXPRESSION,
                "equivalent alternatives", List.of(node, node), new Computation(Operation.ALTERNATIVES, ""));
        return node;
    }
}
