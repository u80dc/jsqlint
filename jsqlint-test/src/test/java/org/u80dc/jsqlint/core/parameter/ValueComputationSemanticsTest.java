package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.semantics.JavaValueOperations;
import sootup.java.core.JavaIdentifierFactory;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** 验证运算文案变化不改变非空、强制转换及拼接的语义，并保持旧快照的重演能力。 */
class ValueComputationSemanticsTest {
    @Test
    void displayTextDoesNotChangeGuardedOperationReplay() {
        for (String label : List.of("first description", "renamed description")) {
            var text = literal("value", "java.lang.String");
            var missing = node(Operation.NULL, "null", "null");
            for (Operation operation : List.of(Operation.REQUIRE_NON_NULL, Operation.STRING_COPY, Operation.STRING_CAST)) {
                assertEquals("value", known(node(operation, label, "java.lang.String", text)));
            }
            assertNull(known(node(Operation.STRING_CAST, label, "java.lang.String", missing)));
            assertUnknown(node(Operation.REQUIRE_NON_NULL, label, "java.lang.String", missing));
            assertUnknown(node(Operation.STRING_COPY, label, "java.lang.String", missing));
            assertUnknown(node(Operation.STRING_CAST, label, "java.lang.String", literal("42", "int")));
            assertEquals("valuevalue", known(node(Operation.STRING_CONCAT, label, "java.lang.String", text, text)));
            assertUnknown(node(Operation.STRING_CONCAT, label, "java.lang.String", text, missing));
            assertEquals("valuenull", known(node(Operation.CONCAT, label, "java.lang.String", text, missing)));
        }
    }

    @Test
    void legacySnapshotsNormalizeAtTheInputBoundary() {
        var text = literal("value", "java.lang.String");
        var missing = node(Operation.NULL, "null", "null");
        var required = node(Operation.IDENTITY, "Objects.requireNonNull", "java.lang.String", missing);
        var copied = node(Operation.IDENTITY, "String constructor", "java.lang.String", missing);
        var cast = node(Operation.IDENTITY, "String cast", "java.lang.String", literal("42", "int"));
        var concatenated = node(Operation.CONCAT, "String.concat", "java.lang.String", text, missing);
        assertEquals(Operation.REQUIRE_NON_NULL, required.computation().operation());
        assertEquals(Operation.STRING_COPY, copied.computation().operation());
        assertEquals(Operation.STRING_CAST, cast.computation().operation());
        assertEquals(Operation.STRING_CONCAT, concatenated.computation().operation());
        for (Node guarded : List.of(required, copied, cast, concatenated)) assertUnknown(guarded);
    }

    @Test
    void predicateSolverUsesTheSameGuardedSemanticsAfterDescriptionChanges() {
        for (String label : List.of("first description", "renamed description")) {
            var missing = node(Operation.NULL, "null", "null");
            var required = node(Operation.REQUIRE_NON_NULL, label, "java.lang.String", literal("value", "java.lang.String"));
            var condition = node(Operation.BINARY, "!=", "boolean", required, missing);
            var solution = new PathPredicateSolver(() -> {}).solve(List.of(new JointSqlInferenceResult.Condition(trace(condition), true)));
            assertEquals(JointSqlInferenceResult.Status.SATISFIABLE, solution.status(), solution.toString());
            var invalid = node(Operation.STRING_CONCAT, label, "java.lang.String", required, missing);
            var invalidCondition = node(Operation.BINARY, "!=", "boolean", invalid, missing);
            var invalidSolution = new PathPredicateSolver(() -> {}).solve(List.of(new JointSqlInferenceResult.Condition(trace(invalidCondition), true)));
            assertEquals(JointSqlInferenceResult.Status.UNKNOWN, invalidSolution.status(), invalidSolution.toString());
        }
    }

    @Test
    void typedIdentityAndSelectedAlternativesAgreeBetweenReplayAndPredicateSolving() {
        for (Operation operation : List.of(Operation.IDENTITY, Operation.ALTERNATIVES)) {
            var bool = node(operation, "typed boolean", "boolean", literal("1", "int"));
            assertEquals(true, known(bool));
            var truth = new PathPredicateSolver(() -> {}).solve(List.of(new JointSqlInferenceResult.Condition(trace(bool), true)));
            assertEquals(JointSqlInferenceResult.Status.SATISFIABLE, truth.status());

            var narrowed = node(operation, "typed byte", "byte", literal("257", "int"));
            assertEquals((byte) 1, known(narrowed));
            var equal = node(Operation.BINARY, "==", "boolean", narrowed, literal("1", "int"));
            var equality = new PathPredicateSolver(() -> {}).solve(List.of(new JointSqlInferenceResult.Condition(trace(equal), true)));
            assertEquals(JointSqlInferenceResult.Status.SATISFIABLE, equality.status());
        }
    }

