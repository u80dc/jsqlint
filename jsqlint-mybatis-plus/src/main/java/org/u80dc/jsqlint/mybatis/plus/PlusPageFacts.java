package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferencer;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.stmt.AbstractDefinitionStmt;
import java.util.*;
import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.*;

/** Page 构造器及标量 setter 的只读模型；未知身份、分支或额外 Page 操作不冒充已知页请求。 */
final class PlusPageFacts {
    static final String PAGE="com.baomidou.mybatisplus.extension.plugins.pagination.Page";
    record Read(Map<String,ValueTrace> traces,Map<String,Object> values,List<String> issues) {
        Read {traces=Map.copyOf(traces);values=Collections.unmodifiableMap(new LinkedHashMap<>(values));issues=List.copyOf(issues);}
        boolean complete(){return issues.isEmpty();}
    }
    private record State(Map<String,ValuePoint> fields,List<String> issues,boolean constructorCurrent) {
        State {fields=Map.copyOf(fields);issues=List.copyOf(issues);}
    }
    static Read read(ValuePoint input,DataFlowAnalysis flow) {
        var root=identity(input,flow,0);if(!root.complete()||root.values().size()!=1)return failure("Page identity is unresolved");
        var origin=root.values().getFirst();if(!origin.value().getType().toString().equals(PAGE))return failure("Only concrete Page is currently modeled");
        var results=new ArrayList<State>();
        flow.backward(input.point(),new State(Map.of(),List.of(),true),(point,state)->{
            var fields=new LinkedHashMap<>(state.fields());var issues=new ArrayList<>(state.issues());boolean normalized=state.constructorCurrent();
            var site=point.invocation(flow.program());
            if(site.isPresent()&&site.get().invocation() instanceof AbstractInstanceInvokeExpr invoke&&Set.of(PAGE,"com.baomidou.mybatisplus.core.metadata.IPage").contains(invoke.getMethodSignature().getDeclClassType().toString())) {
                var alias=root.aliasTo(identity(point.value(invoke.getBase()),flow,0));
                if(alias==Alias.MUST) {
                    String method=invoke.getMethodSignature().getName();
                    if(method.equals("<init>"))constructor(fields,point,invoke.getArgs(),invoke.getMethodSignature().getParameterTypes().stream().map(Object::toString).toList());
                    else if(Map.of("setCurrent","current","setSize","size","setSearchCount","searchCount","setOptimizeCountSql","optimizeCount","setOptimizeJoinOfCountSql","optimizeJoin","setMaxLimit","maxLimit").containsKey(method)&&invoke.getArgCount()==1) {
                        String property=Map.of("setCurrent","current","setSize","size","setSearchCount","searchCount","setOptimizeCountSql","optimizeCount","setOptimizeJoinOfCountSql","optimizeJoin","setMaxLimit","maxLimit").get(method);
                        if(!fields.containsKey(property)){fields.put(property,point.value(invoke.getArg(0)));if(property.equals("current"))normalized=false;}
                    } else if(!Set.of("setTotal","setRecords").contains(method))issues.add("Unsupported Page operation: "+method);
                    return Decision.skipCall(new State(fields,issues,normalized));
                }else if(alias==Alias.MAY)issues.add("Page operation may target another object");
            }
            if(site.isPresent()&&flow.resolution(site.get(),point.context()).hasExternalTargets())for(var argument:site.get().invocation().getArgs())
                if(Set.of(PAGE,"com.baomidou.mybatisplus.core.metadata.IPage","java.lang.Object").contains(argument.getType().toString())
                        &&root.aliasTo(identity(point.value(argument),flow,0))!=Alias.NONE)issues.add("Page escaped to an external call");
            if(point.statement() instanceof AbstractDefinitionStmt write&&(write.getLeftOp() instanceof sootup.core.jimple.common.ref.JFieldRef||write.getLeftOp() instanceof sootup.core.jimple.common.ref.JArrayRef)
                    &&Set.of(PAGE,"com.baomidou.mybatisplus.core.metadata.IPage","java.lang.Object").contains(write.getRightOp().getType().toString())
                    &&root.aliasTo(identity(point.value(write.getRightOp()),flow,0))!=Alias.NONE)issues.add("Page escaped through heap storage");
            if(point.statement() instanceof AbstractDefinitionStmt assignment&&point.value(assignment.getRightOp()).equals(origin)) {
                if(origin.value() instanceof AbstractInvokeExpr factory)constructor(fields,point,factory.getArgs(),factory.getMethodSignature().getParameterTypes().stream().map(Object::toString).toList());
                fields.putIfAbsent("current",point.value(LongConstant.getInstance(1)));fields.putIfAbsent("size",point.value(LongConstant.getInstance(10)));
                for(String flag:List.of("searchCount","optimizeCount","optimizeJoin"))fields.putIfAbsent(flag,point.value(IntConstant.getInstance(1)));
                fields.putIfAbsent("maxLimit",point.value(NullConstant.getInstance()));return Decision.stop(new State(fields,issues,normalized));
            }
            return Decision.next(new State(fields,issues,normalized));
        },end->{var issues=new ArrayList<>(end.state().issues());issues.addAll(end.issues());if(end.end()!=End.FOUND)issues.add("Page history incomplete: "+end.end());results.add(new State(end.state().fields(),issues,end.state().constructorCurrent()));});
        if(results.size()!=1)return failure("Page has multiple control-flow histories");var state=results.getFirst();var issues=new ArrayList<>(state.issues());
        var traces=new LinkedHashMap<String,ValueTrace>();var values=new LinkedHashMap<String,Object>();
        var infer=new ParameterValueInferencer((type,name)->new SqlValue.Unknown("Page input is unknown"),flow::checkpoint);
        for(var field:state.fields().entrySet()) {
            var trace=flow.trace(field.getValue());traces.put(field.getKey(),trace);
            String type=Set.of("searchCount","optimizeCount","optimizeJoin").contains(field.getKey())?"boolean":field.getKey().equals("maxLimit")?"java.lang.Long":"long";
            var candidates=infer.infer(trace,type);if(!candidates.complete()||candidates.possibleValues().size()!=1||!(candidates.possibleValues().getFirst().value() instanceof SqlValue.Known known))issues.add("Page fact unresolved: "+field.getKey());
            else values.put(field.getKey(),known.value());
        }
        if(values.get("current") instanceof Number number&&state.constructorCurrent())values.put("current",Math.max(number.longValue(),1));
        return new Read(traces,values,issues);
    }
    private static void constructor(Map<String,ValuePoint> fields,FlowPoint point,List<? extends sootup.core.jimple.common.Value> args,List<String> types) {
        if(args.size()>=2){fields.putIfAbsent("current",point.value(args.get(0)));fields.putIfAbsent("size",point.value(args.get(1)));}
        if(args.size()>=3&&types.get(types.size()-1).equals("boolean"))fields.putIfAbsent("searchCount",point.value(args.get(args.size()-1)));
    }
    private static Origins identity(ValuePoint input,DataFlowAnalysis flow,int depth) {
        if(depth>64)return new Origins(List.of(),false);
        var roots=flow.origins(input,site->Set.of(PAGE,"com.baomidou.mybatisplus.core.metadata.IPage").contains(site.invocation().getMethodSignature().getDeclClassType().toString())
                &&Set.of(PAGE,"com.baomidou.mybatisplus.core.metadata.IPage").contains(site.invocation().getMethodSignature().getType().toString()));var values=new ArrayList<ValuePoint>();boolean complete=roots.complete();
        for(var root:roots.values())if(root.value() instanceof AbstractInstanceInvokeExpr invoke){var parent=identity(root.point().value(invoke.getBase()),flow,depth+1);values.addAll(parent.values());complete&=parent.complete();}else values.add(root);
        return new Origins(values.stream().distinct().toList(),complete);
    }
    private static Read failure(String issue){return new Read(Map.of(),Map.of(),List.of(issue));}
}
