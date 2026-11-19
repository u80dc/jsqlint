package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.JointBudgets;
import org.u80dc.jsqlint.core.node.SqlValue;
import sootup.java.core.JavaIdentifierFactory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.*;

class PathPredicateSolverGraphTest {
    private static final JavaIdentifierFactory FACTORY = JavaIdentifierFactory.getInstance();

    @Test
    void wrapperReferenceEqualityDoesNotBecomeNumericEquality() {
        for (var type : List.of("java.lang.Integer", "java.lang.Long", "java.lang.Character", "java.lang.Boolean")) {
            String primitive = switch (type) {
                case "java.lang.Integer" -> "int"; case "java.lang.Long" -> "long";
                case "java.lang.Character" -> "char"; default -> "boolean";
            };
            String text = primitive.equals("boolean") ? "true" : "1000";
            var method = FACTORY.getMethodSignature(type, "valueOf", type, List.of(primitive));
            var computation = new Computation(Operation.CALL, "valueOf", Optional.of(method));
            Node first = node(1, type, Kind.EXPRESSION, computation, literal(text, primitive));
            Node second = node(2, type, Kind.EXPRESSION, computation, literal(text, primitive));
            Node equal = binary("==", first, second);
            for (boolean expected : List.of(true, false)) {
                var conditions = List.of(condition(equal, expected));
                var solver = new PathPredicateSolver(() -> {});
                assertEquals(Status.UNKNOWN, solver.solve(conditions).status(), type);
                assertFalse(solver.contradicts(conditions), type);
            }
        }
    }

    @Test
    void primitiveEqualityAndNullChecksRemainSupported() {
        var solver = new PathPredicateSolver(() -> {});
        Node equal = binary("==", literal("1000", "int"), literal("1000", "int"));
        assertEquals(Status.SATISFIABLE, solver.solve(List.of(condition(equal, true))).status());
        assertEquals(Status.UNSATISFIABLE, solver.solve(List.of(condition(equal, false))).status());
        Node absent = node(0, "null", Kind.CONSTANT, new Computation(Operation.NULL, "null"));
        Node nullable = symbol("java.lang.Integer");
        assertEquals(Status.SATISFIABLE, solver.solve(List.of(condition(binary("==", nullable, absent), true))).status());
        assertEquals(Status.SATISFIABLE, solver.solve(List.of(condition(binary("!=", literal("x", "java.lang.String"), absent), true))).status());
    }

    @Test
    void unknownReferenceIdentityIsNotReusedForStructuralContradictions() {
        var method = FACTORY.getMethodSignature("java.lang.Integer", "valueOf", "java.lang.Integer", List.of("int"));
        var computation = new Computation(Operation.CALL, "valueOf", Optional.of(method));
        Node first = binary("==", node(1, "java.lang.Integer", Kind.EXPRESSION, computation, literal("1000", "int")),
                node(2, "java.lang.Integer", Kind.EXPRESSION, computation, literal("1000", "int")));
        Node second = binary("==", node(3, "java.lang.Integer", Kind.EXPRESSION, computation, literal("1000", "int")),
                node(4, "java.lang.Integer", Kind.EXPRESSION, computation, literal("1000", "int")));
        var solver = new PathPredicateSolver(() -> {});
        for (var conditions : List.of(List.of(condition(first, true), condition(second, false)),
                List.of(condition(first, true), condition(first, false)))) {
            assertFalse(solver.contradicts(conditions));
            assertEquals(Status.UNKNOWN, solver.solve(conditions).status());
        }
    }

    @Test
    void sameReferenceSymbolIsKnownWithoutBypassingThrowingOperations() {
        var solver = new PathPredicateSolver(() -> {});
        Node first = symbol("java.lang.Object"), copiedSymbol = symbol("java.lang.Object");
        Node equal = binary("==", first, copiedSymbol);
        assertEquals(Status.SATISFIABLE, solver.solve(List.of(condition(equal, true))).status());
        assertEquals(Status.UNSATISFIABLE, solver.solve(List.of(condition(equal, false))).status());

        Node required = node(3, "java.lang.Object", Kind.EXPRESSION,
                new Computation(Operation.REQUIRE_NON_NULL, "guarded reference"), first);
        assertEquals(Status.UNKNOWN, solver.solve(List.of(condition(binary("==", required, required), true))).status(),
                "The only available Object model is null and cannot pass requireNonNull");
    }

    @Test
    void independentlyBuiltEquivalentExpressionsStillProveContradictions() {
        Node first = arithmeticCondition(), second = arithmeticCondition();
        assertNotSame(first, second);
        var conditions = List.of(condition(first, true), condition(second, false));
        var solver = new PathPredicateSolver(() -> {});
        assertTrue(solver.contradicts(conditions));
        assertEquals(Status.UNSATISFIABLE, solver.solve(conditions).status());
    }

    @Test
    void mutableMapQueriesAtDifferentCallsDoNotShareLogicalIdentity() {
        var method = FACTORY.getMethodSignature("java.util.Map", "containsKey", "boolean", List.of("java.lang.Object"));
        var computation = new Computation(Operation.CALL, "containsKey", Optional.of(method));
        Node first = node(1, "boolean", Kind.EXPRESSION, computation, symbol("java.util.Map"), literal("id", "java.lang.String"));
        Node second = node(2, "boolean", Kind.EXPRESSION, computation, symbol("java.util.Map"), literal("id", "java.lang.String"));
        var conditions = List.of(condition(first, true), condition(second, false));
        var solver = new PathPredicateSolver(() -> {});
        assertFalse(solver.contradicts(conditions), "The Map can change between the two calls");
        assertEquals(Status.UNKNOWN, solver.solve(conditions).status());
    }

