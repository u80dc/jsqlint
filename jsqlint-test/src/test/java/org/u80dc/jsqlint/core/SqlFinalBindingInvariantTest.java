package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.*;
import org.u80dc.jsqlint.core.spi.*;
import sootup.java.core.JavaIdentifierFactory;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 公开结果边界不能把一组已证明的绑定和另一组最终样例值关联起来。 */
class SqlFinalBindingInvariantTest {
    private static final SourceLocation LOCATION = new SourceLocation(URI.create("memory:/query.sql"), "query");

    @Test void canonicalVariantRejectsValuesThatDifferFromItsCombination() {
        var fixture = fixture(7);
        var changed = new SqlSample.Binding("1", "int", new SqlValue.Known(1), new SqlValue.Known(99), LOCATION);
        assertThrows(IllegalArgumentException.class, () -> fixture.variant(List.of(
                new RawSql.BindingSnapshot(List.of(changed), Optional.of(fixture.combination())))));
    }

    @Test void canonicalVariantRejectsChangedPositionAndForeignCombination() {
        var fixture = fixture(7);
        var changedPosition = new SqlSample.Binding("1", "int", new SqlValue.Known(2), new SqlValue.Known(7), LOCATION);
        assertThrows(IllegalArgumentException.class, () -> fixture.variant(List.of(
                new RawSql.BindingSnapshot(List.of(changedPosition), Optional.of(fixture.combination())))));
        var foreign = fixture(8);
        assertThrows(IllegalArgumentException.class, () -> fixture.variant(List.of(foreign.snapshot())));
    }

    @Test void canonicalVariantRejectsAnUnsupportedKnownValueWithoutJointInference() {
        var fixture = fixture(7);
        var changed = new SqlSample.Binding("1", "int", new SqlValue.Known(1), new SqlValue.Known(99), LOCATION);
        assertThrows(IllegalArgumentException.class, () -> new RawSql.Variant("SELECT ?", List.of(), Optional.empty(),
                List.of(fixture.binding()), Optional.empty(), List.of(new RawSql.BindingSnapshot(List.of(changed), Optional.empty()))));
    }

