package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.InferredParameterValue;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import org.u80dc.jsqlint.core.spi.SqlCallSite;
import sootup.java.core.JavaIdentifierFactory;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SqlResultModelContractTest {
    private static final SourceLocation LOCATION = new SourceLocation(URI.create("memory:/mapper.xml"), "Mapper.query");

    @Test void definitionSamplesPreserveBoundReplayModeIncludingLegacyUnavailableSnapshots() {
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT #{id}"));
        var bound = definition.render(Map.of("id", 7));
        assertEquals(SqlNodeRenderer.RenderedSql.RecoveryMode.BOUND_REPLAY, bound.rendered().recoveryMode());
        var source = new SqlTemplateResult("test", LOCATION, definition.template(), Optional.empty(),
                List.of(), Optional.of(definition), Optional.of(bound));
        assertEquals(SqlNodeRenderer.RenderedSql.RecoveryMode.BOUND_REPLAY, source.samples().getFirst().rendered().recoveryMode());
        var legacy = new SqlTemplateResult("test", LOCATION, definition.template(), Optional.empty(), List.of(), Optional.of(definition));
        assertTrue(legacy.samples().getFirst().sql().isEmpty());
        assertEquals(SqlNodeRenderer.RenderedSql.RecoveryMode.BOUND_REPLAY, legacy.samples().getFirst().rendered().recoveryMode());
    }

    @Test void definitionAndSavedTemplatesMustDescribeTheSameTree() {
        var node = SqlNodeBuilder.templateText("SELECT #{id}");
        var definition = new SqlDefinition("query", LOCATION, node);
        var different = SqlNodeBuilder.text("SELECT 2");
        assertThrows(IllegalArgumentException.class, () -> new SqlTemplateResult("test", LOCATION, different,
                Optional.empty(), List.of(), Optional.of(definition)));
        assertThrows(IllegalArgumentException.class, () -> new SqlExtraction.Statement(Optional.empty(), different, List.of(),
                Optional.empty(), List.of(), List.of(), new RawSql(List.of(), List.of()), Optional.of(definition), Optional.empty()));
        var unrelatedBound = SqlNodeRenderer.renderBound(different, Map.of(), SqlExpressionEvaluator.simple());
        assertThrows(IllegalArgumentException.class, () -> new SqlTemplateResult("test", LOCATION, node,
                Optional.empty(), List.of(), Optional.of(definition), Optional.of(unrelatedBound)));

        var sameTree = SqlNodeBuilder.templateText("SELECT #{id}");
        assertDoesNotThrow(() -> new SqlTemplateResult("test", LOCATION, sameTree,
                Optional.empty(), List.of(), Optional.of(definition)));
        var javaSql = trace("providerSql", "java.lang.String", "SELECT ?");
        var statement = new SqlExtraction.Statement(Optional.of(javaSql), sameTree, List.of(), Optional.empty(),
                List.of(), List.of(), new RawSql(List.of(), List.of()), Optional.of(definition), Optional.empty());
        assertEquals(javaSql, statement.sql().orElseThrow());
        assertEquals(definition, statement.definition().orElseThrow());
    }

    @Test void boundModelsRejectReorderedEvidenceAndDifferentResolvedValuesAtConstruction() {
        var node = SqlNodeBuilder.templateText("SELECT #{first},#{second}");
        var fixture = fixture(node, Map.of("first", 11, "second", 22));
        var reversed = List.of(fixture.bindings().getLast(), fixture.bindings().getFirst());
        assertThrows(IllegalArgumentException.class, () -> new TemplateInferenceResult.BoundModel(
                fixture.bound(), reversed, Optional.empty(), List.of()));

        var first = fixture.bindings().getFirst();
        var wrongValue = new InferredParameterValue(new SqlValue.Known(99), first.value(), List.of(), List.of());
        var wrongInference = new ParameterValueInferenceResult(first.inference().name(), first.inference().type(),
                List.of(wrongValue), List.of());
        var wrongEvidence = new SqlExtraction.Binding(first.key(), first.keyTrace(), first.value(), LOCATION,
                wrongInference, first.options());
        assertThrows(IllegalArgumentException.class, () -> new TemplateInferenceResult.BoundModel(fixture.bound(),
                List.of(wrongEvidence, fixture.bindings().getLast()), Optional.empty(), List.of()));

        var combination = fixture.combination();
        var reversedCombination = new JointSqlInferenceResult.Combination(combination.status(), combination.parameters(),
                List.of(combination.bindings().getLast(), combination.bindings().getFirst()), combination.conditions(),
                combination.inputs(), combination.issues());
        assertThrows(IllegalArgumentException.class, () -> new TemplateInferenceResult.BoundModel(fixture.bound(),
                fixture.bindings(), Optional.of(reversedCombination), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TemplateInferenceResult.BoundModel(fixture.bound(),
                List.of(first), Optional.empty(), List.of()));
    }

    @Test void modelAssociationRejectsDifferentFinalBindingSnapshots() {
        var node = SqlNodeBuilder.templateText("SELECT #{id}");
        var fixture = fixture(node, Map.of("id", 11));
        var model = fixture.model();
        var slot = SqlBindingSnapshots.bound(fixture.bound(), LOCATION, fixture.bindings()).getFirst();
        var changed = new SqlSample.Binding(slot.key(), slot.type(), slot.keyValue(), new SqlValue.Known(22),
                slot.location(), slot.options(), slot.trace());
        var variant = new RawSql.Variant("SELECT ?", List.of(), Optional.empty(), fixture.bindings(), Optional.empty(),
                List.of(new RawSql.BindingSnapshot(List.of(changed), Optional.of(fixture.combination()))));
        assertThrows(IllegalArgumentException.class, () -> model.withRawSql(variant));
    }

    @Test void identicalSqlModelsShareTheirOwnAuthoritativeSnapshotsWithSamples() {
        var node = SqlNodeBuilder.templateText("SELECT #{id}");
        var definition = new SqlDefinition("query", LOCATION, node);
        var first = fixture(node, Map.of("id", 11));
        var second = fixture(node, Map.of("id", 22));
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.SATISFIABLE,
                List.of(first.combination(), second.combination()), List.of());
        var firstVariant = variant(first, Optional.of(joint));
        var secondVariant = variant(second, Optional.of(joint));
        var firstModel = first.model().withRawSql(firstVariant);
        var secondModel = second.model().withRawSql(secondVariant);
        var state = state(List.of(firstModel, secondModel), Optional.of(joint));
        var bindings = List.of(first.bindings().getFirst(), second.bindings().getFirst());
        var raw = new RawSql(List.of(secondVariant, firstVariant), List.of());
        var statement = new SqlExtraction.Statement(Optional.empty(), node, bindings, Optional.empty(), List.of(), List.of(),
                raw, Optional.of(definition), Optional.of(state));
        var source = extraction(statement);
        var samples = source.samples();
        assertEquals(List.of(new SqlValue.Known(11), new SqlValue.Known(22)), samples.stream()
                .map(sample -> sample.bindings().getFirst().value()).toList());
        assertSame(firstVariant, samples.getFirst().rawSql().orElseThrow());
        assertSame(secondVariant, samples.getLast().rawSql().orElseThrow());
        assertSame(firstVariant.bindingSnapshots().getFirst().bindings(), samples.getFirst().bindings());
        assertSame(secondVariant.bindingSnapshots().getFirst().bindings(), samples.getLast().bindings());
        assertEquals(samples, source.samples());

        assertThrows(IllegalArgumentException.class, () -> new SqlExtraction.Statement(Optional.empty(), node, bindings,
                Optional.empty(), List.of(), List.of(), new RawSql(List.of(firstVariant), List.of()), Optional.of(definition), Optional.of(state)));
    }

    @Test void legacyBoundModelConstructorExplicitlyLeavesRawSqlUnassociated() {
        var node = SqlNodeBuilder.templateText("SELECT #{id}");
        var fixture = fixture(node, Map.of("id", 11));
        var model = fixture.model();
        assertTrue(model.rawSql().isEmpty());
        var statement = new SqlExtraction.Statement(Optional.empty(), node, fixture.bindings(), Optional.empty(), List.of(), List.of(),
                new RawSql(List.of(variant(fixture, Optional.empty())), List.of()),
                Optional.of(new SqlDefinition("query", LOCATION, node)), Optional.of(state(List.of(model), Optional.empty())));
        var sample = extraction(statement).samples().getFirst();
        assertTrue(sample.rawSql().isEmpty());
        assertEquals(new SqlValue.Known(11), sample.bindings().getFirst().value());
    }

    private record Fixture(SqlNodeRenderer.BoundSql bound, List<SqlExtraction.Binding> bindings,
                           JointSqlInferenceResult.Combination combination) {
        TemplateInferenceResult.BoundModel model() {
            return new TemplateInferenceResult.BoundModel(bound, bindings, Optional.of(combination), List.of());
        }
    }

    private static Fixture fixture(SqlNode node, Map<String, ?> parameters) {
        var bound = SqlNodeRenderer.renderBound(node, parameters, SqlExpressionEvaluator.simple());
        var bindings = new ArrayList<SqlExtraction.Binding>();
        var values = new ArrayList<JointSqlInferenceResult.BindingValue>();
        for (var slot : bound.bindings()) {
            var keyTrace = trace("position:" + slot.position(), "int", slot.position());
            var valueTrace = trace(slot.expression(), "int", ((SqlValue.Known) slot.value()).value());
            var value = new InferredParameterValue(slot.value(), valueTrace, List.of(), List.of());
            var inference = new ParameterValueInferenceResult(slot.expression(), "int", List.of(value), List.of());
            var key = Integer.toString(slot.position());
            bindings.add(new SqlExtraction.Binding(key, keyTrace, valueTrace, LOCATION, inference, slot.options()));
            values.add(new JointSqlInferenceResult.BindingValue(key,
                    new InferredParameterValue(new SqlValue.Known(slot.position()), keyTrace, List.of(), List.of()), value));
        }
        return new Fixture(bound, List.copyOf(bindings), new JointSqlInferenceResult.Combination(
                JointSqlInferenceResult.Status.SATISFIABLE, Map.of(), values, List.of(), Map.of(), List.of()));
    }

    private static RawSql.Variant variant(Fixture fixture, Optional<JointSqlInferenceResult> joint) {
        var snapshot = new RawSql.BindingSnapshot(SqlBindingSnapshots.bound(fixture.bound(), LOCATION, fixture.bindings()),
                Optional.of(fixture.combination()));
        return new RawSql.Variant(fixture.bound().rendered().sql().orElseThrow(), List.of(), Optional.empty(),
                fixture.bindings(), joint, List.of(snapshot));
    }

    private static TemplateInferenceResult state(List<TemplateInferenceResult.BoundModel> models, Optional<JointSqlInferenceResult> joint) {
        return new TemplateInferenceResult(Map.of(), Map.of(), Map.of(), Optional.empty(), Optional.empty(), Map.of(),
                joint, models, List.of());
    }

    private static SqlExtraction extraction(SqlExtraction.Statement statement) {
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Mapper", "query", "void", List.of());
        return new SqlExtraction("test", new SqlCallSite(signature, Optional.of(signature), LOCATION), List.of(), List.of(statement), List.of());
    }

    private static ValueTrace trace(String expression, String type, Object literal) {
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Mapper", "query", "void", List.of());
        var variable = new ValueTrace.Variable(signature, 0, expression, type, List.of());
        return new ValueTrace(variable, List.of(), new ValueTrace.Node(variable, ValueTrace.Kind.CONSTANT, "Saved constant",
                List.of(), new ValueTrace.Computation(ValueTrace.Operation.LITERAL, String.valueOf(literal))));
    }
}