    @Test
    void sharedGraphsAreConvertedHashedAndEvaluatedWithinLinearWork() {
        Node expression = symbol("int");
        for (int i = 0; i < 27; i++) expression = node(i + 20, "int", Kind.EXPRESSION,
                new Computation(Operation.BINARY, "+"), expression, expression);
        Node equal = binary("==", expression, literal(Integer.toString(1 << 27), "int"));
        AtomicInteger checkpoints = new AtomicInteger();
        var solver = new PathPredicateSolver(checkpoints::incrementAndGet, 2048, 256, 1_000_000, 2000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var result = solver.solve(List.of(condition(equal, true)));
            assertEquals(Status.SATISFIABLE, result.status(), result.toString());
            assertEquals(1, assertInstanceOf(SqlValue.Known.class, result.inputs().get(symbol("int").variable())).value());
            assertTrue(checkpoints.get() < 2000, "Shared subtrees must not be expanded repeatedly");
        });
    }

    @Test
    void preprocessingAndDepthLimitsReturnUnknownAndNeverPrune() {
        Node condition = arithmeticCondition();
        var conditions = List.of(condition(condition, true), condition(arithmeticCondition(), false));
        var limited = new PathPredicateSolver(() -> {}, 2048, 256, 1_000_000, 1);
        assertEquals(Status.UNKNOWN, limited.solve(conditions).status());
        assertTrue(limited.solve(conditions).issues().getFirst().contains("preparation limit"));
        assertFalse(limited.contradicts(conditions));

        Node deep = literal("true", "boolean");
        for (int i = 0; i < 50; i++) deep = node(i, "boolean", Kind.EXPRESSION,
                new Computation(Operation.IDENTITY, "forwarded value"), deep);
        var shallow = new PathPredicateSolver(() -> {}, 2048, 2, 1_000_000, 2000);
        assertEquals(Status.UNKNOWN, shallow.solve(List.of(condition(deep, true))).status());
        assertFalse(shallow.contradicts(List.of(condition(deep, false))));
    }

    @Test
    void permittedDeepGraphsDoNotUseTheJavaCallStack() {
        Node value = literal("true", "boolean");
        for (int i = 0; i < 5000; i++) value = node(i, "boolean", Kind.EXPRESSION,
                new Computation(Operation.IDENTITY, "forwarded value"), value);
        Node deep = value;
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(Status.SATISFIABLE,
                new PathPredicateSolver(() -> {}, 2048, 5000, 1_000_000, 16_000)
                        .solve(List.of(condition(deep, true))).status()));
    }

    @Test
    void cancellationEscapesBothSolverEntrypoints() {
        RuntimeException cancelled = new IllegalStateException("cancelled");
        var solver = new PathPredicateSolver(() -> { throw cancelled; });
        var conditions = List.of(condition(arithmeticCondition(), true));
        assertSame(cancelled, assertThrows(IllegalStateException.class, () -> solver.solve(conditions)));
        assertSame(cancelled, assertThrows(IllegalStateException.class, () -> solver.contradicts(conditions)));
    }

    @Test
    void predicateBudgetSurvivesAllOtherBudgetUpdatesAndLegacyConstruction() {
        var changed = JointBudgets.defaults().withPredicateSteps(31).withCombinations(1).withAttempts(2)
                .withPrefixChecks(3).withFramePaths(4).withFrameSteps(5).withHistorySteps(6).withModelSteps(7)
                .withDimensions(8).withDependencyDepth(9).withControlFlowDepth(10);
        assertEquals(31, changed.predicateSteps());
        assertEquals(20_000, new JointBudgets(1, 2, 3, 4, 5, 6, 7).predicateSteps());
        assertEquals(20_000, new JointBudgets(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).predicateSteps());
        assertThrows(IllegalArgumentException.class, () -> changed.withPredicateSteps(0));
    }

    private static Node arithmeticCondition() {
        Node n = symbol("int");
        Node doubled = node(10, "int", Kind.EXPRESSION, new Computation(Operation.BINARY, "*"), n, literal("2", "int"));
        Node shifted = node(11, "int", Kind.EXPRESSION, new Computation(Operation.BINARY, "+"), doubled, literal("1", "int"));
        return binary(">", shifted, literal("0", "int"));
    }
    private static Node binary(String operator, Node left, Node right) {
        return node(12, "boolean", Kind.EXPRESSION, new Computation(Operation.BINARY, operator), left, right);
    }
    private static Node symbol(String type) { return node(0, type, Kind.NO_CALLER, new Computation(Operation.OPAQUE, "input")); }
    private static Node literal(String value, String type) { return node(0, type, Kind.CONSTANT, new Computation(Operation.LITERAL, value)); }
    private static Node node(int index, String type, Kind kind, Computation computation, Node... inputs) {
        var method = FACTORY.getMethodSignature("example.PredicateGraph", "query", "void", List.of("int"));
        return new Node(new Variable(method, index, kind == Kind.NO_CALLER ? "@parameter0" : "value" + index, type),
                kind, computation.text(), List.of(inputs), computation);
    }
    private static Condition condition(Node node, boolean expected) {
        return new Condition(new org.u80dc.jsqlint.core.dataflow.ValueTrace(node.variable(), List.of(), node), expected);
    }
}