    @Test void definitionWithoutTemplateInferenceCannotOmitItsBindingSource() {
        var fixture = fixture(7);
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.SATISFIABLE, List.of(fixture.combination()), List.of(), true);
        var variant = new RawSql.Variant("SELECT ?", List.of(), Optional.empty(), List.of(), Optional.of(joint), List.of(fixture.snapshot()));
        var template = new StaticTextSqlNode("SELECT ?");
        assertThrows(IllegalArgumentException.class, () -> new SqlExtraction.Statement(Optional.empty(), template, List.of(),
                Optional.empty(), List.of(), List.of(), new RawSql(List.of(variant), List.of()),
                Optional.of(new SqlDefinition("query", LOCATION, template)), Optional.empty()));
    }

    @Test void canonicalVariantRejectsDuplicateAndUnsatisfiableModels() {
        var fixture = fixture(7);
        assertThrows(IllegalArgumentException.class, () -> fixture.variant(List.of(fixture.snapshot(), fixture.snapshot())));
        var unsatisfiable = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNSATISFIABLE, List.of(), List.of(), true);
        assertThrows(IllegalArgumentException.class, () -> new RawSql.Variant("SELECT ?", List.of(), Optional.empty(),
                List.of(fixture.binding()), Optional.of(unsatisfiable), List.of(fixture.snapshot())));
    }

    @Test void definitionWithoutTemplateInferenceCannotOmitItsJointBindingModel() {
        var first = fixture(7); var second = fixture(8);
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.SATISFIABLE,
                List.of(first.combination(), second.combination()), List.of(), true);
        var variant = new RawSql.Variant("SELECT ?", List.of(), Optional.empty(), List.of(first.binding()),
                Optional.of(joint), List.of(first.snapshot()));
        var template = new StaticTextSqlNode("SELECT ?");
        assertThrows(IllegalArgumentException.class, () -> new SqlExtraction.Statement(Optional.empty(), template,
                List.of(first.binding()), Optional.empty(), List.of(), List.of(), new RawSql(List.of(variant), List.of()),
                Optional.of(new SqlDefinition("query", LOCATION, template)), Optional.empty()));
    }

    @Test void validPublicDefinitionStatementUsesTheSameBindingModelForItsSample() {
        var fixture = fixture(7); var variant = fixture.variant(List.of(fixture.snapshot()));
        var template = new StaticTextSqlNode("SELECT ?");
        var statement = new SqlExtraction.Statement(Optional.empty(), template, List.of(fixture.binding()), Optional.empty(),
                List.of(), List.of(), new RawSql(List.of(variant), List.of()),
                Optional.of(new SqlDefinition("query", LOCATION, template)), Optional.empty());
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Query", "run", "void", List.of());
        var source = new SqlExtraction("test", new SqlCallSite(signature, Optional.empty(), LOCATION), List.of(), List.of(statement), List.of());
        var sample = source.samples().getFirst();
        assertEquals(SqlSample.Feasibility.SATISFIABLE, sample.feasibility());
        assertEquals(fixture.combination().bindings().getFirst().value().value(), sample.bindings().getFirst().value());
        assertSame(variant.bindingSnapshots().getFirst().bindings(), sample.bindings());
    }

    @Test void templateResultRejectsModelsContradictingItsUnsatisfiabilityProof() {
        var fixture = fixture(7);
        var model = boundModel(fixture, Optional.of(fixture.combination()));
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNSATISFIABLE, List.of(), List.of(), true);
        assertThrows(IllegalArgumentException.class, () -> templateState(Optional.of(joint), List.of(model)));
        assertDoesNotThrow(() -> templateState(Optional.of(joint), List.of()));
    }

    @Test void templateResultRejectsForeignCombinationsAndUnassociatedSolvedModels() {
        var fixture = fixture(7); var foreign = fixture(8);
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.SATISFIABLE,
                List.of(foreign.combination()), List.of(), true);
        assertThrows(IllegalArgumentException.class, () -> templateState(Optional.of(joint),
                List.of(boundModel(fixture, Optional.of(fixture.combination())))));
        assertThrows(IllegalArgumentException.class, () -> templateState(Optional.of(joint),
                List.of(boundModel(fixture, Optional.empty()))));
    }

    @Test void legacyIndependentAndUnknownFallbackTemplateModelsRemainLegal() {
        var fixture = fixture(7);
        var independent = boundModel(fixture, Optional.of(fixture.combination()));
        assertDoesNotThrow(() -> templateState(Optional.empty(), List.of(independent)));
        var unknown = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN, List.of(), List.of("No models"), false);
        assertDoesNotThrow(() -> templateState(Optional.of(unknown), List.of(boundModel(fixture, Optional.empty()))));
        var partial = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN,
                List.of(fixture.combination()), List.of("Coverage limit"), false);
        assertDoesNotThrow(() -> templateState(Optional.of(partial), List.of(independent)));
        assertThrows(IllegalArgumentException.class, () -> templateState(Optional.of(partial),
                List.of(boundModel(fixture, Optional.empty()))));
    }

    @Test void jointTemplateResultCannotOmitAvailableModelsOrUnknownFallback() {
        var first = fixture(7); var second = fixture(8);
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.SATISFIABLE,
                List.of(first.combination(), second.combination()), List.of(), true);
        assertThrows(IllegalArgumentException.class, () -> templateState(Optional.of(joint), List.of()));
        assertThrows(IllegalArgumentException.class, () -> templateState(Optional.of(joint),
                List.of(boundModel(first, Optional.of(first.combination())))));
        assertDoesNotThrow(() -> templateState(Optional.of(joint), List.of(
                boundModel(first, Optional.of(first.combination())), boundModel(second, Optional.of(second.combination())))));
        var unknown = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN, List.of(), List.of("No resolved input"), false);
        assertThrows(IllegalArgumentException.class, () -> templateState(Optional.of(unknown), List.of()));
    }

    private static TemplateInferenceResult.BoundModel boundModel(Fixture fixture,
            Optional<JointSqlInferenceResult.Combination> combination) {
        var definition = new SqlDefinition("query", LOCATION, SqlNodeBuilder.templateText("SELECT #{value}"));
        return new TemplateInferenceResult.BoundModel(definition.render(Map.of("value",
                ((SqlValue.Known) fixture.combination().bindings().getFirst().value().value()).value())),
                List.of(fixture.binding()), combination, List.of());
    }

    private static TemplateInferenceResult templateState(Optional<JointSqlInferenceResult> joint,
                                                        List<TemplateInferenceResult.BoundModel> models) {
        return new TemplateInferenceResult(Map.of(), Map.of(), Map.of(), Optional.empty(), Optional.empty(), Map.of(),
                joint, models, List.of());
    }

    private record Fixture(SqlExtraction.Binding binding, JointSqlInferenceResult.Combination combination,
                           RawSql.BindingSnapshot snapshot) {
        RawSql.Variant variant(List<RawSql.BindingSnapshot> snapshots) {
            var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.SATISFIABLE, List.of(combination), List.of(), true);
            return new RawSql.Variant("SELECT ?", List.of(), Optional.empty(), List.of(binding), Optional.of(joint), snapshots);
        }
    }

    private static Fixture fixture(int number) {
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("example.Query", "run", "void", List.of());
        var keyVariable = new ValueTrace.Variable(signature, 0, "position", "int");
        var valueVariable = new ValueTrace.Variable(signature, 0, "value", "int");
        var keyTrace = new ValueTrace(keyVariable, List.of(), new ValueTrace.Node(keyVariable, ValueTrace.Kind.CONSTANT,
                "1", List.of(), new ValueTrace.Computation(ValueTrace.Operation.LITERAL, "1")));
        var valueTrace = new ValueTrace(valueVariable, List.of(), new ValueTrace.Node(valueVariable, ValueTrace.Kind.CONSTANT,
                Integer.toString(number), List.of(), new ValueTrace.Computation(ValueTrace.Operation.LITERAL, Integer.toString(number))));
        var keyValue = new InferredParameterValue(new SqlValue.Known(1), keyTrace, List.of(), List.of());
        var value = new InferredParameterValue(new SqlValue.Known(number), valueTrace, List.of(), List.of());
        var inference = new ParameterValueInferenceResult("value", "int", List.of(value), List.of());
        var binding = new SqlExtraction.Binding("1", keyTrace, valueTrace, LOCATION, inference);
        var combination = new JointSqlInferenceResult.Combination(JointSqlInferenceResult.Status.SATISFIABLE, Map.of(),
                List.of(new JointSqlInferenceResult.BindingValue("1", keyValue, value)), List.of(), Map.of(), List.of());
        var snapshot = new RawSql.BindingSnapshot(List.of(new SqlSample.Binding("1", "int", keyValue.value(), value.value(),
                LOCATION, "", Optional.of(valueTrace))), Optional.of(combination));
        return new Fixture(binding, combination, snapshot);
    }
}
