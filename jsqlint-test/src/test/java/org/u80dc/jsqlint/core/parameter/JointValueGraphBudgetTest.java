package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.JointBudgets;
import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.core.JavaIdentifierFactory;
import sootup.java.core.views.JavaView;
import java.nio.file.*;
import javax.tools.ToolProvider;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

class JointValueGraphBudgetTest {
    @TempDir Path temporary;
    @Test void pathSelectionRetainsSharingInsteadOfExpandingEveryPath() {
        Node root = shared(30);
        AtomicInteger checkpoints = new AtomicInteger();
        JointPathExplorer explorer = explorer(checkpoints::incrementAndGet, 1000, 256);
        ValueTrace result = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> explorer.select(trace(root), emptySelection()));
        assertTrue(result.complete());
        Node selected = result.root();
        for (int i = 0; i < 30; i++) {
            assertSame(selected.inputs().get(0), selected.inputs().get(1)); selected = selected.inputs().getFirst();
        }
        assertTrue(checkpoints.get() < 250, "Only unique nodes and actual input edges may consume work");
    }

    @Test void pathSelectionOfPermittedDeepGraphsDoesNotUseTheCallStack() {
        Node root = chain(6000);
        ValueTrace selected = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> explorer(() -> {}, 25_000, 6000).select(trace(root), emptySelection()));
        assertTrue(selected.complete());
        assertEquals(root, selected.root());
    }

    @Test void pathSelectionLimitsPreserveOriginalEvidenceAsUnknown() {
        Node root = shared(30);
        for (JointPathExplorer explorer : List.of(explorer(() -> {}, 5, 256), explorer(() -> {}, 1000, 2))) {
            ValueTrace selected = explorer.select(trace(root), emptySelection());
            assertEquals(Kind.LIMIT, selected.root().kind());
            assertFalse(selected.complete());
            assertSame(root, selected.root().inputs().getFirst(), "The source must remain available for audit/recovery");
            var replay = new ParameterValueInferencer().infer(selected);
            assertFalse(replay.complete());
            assertTrue(replay.possibleValues().stream().allMatch(value -> value.value() instanceof org.u80dc.jsqlint.core.node.SqlValue.Unknown));
        }
    }

    @Test void independentEquivalentGraphsHaveShallowKeysAndPublicValueSemantics() {
        Node first = shared(30), second = shared(30);
        AtomicInteger checkpoints = new AtomicInteger();
        var keys = new ValueTraceGraphs.Keys(1000, 256, checkpoints::incrementAndGet);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(keys.key(first), keys.key(second));
            assertEquals(first, second);
            assertEquals(first.hashCode(), second.hashCode());
            assertEquals(trace(first), trace(second));
        });
        assertTrue(checkpoints.get() < 500);
        Node different = node(99, "different", first);
        assertNotEquals(keys.key(first), keys.key(different));
    }

    @Test void keysRejectWorkAndDepthLimitsWithoutRecursiveHashing() {
        Node deep = chain(6000);
        assertThrows(ValueTraceGraphs.Limit.class, () -> new ValueTraceGraphs.Keys(5, 6000, () -> {}).key(deep));
        assertThrows(ValueTraceGraphs.Limit.class, () -> new ValueTraceGraphs.Keys(25_000, 2, () -> {}).key(deep));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(deep, chain(6000));
            assertEquals(deep.hashCode(), chain(6000).hashCode());
        });
    }

    @Test void cachedSharedSubtreesCannotBypassTheDepthOfAnotherPath() {
        Node shared = chain(3);
        Node deeper = node(20, "deeper", node(21, "deeper", shared));
        Node root = node(30, "root", shared, deeper);
        assertThrows(ValueTraceGraphs.Limit.class, () -> new ValueTraceGraphs.Keys(1000, 4, () -> {}).key(root));
        assertThrows(ValueTraceGraphs.Limit.class, () -> ValueTraceGraphs.transform(root, 1000, 4, () -> {}, (node, work) -> node));
    }

    @Test void cancellationPropagatesFromGraphKeysAndPathSelection() {
        IllegalStateException cancellation = new IllegalStateException("cancelled");
        Runnable checkpoint = () -> { throw cancellation; };
        assertSame(cancellation, assertThrows(IllegalStateException.class,
                () -> new ValueTraceGraphs.Keys(1000, 256, checkpoint).key(shared(30))));
        assertSame(cancellation, assertThrows(IllegalStateException.class,
                () -> explorer(checkpoint, 1000, 256).select(trace(shared(30)), emptySelection())));
    }

    @Test void wholeJointAnalysisKeepsOneEquivalentSourceCombination() throws Exception {
        try (Fixture fixture = fixture()) {
            Node first = fixtureChain(fixture, 200), second = fixtureChain(fixture, 200);
            var values = List.of(inferred(first), inferred(second));
            var parameter = new ParameterValueInferenceResult("value", "int", values, List.of());
            var budgets = JointBudgets.defaults().withFrameSteps(1000).withDependencyDepth(200).withPredicateSteps(1000);
            var inference = new JointSqlValueInferencer(fixture.flow(), budgets, () -> (type, name) -> new SqlValue.Known(1),
                    ReplayBudgets.defaults().withDepth(200).withSteps(1000));
            var result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> inference.inferParameters(List.of(parameter), fixture.point(), List.of(), List.of()));
            assertEquals(JointSqlInferenceResult.Status.SATISFIABLE, result.status(), result.issues().toString());
            assertTrue(result.coverageComplete());
            assertEquals(1, result.combinations().size(), "Equivalent source evidence must not create duplicate models");
        }
    }

    @Test void wholeJointGraphLimitRetainsUnknownCandidateInsteadOfReportingUnsat() throws Exception {
        try (Fixture fixture = fixture()) {
            var parameter = new ParameterValueInferenceResult("value", "int", List.of(inferred(fixtureChain(fixture, 30))), List.of());
            var inference = new JointSqlValueInferencer(fixture.flow(), JointBudgets.defaults().withPredicateSteps(1),
                    () -> (type, name) -> new SqlValue.Known(1));
            var result = inference.inferParameters(List.of(parameter), fixture.point(), List.of(), List.of());
            assertEquals(JointSqlInferenceResult.Status.UNKNOWN, result.status());
            assertFalse(result.coverageComplete());
            assertFalse(result.combinations().isEmpty(), "Comparison capacity cannot erase an available source combination");
            assertEquals(new SqlValue.Known(1), result.combinations().getFirst().parameters().get("value").value());
        }
    }

    private Fixture fixture() throws Exception {
        Path root = Files.createTempDirectory(temporary, "graph-");
        Path source = Files.writeString(root.resolve("Sample.java"), "public class Sample { public static int run(int value) { return value; } }");
        Path classes = Files.createDirectory(root.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        var input = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
        var view = new JavaView(List.of(input, new DefaultRuntimeAnalysisInputLocation()));
        var application = input.getClassSources(view).map(value -> view.getClass(value.getClassType()).orElseThrow()).toList();
        var calls = new ArrayList<InvocationSite>();
        for (var clazz : application) for (var method : clazz.getMethods()) if (method.hasBody()) {
            var statements = method.getBody().getStmts();
            for (int i = 0; i < statements.size(); i++) if (statements.get(i).isInvokableStmt()) {
                var invocation = statements.get(i).asInvokableStmt().getInvokeExpr();
                if (invocation.isPresent()) calls.add(new InvocationSite(method, statements.get(i), i, invocation.get(), view));
            }
        }
        var index = new ProgramIndex(view, application, calls, () -> {});
        var flow = new DataFlowAnalysis(index, 32, 6000, 30_000, 128, () -> {}, (code, message) -> {});
        var method = application.stream().flatMap(clazz -> clazz.getMethods().stream()).filter(value -> value.getName().equals("run")).findFirst().orElseThrow();
        return new Fixture(input, flow, new FlowPoint(method, method.getBody().getStmts().getLast(), CallContext.EMPTY));
    }
    private record Fixture(JavaClassPathAnalysisInputLocation input, DataFlowAnalysis flow, FlowPoint point) implements AutoCloseable {
        @Override public void close() throws Exception { input.close(); }
    }
    private static Node fixtureChain(Fixture fixture, int depth) {
        var method = fixture.point().method().getSignature();
        Node node = new Node(new Variable(method, -1, "@parameter0", "int"), Kind.NO_CALLER, "input", List.of());
        for (int i = 1; i <= depth; i++) node = new Node(new Variable(method, -1, "v" + i, "int"), Kind.EXPRESSION,
                "identity", List.of(node), new Computation(Operation.IDENTITY, ""));
        return node;
    }
    private static InferredParameterValue inferred(Node node) {
        return new InferredParameterValue(new SqlValue.Known(1), trace(node), List.of(), List.of());
    }

    private static JointPathExplorer explorer(Runnable checkpoint, int work, int depth) {
        var view = new JavaView(List.of());
        var index = new ProgramIndex(view, List.of(), List.of(), checkpoint);
        var flow = new DataFlowAnalysis(index, 32, depth, 30_000, 128, checkpoint, (code, message) -> {});
        return new JointPathExplorer(flow, 64, work, 2048, depth, 512);
    }
    private static JointPathExplorer.Selection emptySelection() {
        return new JointPathExplorer.Selection(Map.of(), List.of(), Map.of());
    }
    private static ValueTrace trace(Node root) { return new ValueTrace(root.variable(), List.of(), root); }
    private static Node shared(int depth) {
        Node result = node(0, "input");
        for (int i = 1; i <= depth; i++) result = node(i, "binary", result, result);
        return result;
    }
    private static Node chain(int depth) {
        Node result = node(0, "input");
        for (int i = 1; i <= depth; i++) result = node(i, "identity", result);
        return result;
    }
    private static Node node(int position, String operation, Node... inputs) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.Graph", "query", "void", List.of("int"));
        var variable = new Variable(method, -1, "v" + position, "int");
        return new Node(variable, inputs.length == 0 ? Kind.NO_CALLER : Kind.EXPRESSION, operation, List.of(inputs),
                new Computation(inputs.length == 0 ? Operation.OPAQUE : inputs.length == 1 ? Operation.IDENTITY : Operation.BINARY,
                        inputs.length > 1 ? "+" : ""));
    }
}
