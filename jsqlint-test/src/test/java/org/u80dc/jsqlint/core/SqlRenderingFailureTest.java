package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.resource.AnalysisResourceSource;
import org.u80dc.jsqlint.core.spi.*;
import java.net.URI;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class SqlRenderingFailureTest {
    @Test void requiredRenderingFailureMakesTheRunPartialAndKeepsOtherDefinitions() {
        var cycle = new LinkedHashMap<String, Object>(); cycle.put("self", cycle);
        var result = analyze(List.of(seed("bad", cycle), seed("good", Map.of("id", 7))));
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        assertEquals(2, result.definitions().size());
        var failed = result.definitions().getFirst();
        assertTrue(failed.bound().isEmpty());
        assertEquals(new SqlAssessment(true, true, false, Optional.empty()), failed.assessment());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("SQL_RENDERING") && diagnostic.incomplete()));
        assertEquals(new SqlValue.Known(7), result.definitions().getLast().samples().getFirst().bindings().getFirst().value());
    }

    @Test void anUnknownBindingDoesNotBecomeAnExecutionFailure() {
        var result = analyze(List.of(seed("unknown", Map.of())));
        assertEquals(AnalysisResult.Status.SUCCESS, result.status());
        assertEquals("SELECT ?", result.definitions().getFirst().bound().orElseThrow().rendered().sql().orElseThrow());
        assertInstanceOf(SqlValue.Unknown.class, result.samples().getFirst().bindings().getFirst().value());
        assertTrue(result.diagnostics().stream().noneMatch(AnalysisDiagnostic::incomplete));
    }

    private static AnalysisSeed seed(String id, Map<String, ?> parameters) {
        var location = new SourceLocation(URI.create("memory:/" + id), id);
        var definition = new SqlDefinition(id, location, SqlNodeBuilder.templateText("SELECT #{id}"));
        return new AnalysisSeed() {
            public SourceLocation location() { return location; }
            public SqlInput input() { return new SqlInput.Definition(definition); }
            public Map<String, ?> parameters() { return parameters; }
        };
    }
    private static AnalysisResult analyze(List<AnalysisSeed> seeds) {
        var module = new SqlAnalysisModule() {
            public String id() { return "render-failure"; }
            public void register(DiscoveryRegistry registry) { registry.afterDiscovery(output -> seeds.forEach(output)); }
        };
        AnalysisResourceSource resources = Stream::empty;
        return new Analyzer(List.of(module), List.of()).analyze(new AnalysisRequest(List.of(), List.of(resources)));
    }
}
