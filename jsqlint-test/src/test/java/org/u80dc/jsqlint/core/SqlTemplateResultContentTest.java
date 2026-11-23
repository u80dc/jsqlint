package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.SourceLocation;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlTemplateResultContentTest {
    private static final SourceLocation DEFINITION = new SourceLocation(URI.create("memory:/mapper.xml"), "query");
    private static final SourceLocation ORIGIN = new SourceLocation(URI.create("java:example.Mapper"), "query");

    @Test void definitionHasOneTemplateAndOneBoundSnapshotWithoutKeepingLiteralPreview() {
        var evaluations = new AtomicInteger();
        SqlExpressionEvaluator evaluator = (expression, parameters) -> {
            evaluations.incrementAndGet();
            return SqlExpressionEvaluator.simple().evaluate(expression, parameters);
        };
        var definition = new SqlDefinition("query", DEFINITION, SqlNodeBuilder.templateText("SELECT #{id}"), evaluator, Map.of(), List.of());
        var preview = SqlNodeRenderer.render(definition.template(), Map.of("id", 7), evaluator);
        var bound = definition.render(Map.of("id", 7));
        var source = new SqlTemplateResult("test", ORIGIN, definition.template(), Optional.of(preview),
                List.of(), Optional.of(definition), Optional.of(bound));
        assertInstanceOf(SqlTemplateResult.Content.Definition.class, source.content());
        assertSame(definition.template(), source.template());
        assertSame(bound.rendered(), source.rendered().orElseThrow());
        assertEquals("SELECT 7", preview.sql().orElseThrow());
        assertEquals("SELECT ?", source.rendered().orElseThrow().sql().orElseThrow());
        assertEquals(ORIGIN, source.location());
        assertEquals(DEFINITION, source.definition().orElseThrow().location());
        int before = evaluations.get();
        assertEquals(new SqlValue.Known(7), source.samples().getFirst().bindings().getFirst().value());
        assertEquals("SELECT ?", source.rawSqls().getFirst().text());
        assertEquals(before, evaluations.get(), "Reading saved models must not evaluate expressions");
    }

    @Test void missingBoundSnapshotIsNotReplacedByAnOldPreview() {
        var definition = new SqlDefinition("query", DEFINITION, SqlNodeBuilder.templateText("SELECT #{id}"));
        var preview = SqlNodeRenderer.render(definition.template(), Map.of("id", 7));
        var source = new SqlTemplateResult("test", ORIGIN, definition.template(), Optional.of(preview), List.of(), Optional.of(definition));
        assertTrue(source.rendered().isEmpty());
        assertTrue(source.samples().getFirst().sql().isEmpty());
        assertTrue(source.rawSqls().isEmpty());
        assertEquals(new SqlValue.Known(9), source.sample(Map.of("id", 9)).bindings().getFirst().value());
        assertTrue(source.bound().isEmpty(), "Explicit samples must not populate the saved result");
    }

    @Test void bareTemplateRetainsItsOwnRenderingAndLocalDiagnostics() {
        var template = SqlNodeBuilder.text("SELECT 1");
        var rendered = SqlNodeRenderer.render(template, Map.of());
        var diagnostic = new AnalysisDiagnostic("NOTE", AnalysisListener.Stage.ANALYZING, Optional.of(ORIGIN), false, "bare template");
        var source = new SqlTemplateResult("test", ORIGIN, new SqlTemplateResult.Content.Template(template, Optional.of(rendered)), List.of(diagnostic));
        assertSame(template, source.template());
        assertSame(rendered, source.rendered().orElseThrow());
        assertTrue(source.definition().isEmpty());
        assertTrue(source.bound().isEmpty());
        assertEquals(List.of(diagnostic), source.diagnostics());
        assertEquals("SELECT 1", source.samples().getFirst().sql().orElseThrow());
    }

    @Test void legacyAdaptersRejectConflictingTreesAndBoundSnapshotsWithoutDefinitions() {
        var definition = new SqlDefinition("query", DEFINITION, SqlNodeBuilder.text("SELECT 1"));
        var different = SqlNodeBuilder.text("SELECT 2");
        var bound = definition.render(Map.of());
        assertThrows(IllegalArgumentException.class, () -> new SqlTemplateResult("test", ORIGIN, different,
                Optional.empty(), List.of(), Optional.of(definition), Optional.of(bound)));
        assertThrows(IllegalArgumentException.class, () -> new SqlTemplateResult("test", ORIGIN, definition.template(),
                Optional.empty(), List.of(), Optional.empty(), Optional.of(bound)));
        var unrelated = SqlNodeRenderer.renderBound(different, Map.of(), SqlExpressionEvaluator.simple());
        assertThrows(IllegalArgumentException.class, () -> new SqlTemplateResult.Content.Definition(definition, Optional.of(unrelated)));
    }
}