    @Test
    void missingTargetLocaleIsNeverReplacedByTheAnalyzersLocale() {
        assertNotEquals("i".toUpperCase(Locale.ENGLISH), "i".toUpperCase(Locale.forLanguageTag("tr")));
        for (String operation : List.of("toUpperCase", "toLowerCase")) {
            var signature = JavaIdentifierFactory.getInstance().getMethodSignature("java.lang.String", operation,
                    "java.lang.String", List.of());
            assertFalse(JavaValueOperations.supports(signature));
            var source = literal("i", "java.lang.String");
            var converted = new Node(source.variable(), Kind.EXPRESSION, "ambient locale conversion", List.of(source),
                    new Computation(Operation.CALL, operation, Optional.of(signature)));
            assertUnknown(converted);
            var compared = node(Operation.BINARY, "!=", "boolean", converted, node(Operation.NULL, "null", "null"));
            var solution = new PathPredicateSolver(() -> {}).solve(List.of(new JointSqlInferenceResult.Condition(trace(compared), true)));
            assertEquals(JointSqlInferenceResult.Status.UNKNOWN, solution.status());
        }
    }

    @Test
    void knownStringOperationsUseTheSameActualLengthBudgetInBothEngines() {
        var repeat = JavaIdentifierFactory.getInstance().getMethodSignature("java.lang.String", "repeat", "java.lang.String", List.of("int"));
        var text = literal("ab", "java.lang.String");
        var repeated = new Node(text.variable(), Kind.EXPRESSION, "repeat twice", List.of(text, literal("2", "int")),
                new Computation(Operation.CALL, "repeat", Optional.of(repeat)));
        for (var expression : List.of(
                node(Operation.CONCAT, "concat", "java.lang.String", text, text),
                node(Operation.REPLACE, "replace", "java.lang.String", literal("a", "java.lang.String"),
                        literal("", "java.lang.String"), literal("bb", "java.lang.String")), repeated)) {
            var condition = new JointSqlInferenceResult.Condition(trace(node(Operation.BINARY, "!=", "boolean",
                    expression, node(Operation.NULL, "null", "null"))), true);
            var limited = new ParameterValueInferencer((type, name) -> new SqlValue.Unknown("No external input"), () -> {},
                    ReplayBudgets.defaults().withLength(2)).infer(trace(expression));
            assertTrue(limited.truncated());
            assertInstanceOf(SqlValue.Unknown.class, limited.possibleValues().getFirst().value());
            assertEquals(JointSqlInferenceResult.Status.UNKNOWN,
                    new PathPredicateSolver(() -> {}, 2048, 256, 2).solve(List.of(condition)).status());

            int exactLength = ((String) known(expression)).length();
            var exact = new ParameterValueInferencer((type, name) -> new SqlValue.Unknown("No external input"), () -> {},
                    ReplayBudgets.defaults().withLength(exactLength)).infer(trace(expression));
            assertTrue(exact.complete());
            assertEquals(JointSqlInferenceResult.Status.SATISFIABLE,
                    new PathPredicateSolver(() -> {}, 2048, 256, exactLength).solve(List.of(condition)).status());
        }
    }

    @Test
    void sharedExpressionGraphsAreReplayedWithoutRecursivelyHashingTheirWholeSubtrees() {
        Node expression = literal("1", "int");
        for (int i = 0; i < 27; i++) expression = node(Operation.BINARY, "+", "int", expression, expression);
        var shared = trace(expression);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var result = new ParameterValueInferencer((type, name) -> new SqlValue.Unknown("No external input")).infer(shared);
            assertTrue(result.complete());
            assertEquals(1, result.possibleValues().size());
            assertEquals(1 << 27, assertInstanceOf(SqlValue.Known.class, result.possibleValues().getFirst().value()).value());
        });
    }

    private static Node literal(String text, String type) { return node(Operation.LITERAL, text, type); }
    private static Node node(Operation operation, String text, String type, Node... inputs) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.Sample", "value", type, List.of());
        var variable = new Variable(method, 0, operation.name() + ":" + text, type);
        return new Node(variable, Kind.EXPRESSION, text, List.of(inputs), new Computation(operation, text));
    }
    private static ValueTrace trace(Node node) { return new ValueTrace(node.variable(), List.of(), node); }
    private static SqlValue replay(Node node) {
        var inference = new ParameterValueInferencer((type, name) -> new SqlValue.Unknown("No external input")).infer(trace(node));
        assertEquals(1, inference.possibleValues().size(), inference.toString());
        return inference.possibleValues().getFirst().value();
    }
    private static Object known(Node node) { return assertInstanceOf(SqlValue.Known.class, replay(node)).value(); }
    private static void assertUnknown(Node node) { assertInstanceOf(SqlValue.Unknown.class, replay(node)); }
}
