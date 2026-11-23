package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.node.SqlNodeBuilder;
import org.u80dc.jsqlint.core.node.SqlRewriteStep;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult;
import org.u80dc.jsqlint.core.spi.SourceLocation;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqlSampleEvidenceTest {
    @Test void detailedEvidenceSharesFrozenReferencesAndProtectsTheRewriteList() {
        var location = new SourceLocation(URI.create("memory:/query"), "query");
        var definition = new SqlDefinition("query", location, SqlNodeBuilder.text("SELECT 1"));
        var variant = new RawSql.Variant("SELECT 1", List.of());
        var combination = new JointSqlInferenceResult.Combination(JointSqlInferenceResult.Status.SATISFIABLE,
                Map.of(), List.of(), List.of(), Map.of(), List.of());
        var rewrite = new SqlRewriteStep("format", Optional.of("SELECT  1"), Optional.of("SELECT 1"),
                List.of(), List.of(), List.of());
        var rewrites = new ArrayList<>(List.of(rewrite));
        var evidence = new SqlSampleEvidence(Optional.of(variant), Optional.of(combination),
                Optional.of(definition), rewrites);
        rewrites.clear();

        assertSame(variant, evidence.rawSql().orElseThrow());
        assertSame(combination, evidence.combination().orElseThrow());
        assertSame(definition, evidence.definition().orElseThrow());
        assertEquals(List.of(rewrite), evidence.rewrites());
        assertThrows(UnsupportedOperationException.class, () -> evidence.rewrites().clear());
    }

    @Test void sampleAccessorsAndExistingConstructorsUseTheSameEvidence() {
        var location = new SourceLocation(URI.create("memory:/query"), "query");
        var definition = new SqlDefinition("query", location, SqlNodeBuilder.text("SELECT 1"));
        var bound = definition.render(Map.of());
        var source = new SqlTemplateResult("test", location, definition.template(), Optional.empty(),
                List.of(), Optional.of(definition), Optional.of(bound));
        var original = source.samples().getFirst();
        var evidence = new SqlSampleEvidence(original.rawSql(), original.combination(), original.definition(),
                original.rewrites());
        var grouped = new SqlSample(source, original.batchEntry(), original.rendered(), original.bindings(),
                original.feasibility(), original.assessment(), original.issues(), evidence);
        var existing = new SqlSample(source, original.batchEntry(), original.rendered(), original.bindings(),
                original.feasibility(), original.assessment(), original.issues(), original.rawSql(),
                original.combination(), original.definition(), original.rewrites());

        assertEquals(original, grouped);
        assertEquals(grouped, existing);
        assertSame(evidence, grouped.evidence());
        assertSame(definition, grouped.definition().orElseThrow());
        assertEquals(evidence.rawSql(), grouped.rawSql());
        assertEquals(evidence.combination(), grouped.combination());
        assertSame(evidence.rewrites(), grouped.rewrites());
        assertTrue(SqlSampleEvidence.empty().rawSql().isEmpty());
        assertTrue(SqlSampleEvidence.empty().combination().isEmpty());
        assertTrue(SqlSampleEvidence.empty().definition().isEmpty());
        assertTrue(SqlSampleEvidence.empty().rewrites().isEmpty());
    }
}
