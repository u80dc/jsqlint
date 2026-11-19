package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.resource.AnalysisResourceSource;
import org.u80dc.jsqlint.core.spi.*;
import sootup.java.core.JavaIdentifierFactory;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** 冻结来源携带预算，每次重演独立提供检查点且兼容旧表达式扩展。 */
class TraceExpressionExecutionContextTest {
    private static final SourceLocation LOCATION = new SourceLocation(URI.create("memory:/trace-context.sql"), "query");

    @Test void aFrozenEvaluatorKeepsItsReplayPolicy() {
        var trace = identityChain(3, "7", "int");
        var limited = evaluator(trace, ReplayBudgets.defaults().withSteps(1));
        assertInstanceOf(SqlValue.Unknown.class, limited.evaluate("slot", Map.of()));
        assertEquals(new SqlValue.Known(7), evaluator(trace, ReplayBudgets.defaults()).evaluate("slot", Map.of()));
    }

    @Test void explicitContextCanReplaceTheFrozenStepBudget() {
        var evaluator = evaluator(identityChain(3, "7", "int"), ReplayBudgets.defaults());
        var limits = new ArrayList<String>();
        var context = new SqlEvaluationContext(ReplayBudgets.defaults().withSteps(1), () -> {}, limits::add);
        assertInstanceOf(SqlValue.Unknown.class, evaluator.evaluate("slot", Map.of(), context));
        assertTrue(limits.stream().anyMatch(message -> message.contains("replay budget")), limits.toString());
        assertEquals(new SqlValue.Known(7), evaluator.evaluate("slot", Map.of(),
                new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {})));
    }

    @Test void explicitContextLimitsReplayDependencyDepth() {
        var evaluator = evaluator(identityChain(3, "7", "int"), ReplayBudgets.defaults());
        var limits = new ArrayList<String>();
        assertInstanceOf(SqlValue.Unknown.class, evaluator.evaluate("slot", Map.of(),
                new SqlEvaluationContext(ReplayBudgets.defaults().withDepth(1), () -> {}, limits::add)));
        assertFalse(limits.isEmpty());
    }

    @Test void replayLengthLimitReachesBoundRenderingDiagnostics() {
        var evaluator = evaluator(identityChain(0, "abcdef", "java.lang.String"), ReplayBudgets.defaults());
        var limits = new ArrayList<String>();
        var bound = SqlNodeRenderer.renderBound(ValueSqlNode.parameter("slot"), Map.of(), evaluator,
                new SqlEvaluationContext(ReplayBudgets.defaults().withLength(3), () -> {}, limits::add));
        assertEquals(Optional.of("?"), bound.rendered().sql());
        assertInstanceOf(SqlValue.Unknown.class, bound.bindings().getFirst().value());
        assertFalse(bound.valuesComplete());
        assertTrue(bound.rendered().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")));
        assertFalse(limits.isEmpty());
    }

    @Test void legacyRenderingInheritsTheFrozenEvaluatorBudget() {
        var evaluator = evaluator(identityChain(3, "7", "int"), ReplayBudgets.defaults().withSteps(1));
        var definition = new SqlDefinition("query", LOCATION, ValueSqlNode.parameter("slot"), evaluator, Map.of(), List.of());
        var limited = definition.render(Map.of());
        assertInstanceOf(SqlValue.Unknown.class, limited.bindings().getFirst().value());
        assertTrue(limited.rendered().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")));
        var restored = definition.render(Map.of(), new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {}));
        assertEquals(new SqlValue.Known(7), restored.bindings().getFirst().value());
    }

    @Test void cancellationFromDeepReplayEscapesUnchangedAndClearsTheScope() {
        var cancelled = new IllegalStateException("cancel trace replay");
        var checks = new AtomicInteger();
        var evaluator = evaluator(identityChain(50, "7", "int"), ReplayBudgets.defaults());
        var context = new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {
            if (checks.incrementAndGet() >= 12) throw cancelled;
        });
        assertSame(cancelled, assertThrows(IllegalStateException.class, () ->
                SqlNodeRenderer.renderBound(ValueSqlNode.parameter("slot"), Map.of(), evaluator, context)));
        assertTrue(checks.get() >= 12);
        var fallback = ReplayBudgets.defaults().withSteps(17);
        assertEquals(fallback, SqlEvaluationContext.currentOr(fallback).replayBudgets());
        assertEquals(new SqlValue.Known(7), evaluator.evaluate("slot", Map.of()));
    }

    @Test void failedNestedExecutionRestoresAnOuterExecutionContext() {
        var outerBudgets = ReplayBudgets.defaults().withSteps(99);
        var innerBudgets = ReplayBudgets.defaults().withSteps(3);
        var outer = new SqlEvaluationContext(outerBudgets, () -> {});
        var inner = new SqlEvaluationContext(innerBudgets, () -> {});
        var error = new IllegalArgumentException("evaluation failed");
        outer.evaluate(() -> {
            assertSame(error, assertThrows(IllegalArgumentException.class, () -> inner.evaluate(() -> { throw error; })));
            assertEquals(outerBudgets, SqlEvaluationContext.current().replayBudgets());
            return null;
        });
        assertEquals(innerBudgets, SqlEvaluationContext.currentOr(innerBudgets).replayBudgets());
    }

    @Test void contextAwareRenderingPreservesALegacyReferenceOverride() {
        var evaluations = new AtomicInteger();
        SqlExpressionEvaluator evaluator = new SqlExpressionEvaluator() {
            @Override public SqlValue evaluate(String expression, Map<String, ?> parameters) {
                fail("The legacy reference override must be called");
                return new SqlValue.Unknown("unused");
            }
            @Override public Evaluation evaluateWithReferences(String expression, Map<String, ?> parameters, boolean parameter) {
                evaluations.incrementAndGet();
                return new Evaluation(new SqlValue.Known(7), List.of(new Reference("entry", List.of())), true);
            }
        };
        var bound = SqlNodeRenderer.renderBound(ValueSqlNode.parameter("slot"), Map.of("entry", 7), evaluator,
                new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {}));
        assertEquals(1, evaluations.get());
        assertEquals(new SqlValue.Known(7), bound.bindings().getFirst().value());
        assertEquals(new SqlValueOrigin.Input("entry"), bound.bindings().getFirst().origin());
    }

    @Test void analyzerPolicyControlsRenderingAndSurvivesInTheSavedDefinition() {
        var evaluator = evaluator(identityChain(3, "7", "int"), ReplayBudgets.defaults());
        var definition = new SqlDefinition("query", LOCATION, ValueSqlNode.parameter("slot"), evaluator, Map.of(), List.of());
        var module = new SqlAnalysisModule() {
            public String id() { return "trace-context"; }
            public void register(DiscoveryRegistry registry) {
                registry.afterDiscovery(seeds -> seeds.accept(new AnalysisSeed() {
                    public SourceLocation location() { return LOCATION; }
                    public SqlInput input() { return new SqlInput.Definition(definition); }
                }));
            }
        };
        AnalysisResourceSource resources = Stream::empty;
        var config = AnalyzerConfig.defaults().withReplayBudgets(ReplayBudgets.defaults().withSteps(1));
        var result = new Analyzer(config, List.of(module), List.of()).analyze(
                new AnalysisRequest(List.of(), List.of(resources), AnalysisOptions.defaults()));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("SQL_RENDER_LIMIT")));
        var saved = ((SqlTemplateResult) result.sqlResults().getFirst()).definition().orElseThrow();
        var rerendered = saved.render(Map.of());
        assertInstanceOf(SqlValue.Unknown.class, rerendered.bindings().getFirst().value());
        assertTrue(rerendered.rendered().diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RENDER_LIMIT")));
        var expanded = saved.render(Map.of(), new SqlEvaluationContext(ReplayBudgets.defaults(), () -> {}));
        assertEquals(new SqlValue.Known(7), expanded.bindings().getFirst().value());
    }

    private static TraceSqlExpressionEvaluator evaluator(ValueTrace trace, ReplayBudgets budgets) {
        return new TraceSqlExpressionEvaluator(SqlExpressionEvaluator.simple(), Map.of("slot", trace), Map.of(), budgets);
    }

    private static ValueTrace identityChain(int identities, String text, String type) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.TraceContext", "query", "void", List.of());
        var variable = new Variable(method, 0, "constant", type);
        Node node = new Node(variable, Kind.CONSTANT, "literal", List.of(), new Computation(Operation.LITERAL, text));
        for (int i = 0; i < identities; i++) {
            variable = new Variable(method, i + 1, "identity" + i, type);
            node = new Node(variable, Kind.DEFINITION, "identity", List.of(node), new Computation(Operation.IDENTITY, ""));
        }
        return new ValueTrace(variable, List.of(), node);
    }
}
