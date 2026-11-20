package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.stmt.*;
import java.util.*;

/** 有限不可变集合的实参快照；只有形成集合后到批量调用之间没有影响对象的语句才可重用。 */
final class PlusBatchSnapshot {
    record Snapshot(FlowPoint point,List<Value> items) { Snapshot {items=List.copyOf(items);} }
    record Read(Optional<Snapshot> snapshot,String issue) {}
    static Read read(ValuePoint collection,DataFlowAnalysis flow) {
        var origins=flow.origins(collection,site->site.invocation() instanceof JStaticInvokeExpr
                &&site.invocation().getMethodSignature().getDeclClassType().toString().equals("java.util.List")
                &&site.invocation().getMethodSignature().getName().equals("of"));
        if(!origins.complete()||origins.values().size()!=1)return failure("Batch collection identity/size is unresolved");
        var origin=origins.values().getFirst();
        if(!(origin.value() instanceof JStaticInvokeExpr factory))return failure("Only finite List.of batch snapshots are currently modeled");
        if(factory.getArgs().stream().anyMatch(value->value.getType().toString().endsWith("[]")))return failure("Batch array-vararg collection expansion is not yet modeled");
        for(var value:factory.getArgs()) {
            flow.checkpoint();var item=flow.origins(origin.point().value(value),ignored->false);
            if(item.complete()&&item.values().size()==1&&item.values().getFirst().value() instanceof NullConstant)
                return failure("List.of with a null item cannot reach batch execution");
        }
        boolean safe=false;
        if(origin.point().method().equals(collection.point().method())&&origin.point().context().equals(collection.point().context()))
            safe=between(origin.point(),collection.point(),flow);
        else if(origin.point().context().calls().size()==collection.point().context().calls().size()+1
                &&origin.point().context().parent().equals(collection.point().context())) {
            var caller=origin.point().context().calls().getLast();
            if(caller.method().equals(collection.point().method())) {
                var returns=origin.point().method().getBody().getStmts().stream().filter(stmt->stmt instanceof ReturnStmt).toList();
                safe=returns.size()==1&&between(origin.point(),new FlowPoint(origin.point().method(),returns.getFirst(),origin.point().context()),flow)
                        &&between(new FlowPoint(caller.method(),caller.statement(),collection.point().context()),collection.point(),flow);
            }
        }
        if(!safe)return failure("Batch input has intervening effects or unsupported factory context; snapshot would be stale");
        return new Read(Optional.of(new Snapshot(origin.point(),new ArrayList<Value>(factory.getArgs()))),"");
    }
    private static boolean between(FlowPoint start,FlowPoint end,DataFlowAnalysis flow) {
        int from=flow.program().statementIndex(start.method(),start.statement()),to=flow.program().statementIndex(end.method(),end.statement());
        if(from<0||to<=from)return false;
        var statements=start.method().getBody().getStmts();
        for(int index=from+1;index<to;index++) {
            flow.checkpoint();var statement=statements.get(index);
            if(statement instanceof AbstractDefinitionStmt assignment&&assignment.getLeftOp() instanceof Local local
                    &&Set.of("java.util.List","java.util.Collection","java.lang.Iterable").contains(local.getType().toString())
                    &&(assignment.getRightOp() instanceof Local||assignment.getRightOp() instanceof JCastExpr))continue;
            return false;
        }
        return true;
    }
    private static Read failure(String issue){return new Read(Optional.empty(),issue);}
}
