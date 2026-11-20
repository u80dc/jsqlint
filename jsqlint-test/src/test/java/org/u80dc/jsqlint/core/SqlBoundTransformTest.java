package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SqlBoundTransformTest {
    @Test void transformationRetainsReplayEvidenceAndSurvivesDefinitionAdaptation() {
        var calls=new AtomicInteger();
        var definition=new SqlDefinition("query",new SourceLocation(URI.create("memory:/query"),"query"),SqlNodeBuilder.templateText("SELECT #{id}"));
        var transformed=definition.withTransform((input,metadata,context,origins)->{
            calls.incrementAndGet();
            var before=input.rendered();
            var steps=new ArrayList<SqlRewriteStep>();
            steps.add(new SqlRewriteStep("suffix",before.sql(),Optional.of("SELECT ? LIMIT 1"),input.bindings(),input.bindings(),List.of()));
            var result=new SqlNodeRenderer.BoundSql(new SqlNodeRenderer.RenderedSql(Optional.of("SELECT ? LIMIT 1"),before.residual(),before.parameters(),before.diagnostics(),before.recoveryMode()),input.bindings(),steps);
            steps.clear();
            return result;
        }).withMetadata(Map.of("role","main")).withReplayBudgets(ReplayBudgets.defaults());
        var bound=transformed.render(Map.of("id",7));
        assertEquals(1,calls.get());
        assertEquals(definition.template(),bound.rendered().residual());
        assertEquals(new SqlValue.Known(7),bound.bindings().getFirst().value());
        assertEquals(Optional.of("SELECT ?"),bound.rewrites().getFirst().beforeSql());
        assertThrows(UnsupportedOperationException.class,()->bound.rewrites().clear());
        var sample=new SqlTemplateResult("test",definition.location(),definition.template(),Optional.empty(),List.of(),Optional.of(transformed),Optional.of(bound)).samples().getFirst();
        assertEquals(bound.rewrites(),sample.rewrites());
    }
    @Test void unresolvedRenderingSkipsTransformationAndForeignReplayTreeIsRejected() {
        var definition=new SqlDefinition("query",new SourceLocation(URI.create("memory:/query"),"query"),SqlNodeBuilder.templateText("SELECT ${table}"));
        assertTrue(definition.withTransform((input,metadata,context,origins)->{fail("Unresolved SQL must not reach a plugin");return input;}).render(Map.of()).rendered().sql().isEmpty());
        var known=new SqlDefinition("known",definition.location(),SqlNodeBuilder.text("SELECT 1"));
        assertThrows(IllegalArgumentException.class,()->known.withTransform((input,metadata,context,origins)->SqlNodeRenderer.renderBound(SqlNodeBuilder.text("SELECT 2"),Map.of(),SqlExpressionEvaluator.simple())).render(Map.of()));
    }
}
