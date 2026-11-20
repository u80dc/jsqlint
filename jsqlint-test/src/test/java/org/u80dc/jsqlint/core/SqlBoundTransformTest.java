package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SqlBoundTransformTest {
    private static SqlDefinition definition(){return new SqlDefinition("query",new SourceLocation(URI.create("memory:/query"),"query"),SqlNodeBuilder.templateText("SELECT #{id}"));}
    private static SqlNodeRenderer.BoundSql result(SqlNodeRenderer.BoundSql input,String sql,List<SqlNodeRenderer.BoundParameter> bindings) {
        var rendered=input.rendered();
        return new SqlNodeRenderer.BoundSql(new SqlNodeRenderer.RenderedSql(Optional.of(sql),rendered.residual(),rendered.parameters(),rendered.diagnostics(),rendered.recoveryMode()),bindings,
                List.of(new SqlRewriteStep("test",rendered.sql(),Optional.of(sql),input.bindings(),bindings,List.of())));
    }
    @Test void missingOrChangedInputSnapshotsAreRejectedButCopiedArraysRemainValid() {
        for(Map<String,Object> replacement:List.of(Map.<String,Object>of(),Map.<String,Object>of("id",9))) {
            assertThrows(IllegalArgumentException.class,()->definition().withTransform((input,metadata,context,origins)->{
                var before=input.rendered();return new SqlNodeRenderer.BoundSql(new SqlNodeRenderer.RenderedSql(before.sql(),before.residual(),replacement,before.diagnostics(),before.recoveryMode()),input.bindings());
            }).render(Map.of("id",7)));
        }
        assertDoesNotThrow(()->definition().withTransform((input,metadata,context,origins)->result(input,"SELECT ? LIMIT 1",input.bindings())).render(Map.of("id",new int[]{1,2})));
    }
    @Test void previousDiagnosticsAndJournalCannotBeRemoved() {
        var base=definition().render(Map.of("id",7));var rendered=base.rendered();var diagnostic=new SqlDiagnostic("INPUT","Existing diagnostic");
        var input=new SqlNodeRenderer.BoundSql(new SqlNodeRenderer.RenderedSql(rendered.sql(),rendered.residual(),rendered.parameters(),List.of(diagnostic),rendered.recoveryMode()),base.bindings());
        assertThrows(IllegalArgumentException.class,()->SqlBoundTransformResults.validate(input,base,SqlEvaluationContext.defaults(()->{},ignored->{})));
        var rewritten=result(base,"SELECT ? LIMIT 1",base.bindings());
        var withoutJournal=new SqlNodeRenderer.BoundSql(rewritten.rendered(),rewritten.bindings());
        assertThrows(IllegalArgumentException.class,()->SqlBoundTransformResults.validate(rewritten,withoutJournal,SqlEvaluationContext.defaults(()->{},ignored->{})));
    }
    @Test void changedSqlWithoutEvidenceAndFalseInitialEvidenceAreRejected() {
        assertThrows(IllegalArgumentException.class,()->definition().withTransform((input,metadata,context,origins)->new SqlNodeRenderer.BoundSql(result(input,"SELECT ? LIMIT 1",input.bindings()).rendered(),input.bindings())).render(Map.of("id",7)));
        assertThrows(IllegalArgumentException.class,()->definition().withTransform((input,metadata,context,origins)->{
            var output=result(input,"SELECT ? LIMIT 1",input.bindings());
            return new SqlNodeRenderer.BoundSql(output.rendered(),output.bindings(),List.of(new SqlRewriteStep("false",Optional.of("SELECT 999"),output.rendered().sql(),input.bindings(),output.bindings(),List.of())));
        }).render(Map.of("id",7)));
    }
    @Test void discontinuousStepsAndFinalBindingMismatchAreRejectedAtConstruction() {
        var input=definition().render(Map.of("id",7));var output=result(input,"SELECT ? LIMIT 1",input.bindings());
        var wrong=new SqlRewriteStep("second",Optional.of("SELECT 999"),output.rendered().sql(),input.bindings(),input.bindings(),List.of());
        assertThrows(IllegalArgumentException.class,()->new SqlNodeRenderer.BoundSql(output.rendered(),input.bindings(),List.of(output.rewrites().getFirst(),wrong)));
        var modified=new SqlNodeRenderer.BoundParameter(1,"id","int","",new SqlValue.Known(99));
        assertThrows(IllegalArgumentException.class,()->new SqlNodeRenderer.BoundSql(output.rendered(),List.of(modified),output.rewrites()));
        assertThrows(IllegalArgumentException.class,()->new SqlRewriteStep("failed",input.rendered().sql(),Optional.empty(),input.bindings(),input.bindings(),List.of()));
    }
    @Test void excessiveOutputAndBindingsBecomeLimitedUnknownWithOriginalEvidence() {
        for(boolean text:List.of(true,false)) {
            var limits=new ArrayList<String>();var context=new SqlEvaluationContext(ReplayBudgets.defaults(),new SqlRenderBudgets(10,128,64),()->{},limits::add);
            var definition=definition().withTransform((input,metadata,execution,origins)->{
                if(text)return result(input,"X".repeat(100),input.bindings());
                var bindings=new ArrayList<SqlNodeRenderer.BoundParameter>();for(int index=1;index<=20;index++)bindings.add(new SqlNodeRenderer.BoundParameter(index,"extra","int","",new SqlValue.Known(index)));
                return result(input,"SELECT ?",bindings);
            });
            var output=definition.render(Map.of("id",7),context);
            assertTrue(output.rendered().sql().isEmpty());assertTrue(output.rendered().diagnostics().getLast().limited());assertEquals(1,limits.size());
            assertEquals(new SqlValue.Known(7),output.bindings().getFirst().value());assertEquals(7,output.rendered().parameters().get("id"));assertEquals(definition.template(),output.rendered().residual());
            assertEquals(Optional.of("SELECT ?"),output.rewrites().getLast().beforeSql());assertTrue(output.rewrites().getLast().afterSql().isEmpty());
        }
    }
    @Test void originDepthIsLimitedAndCancellationIsNotConvertedToUnknown() {
        var limited=definition().withTransform((input,metadata,context,origins)->{
            SqlValueOrigin origin=new SqlValueOrigin.Input("id");for(int index=0;index<4;index++)origin=new SqlValueOrigin.Expression("derived",List.of(origin));
            var binding=new SqlNodeRenderer.BoundParameter(1,"id","int","",new SqlValue.Known(7),origin);
            return result(input,"SELECT ?",List.of(binding));
        }).render(Map.of("id",7),new SqlEvaluationContext(ReplayBudgets.defaults(),new SqlRenderBudgets(1000,2,10000),()->{}));
        assertTrue(limited.rendered().sql().isEmpty());assertTrue(limited.rendered().diagnostics().getLast().limited());
        var stopped=new boolean[]{false};var cancelled=new IllegalStateException("cancelled");
        var context=new SqlEvaluationContext(ReplayBudgets.defaults(),()->{if(stopped[0])throw cancelled;});
        var definition=definition().withTransform((input,metadata,execution,origins)->{var output=result(input,"SELECT ? LIMIT 1",input.bindings());stopped[0]=true;return output;});
        assertSame(cancelled,assertThrows(IllegalStateException.class,()->definition.render(Map.of("id",7),context)));
    }
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
