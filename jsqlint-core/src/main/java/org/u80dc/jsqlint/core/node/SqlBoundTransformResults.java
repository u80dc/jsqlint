package org.u80dc.jsqlint.core.node;

import java.util.*;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer.*;

/** 改写扩展的通用结果边界；不解释 SQL 方言或框架插件。 */
public final class SqlBoundTransformResults {
    private SqlBoundTransformResults() {}
    public static BoundSql validate(BoundSql input,BoundSql output,SqlEvaluationContext context) {
        var before=input.rendered();var after=output.rendered();
        if(!before.residual().equals(after.residual())||before.recoveryMode()!=after.recoveryMode())
            throw new IllegalArgumentException("SQL transformation must preserve the replay template/mode");
        if(!SqlValueSnapshots.sameValue(before.parameters(),after.parameters()))
            throw new IllegalArgumentException("SQL transformation must preserve input snapshots");
        if(!prefix(before.diagnostics(),after.diagnostics()))
            throw new IllegalArgumentException("SQL transformation must preserve existing diagnostics in order");
        if(!prefix(input.rewrites(),output.rewrites()))
            throw new IllegalArgumentException("SQL transformation must preserve existing rewrite evidence");
        int first=input.rewrites().size();
        if(first<output.rewrites().size()) {
            var step=output.rewrites().get(first);
            if(!step.beforeSql().equals(before.sql())||!step.beforeBindings().equals(input.bindings()))
                throw new IllegalArgumentException("Rewrite evidence must start from the transformation input");
        } else if(!before.sql().equals(after.sql())||!input.bindings().equals(output.bindings()))
            throw new IllegalArgumentException("Changed SQL/bindings require rewrite evidence");
        String exceeded=budget(output,context);
        if(exceeded==null)return output;
        context.limit(exceeded);
        var diagnostic=new SqlDiagnostic("TRANSFORM_LIMIT",exceeded,SqlDiagnostic.Effect.LIMIT);
        var diagnostics=new ArrayList<>(before.diagnostics());diagnostics.add(diagnostic);
        var steps=new ArrayList<>(input.rewrites());steps.add(new SqlRewriteStep("transform-limit",before.sql(),Optional.empty(),input.bindings(),input.bindings(),List.of(diagnostic)));
        return new BoundSql(new RenderedSql(Optional.empty(),before.residual(),before.parameters(),diagnostics,before.recoveryMode()),input.bindings(),steps);
    }
    private static boolean prefix(List<?> before,List<?> after){return before.size()<=after.size()&&before.equals(after.subList(0,before.size()));}
    static void validateJournal(RenderedSql rendered,List<BoundParameter> bindings,List<SqlRewriteStep> steps) {
        for(int index=0;index<steps.size();index++) {
            var step=steps.get(index);
            if(!rendered.diagnostics().containsAll(step.diagnostics()))throw new IllegalArgumentException("Rewrite diagnostics must be present in the final result");
            if(index>0) {
                var previous=steps.get(index-1);
                if(!previous.afterSql().equals(step.beforeSql())||!previous.afterBindings().equals(step.beforeBindings()))
                    throw new IllegalArgumentException("Rewrite steps must form a continuous SQL/binding chain");
            }
        }
        if(!steps.isEmpty()) {
            var last=steps.getLast();
            if(!last.afterSql().equals(rendered.sql())||!last.afterBindings().equals(bindings))
                throw new IllegalArgumentException("Final SQL/bindings must match the last rewrite step");
        }
    }
    private static String budget(BoundSql output,SqlEvaluationContext context) {
        var budget=new EvidenceBudget(context);
        try {
            budget.add(0,output.rendered().sql().map(String::length).orElse(0));
            budget.bindings(output.bindings());budget.diagnostics(output.rendered().diagnostics());
            for(var step:output.rewrites()) {
                budget.add(1,(long)step.id().length()+step.beforeSql().map(String::length).orElse(0)+step.afterSql().map(String::length).orElse(0));
                budget.bindings(step.beforeBindings());budget.bindings(step.afterBindings());budget.diagnostics(step.diagnostics());
            }
            return null;
        } catch(EvidenceLimit limit){return limit.getMessage();}
    }
    private static final class EvidenceBudget {
        private final SqlEvaluationContext context;
        private final Map<SqlValueOrigin,Integer> visited=new IdentityHashMap<>();
        private long work,length;
        private EvidenceBudget(SqlEvaluationContext context){this.context=context;}
        private void add(long steps,long text) {
            context.check();work+=steps;length+=text;
            if(work>context.renderBudgets().steps())throw new EvidenceLimit("SQL transform binding/evidence work budget exceeded");
            if(length>context.renderBudgets().length())throw new EvidenceLimit("SQL transform output/evidence text length budget exceeded");
        }
        private void diagnostics(List<SqlDiagnostic> diagnostics){for(var diagnostic:diagnostics)add(1,(long)diagnostic.code().length()+diagnostic.message().length());}
        private void bindings(List<BoundParameter> bindings) {
            for(var binding:bindings) {
                add(1,(long)binding.expression().length()+binding.type().length()+binding.options().length());
                record Pending(SqlValueOrigin origin,int depth) {}
                var pending=new ArrayDeque<Pending>();pending.add(new Pending(binding.origin(),1));
                while(!pending.isEmpty()) {
                    var current=pending.removeLast();context.check();
                    if(current.depth()>context.renderBudgets().depth())throw new EvidenceLimit("SQL transform origin depth budget exceeded");
                    var previousDepth=visited.get(current.origin());
                    if(previousDepth!=null&&previousDepth>=current.depth())continue;
                    visited.put(current.origin(),current.depth());
                    if(current.origin() instanceof SqlValueOrigin.Expression expression) {
                        add(1,expression.expression().length());
                        // Bound the queue before retaining all child references.
                        if((long)pending.size()+expression.inputs().size()>context.renderBudgets().steps())throw new EvidenceLimit("SQL transform origin work budget exceeded");
                        for(var input:expression.inputs())pending.add(new Pending(input,current.depth()+1));
                    } else if(current.origin() instanceof SqlValueOrigin.Input input)add(1,input.name().length());
                    else add(1,0);
                }
            }
        }
    }
    private static final class EvidenceLimit extends RuntimeException {
        private EvidenceLimit(String message){super(message,null,false,false);}
    }
}
