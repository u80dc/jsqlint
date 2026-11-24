package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.mybatis.*;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.stmt.*;
import sootup.core.signatures.MethodSignature;
import java.util.*;
import static org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis.*;

/** 字符串 Wrapper 对象历史与模板适配；不执行 Wrapper 或 Consumer。 */
final class PlusWrapperParameters {
    private static final String CONDITIONS="com.baomidou.mybatisplus.core.conditions.";
    private static final String CHAINS="com.baomidou.mybatisplus.extension.conditions.";
    private final DataFlowAnalysis flow;
    private final MybatisOptions options;
    private final Map<String,TemplateSqlInput.Parameter> parameters=new LinkedHashMap<>();
    private final Map<String,Object> structuralConstants=new LinkedHashMap<>();
    private final List<String> issues=new ArrayList<>();
    private final Set<String> reserved=new HashSet<>();
    private int names;
    private EntityMapping entity;
    private String operation;
    private Map<String, String> metadata = Map.of();
    private record Step(FlowPoint point, AbstractInstanceInvokeExpr invocation) {}
    private record History(List<Step> steps,List<String> issues) {
        History { steps=List.copyOf(steps); issues=List.copyOf(issues); }
    }
    private record Parts(String where,String set,String order,String columns) {}

    PlusWrapperParameters(DataFlowAnalysis flow,MybatisOptions options) { this.flow=flow; this.options=options; }
    static boolean method(String name,List<String> arguments) {
        return Set.of("selectList","selectCount","selectMaps","selectObjs","delete").contains(name)
                && arguments.equals(List.of(CONDITIONS+"Wrapper"))
                || name.equals("update") && arguments.equals(List.of("java.lang.Object",CONDITIONS+"Wrapper"))
                ||Set.of("selectList","selectMaps","selectPage","selectMapsPage").contains(name)&&arguments.equals(List.of("com.baomidou.mybatisplus.core.metadata.IPage",CONDITIONS+"Wrapper"));
    }
    static String skeleton(EntityMapping entity,String operation) {
        return "<script>"+head(entity,operation,"${wrapperColumns}")+" ${wrapperSet} ${wrapperWhere} ${wrapperOrder}</script>";
    }
    SqlTraceSource resolve(EntityMapping entity,String operation,SqlDefinition original,FlowPoint point,org.u80dc.jsqlint.mybatis.spi.MapperMethodContext context) {
        return resolve(entity,operation,original,point,context,point.invocation(flow.program()).orElseThrow().invocation().getArgs());
    }
    SqlTraceSource resolve(EntityMapping entity,String operation,SqlDefinition original,FlowPoint point,
            org.u80dc.jsqlint.mybatis.spi.MapperMethodContext context,List<? extends sootup.core.jimple.common.Value> arguments) {
        this.entity=entity;
        this.operation=operation;
        this.metadata=original.metadata();
        issues.addAll(original.issues());
        if(!original.complete())return SqlTraceSource.template(new TemplateSqlInput(original,Map.of(),Optional.empty()),issues);
        entity.fields().forEach(field->reserved.add(field.property()));
        if(!original.metadata().getOrDefault("framework","").equals("mybatis-plus")) {
            return explicit(original,point,context,arguments);
        }
        var invocation=point.invocation(flow.program()).orElseThrow().invocation();
        int index=0;for(int position=0;position<context.method().getParameterCount();position++)if(context.method().getParameterType(position).toString().equals(CONDITIONS+"Wrapper"))index=position;
        var parts=parts(point.value(arguments.get(index)),flow,false,0);
        String set=parts.set();
        SqlTraceSource entitySource=null;
        if (operation.equals("update") && !(arguments.get(0) instanceof NullConstant)) {
            String entitySet=PlusCrudTemplates.build(entity,"updateSet",issues);
            for(var field:entity.fields()) {
                entitySet=entitySet.replace("#{"+field.property()+"}","#{et."+field.property()+"}")
                        .replace("#{"+field.property()+",","#{et."+field.property()+",")
                        .replace("test=\""+field.property()+" ","test=\"et."+field.property()+" ")
                        .replace(" and "+field.property()+" "," and et."+field.property()+" ");
            }
            set=entitySet+set;
        }
        if (operation.equals("delete") && entity.logicDelete().isPresent()) {
            var logic=entity.logicDelete().get(); set=xml(logic.column()+"="+literal(logic.logicDeleted(),logic.type())+",");
        }
        String columns=operation.equals("selectCount") ? countColumns(parts.columns())
                : parts.columns().isEmpty()?columns(entity,operation):parts.columns();
        String where=parts.where();
        boolean grouped=Boolean.parseBoolean(original.metadata().getOrDefault("wrapperGrouped","true"));
        if(entity.logicDelete().isPresent()) {
            var logic=entity.logicDelete().get();
            where=xml(logic.column()+(logic.logicNotDeleted().equalsIgnoreCase("null")?" IS NULL":"="+literal(logic.logicNotDeleted(),logic.type())))
                    +"<trim prefix='"+(grouped?" AND (":" AND ")+"' suffix='"+(grouped?")":"")
                    +"' prefixOverrides='AND |OR ' suffixOverrides='AND |OR '>"+where+"</trim>";
        } else where="<trim prefix='"+(grouped?"(":"")+"' suffix='"+(grouped?")":"")
                +"' prefixOverrides='AND |OR ' suffixOverrides='AND |OR '>"+where+"</trim>";
        String sql="<script>"+head(entity,operation,columns)
                +(operation.equals("update")||operation.equals("delete")&&entity.logicDelete().isPresent()?" <set>"+set+"</set>":"")
                +" <where>"+where+"</where> "+parts.order()+"</script>";
        var definition=MybatisSqlTemplates.definition(original.id(),original.location(),sql,options,original.metadata(),issues);
        if(operation.equals("update") && !(arguments.get(0) instanceof NullConstant)) {
            entitySource=PlusEntityParameters.resolve(point.value(arguments.get(0)),entity,definition,flow,options);
            parameters.putAll(entitySource.templateInput().orElseThrow().parameters());
            parameters.put("et",entitySource.templateInput().orElseThrow().parameters().get("_parameter"));
            issues.addAll(entitySource.issues());
        }
        return SqlTraceSource.template(new TemplateSqlInput(definition,parameters,Optional.empty(),Optional.empty(),structuralConstants),issues.stream().distinct().toList());
    }
    private SqlTraceSource explicit(SqlDefinition original,FlowPoint point,org.u80dc.jsqlint.mybatis.spi.MapperMethodContext context,
                                   List<? extends sootup.core.jimple.common.Value> arguments) {
        var indexes=new ArrayList<Integer>();
        for(int index=0;index<context.method().getParameterCount();index++)
            if(context.method().getParameterType(index).toString().equals(CONDITIONS+"Wrapper"))indexes.add(index);
        if(indexes.size()!=1)return SqlTraceSource.template(new TemplateSqlInput(original,Map.of(),Optional.empty()),List.of("Exactly one XML Wrapper parameter is required"));
        int index=indexes.getFirst();var input=point.value(arguments.get(index));var variable=input.snapshot(flow.program()).withType("java.util.Map");
        var snapshot=input.value() instanceof NullConstant?flow.trace(input):new ValueTrace(variable,input.point().context().snapshot(),
                new ValueTrace.Node(variable,ValueTrace.Kind.EXPRESSION,"Wrapper XML root",List.of(),new ValueTrace.Computation(ValueTrace.Operation.MAP_LITERAL,"Wrapper XML root")));
        var modeled=new TemplateSqlInput.Parameter(snapshot,"java.util.Map");
        var modeledArguments=new HashMap<Integer,TemplateSqlInput.Parameter>();modeledArguments.put(index,modeled);
        for(int position=0;position<arguments.size();position++)if(position!=index&&arguments.get(position) instanceof NullConstant)
            modeledArguments.put(position,new TemplateSqlInput.Parameter(flow.trace(point.value(arguments.get(position))),context.method().getParameterType(position).toString()));
        var standard=MybatisSqlTemplates.parameters(original,context,point,options,modeledArguments);
        var base=standard.templateInput().orElseThrow();reserved.addAll(base.parameters().keySet());reserved.addAll(base.constants().keySet());
        var pending=new ArrayDeque<org.u80dc.jsqlint.core.node.SqlNode>();pending.add(original.template());int count=0;
        while(!pending.isEmpty()&&++count<=10000) {
            flow.checkpoint();var node=pending.removeLast();
            if(node instanceof org.u80dc.jsqlint.core.node.BindSqlNode bind)reserved.add(bind.name());
            if(node instanceof org.u80dc.jsqlint.core.node.ForEachSqlNode loop) {
                reserved.add(loop.item());reserved.add(loop.index());
            }
            pending.addAll(org.u80dc.jsqlint.core.node.SqlNodeGraphs.children(node));
        }
        var parts=parts(input,flow,false,0);var replacements=new HashMap<String,org.u80dc.jsqlint.core.node.SqlNode>();
        for(var alias:base.parameters().entrySet())if(alias.getValue().equals(modeled)) {
            replacements.put(alias.getKey()+".customSqlSegment",segment("<where>"+group(parts.where())+"</where> "+parts.order()));
            replacements.put(alias.getKey()+".sqlSegment",segment(group(parts.where())+" "+parts.order()));
            replacements.put(alias.getKey()+".sqlSet",segment("<trim suffixOverrides=','>"+parts.set()+"</trim>"));
            replacements.put(alias.getKey()+".sqlSelect",segment(parts.columns()));
        }
        org.u80dc.jsqlint.core.node.SqlNode template=original.template();
        try{template=new PlusTemplateSegments(replacements,flow::checkpoint).replace(template);}catch(IllegalArgumentException failure){issues.add(failure.getMessage());}
        var definition=new SqlDefinition(original.id(),original.location(),template,original.evaluator(),original.metadata(),issues);
        issues.addAll(standard.issues());
        var constants=new LinkedHashMap<>(base.constants());constants.putAll(structuralConstants);
        return SqlTraceSource.template(new TemplateSqlInput(definition,base.parameters(),base.fallback(),base.parameterMapName(),constants,base.sqlSource(),parameters),issues.stream().distinct().toList());
    }
    private org.u80dc.jsqlint.core.node.SqlNode segment(String text) {
        var definition=MybatisSqlTemplates.definition("wrapper-segment",new SourceLocation(java.net.URI.create("java:wrapper"),"segment"),"<script>"+text+"</script>",options,Map.of(),List.of());
        issues.addAll(definition.issues());return definition.template();
    }
    private Parts parts(ValuePoint input,DataFlowAnalysis analysis,boolean nested,int depth) {
        if(depth>32) return unresolved("Wrapper nesting budget exceeded");
        if(input.value() instanceof NullConstant) return new Parts("","","","");
        var origins=identity(input,analysis,new HashSet<>(),0);
        if(!origins.complete()||origins.values().size()!=1) return unresolved("Wrapper receiver identity is unresolved");
        var root=origins.values().getFirst();
        if(root.value() instanceof AbstractInvokeExpr factory && factory.getArgCount()!=0)
            return unresolved("Wrapper factory entity arguments are not modeled");
        String type=root.value().getType().toString();
        if(!nested && !Set.of(CONDITIONS+"query.QueryWrapper",CONDITIONS+"update.UpdateWrapper",CONDITIONS+"query.LambdaQueryWrapper",CONDITIONS+"update.LambdaUpdateWrapper",
                CHAINS+"query.QueryChainWrapper",CHAINS+"query.LambdaQueryChainWrapper",CHAINS+"update.UpdateChainWrapper",CHAINS+"update.LambdaUpdateChainWrapper").contains(type))
            return unresolved("Only allocated string-column QueryWrapper/UpdateWrapper is supported: "+type);
        var histories=new ArrayList<History>();
        analysis.backward(input.point(),new History(List.of(),List.of()),(point,state)->{
            var steps=new ArrayList<>(state.steps()); var problems=new ArrayList<>(state.issues());
            var call=point.invocation(analysis.program());
            if(call.isPresent() && call.get().invocation() instanceof AbstractInstanceInvokeExpr instance) {
                var signature=instance.getMethodSignature();
                if(signature.getDeclClassType().toString().startsWith(CONDITIONS)||signature.getDeclClassType().toString().startsWith(CHAINS)) {
                    var alias=origins.aliasTo(identity(point.value(instance.getBase()),analysis,new HashSet<>(),0));
                    if(alias==Alias.MUST) {
                        if(signature.getName().equals("<init>")) {
                            if(instance.getArgCount()!=0) problems.add("Wrapper constructor entity/column arguments are not modeled");
                        } else steps.add(new Step(point,instance));
                    } else if(alias==Alias.MAY) problems.add("Wrapper operation receiver may alias another object");
                    return Decision.skipCall(new History(steps,problems));
                }
            }
            if(call.isPresent() && analysis.resolution(call.get(),point.context()).hasExternalTargets()) {
                for(var argument:call.get().invocation().getArgs()) {
                    String argumentType=argument.getType().toString();
                    if((argumentType.startsWith(CONDITIONS)||argumentType.equals("java.lang.Object"))
                            && origins.aliasTo(identity(point.value(argument),analysis,new HashSet<>(),0))!=Alias.NONE)
                        problems.add("Wrapper escaped to an unmodeled external call: "+call.get().invocation().getMethodSignature());
                }
            }
            if(point.statement() instanceof AbstractDefinitionStmt assignment) {
                if((assignment.getLeftOp() instanceof JFieldRef||assignment.getLeftOp() instanceof JArrayRef)
                        && (assignment.getRightOp().getType().toString().startsWith(CONDITIONS)||assignment.getRightOp().getType().toString().equals("java.lang.Object"))
                        && origins.aliasTo(identity(point.value(assignment.getRightOp()),analysis,new HashSet<>(),0))!=Alias.NONE)
                    problems.add("Wrapper escaped through heap storage");
                if(point.value(assignment.getRightOp()).equals(root)) return Decision.stop(new History(steps,problems));
            }
            return Decision.next(new History(steps,problems));
        },end->{
            var problems=new ArrayList<>(end.state().issues()); problems.addAll(end.issues());
            if(end.end()!=End.FOUND) problems.add("Wrapper history incomplete: "+end.end());
            var steps=new ArrayList<>(end.state().steps()); Collections.reverse(steps);
            histories.add(new History(steps,problems));
        });
        if(histories.size()!=1) return unresolved("Wrapper has multiple Java control-flow histories; candidates are not collapsed");
        var history=histories.getFirst(); issues.addAll(history.issues());
        var result=render(history.steps(),analysis,depth);
        if(!history.issues().isEmpty()) return new Parts(result.where()+" AND ${"+unknown(history.issues().getFirst(),input)+"}",result.set(),result.order(),result.columns());
        return result;
    }
    private Origins identity(ValuePoint input,DataFlowAnalysis analysis,Set<ValuePoint> active,int depth) {
        analysis.checkpoint();
        if(depth>64||!active.add(input)) return new Origins(List.of(),false);
        try {
            var found=analysis.origins(input,site->{
                var method=site.invocation().getMethodSignature();
                return method.getDeclClassType().toString().startsWith(CONDITIONS)||method.getDeclClassType().toString().startsWith(CHAINS)||chainFactory(site)
                        ||method.getDeclClassType().toString().equals("com.baomidou.mybatisplus.core.toolkit.Wrappers")&&Set.of("query","update","lambdaQuery","lambdaUpdate").contains(method.getName());
            });
            var values=new ArrayList<ValuePoint>(); boolean complete=found.complete();
            for(var value:found.values()) {
                if(value.value() instanceof AbstractInstanceInvokeExpr invoke && !invoke.getMethodSignature().getName().equals("<init>")
                        &&!chainFactory(new InvocationSite(value.point().method(),value.point().statement(),analysis.program().statementIndex(value.point().method(),value.point().statement()),invoke,analysis.program().view()))) {
                    var parent=identity(value.point().value(invoke.getBase()),analysis,active,depth+1);
                    values.addAll(parent.values()); complete&=parent.complete();
                } else values.add(value);
            }
            return new Origins(values.stream().distinct().toList(),complete);
        } finally { active.remove(input); }
    }
    Origins origins(ValuePoint input) { return identity(input,flow,new HashSet<>(),0); }
    private static boolean chainFactory(InvocationSite site) {
        var method=site.invocation().getMethodSignature();
        if(!method.getType().toString().startsWith(CHAINS)||!Set.of("query","lambdaQuery","update","lambdaUpdate").contains(method.getName())||site.invocation().getArgCount()!=0)return false;
        var owner=site.view().getClass(method.getDeclClassType());
        return method.getDeclClassType().toString().startsWith("com.baomidou.mybatisplus.extension.")
                ||owner.isPresent()&&owner.get().getMethod(method.getSubSignature()).isEmpty();
    }
    private Parts render(List<Step> steps,DataFlowAnalysis analysis,int depth) {
        var where=new StringBuilder(); var set=new StringBuilder(); var ordering=new StringBuilder(); var grouping=new StringBuilder(); String selected="";
        String pendingOr="";
        for(var step:steps) {
            analysis.checkpoint(); var invoke=step.invocation(); String method=invoke.getMethodSignature().getName();
            if(invoke.getArgCount()==0&&(method.equals("getWrapper")||method.equals("lambda")&&invoke.getMethodSignature().getDeclClassType().toString().startsWith(CONDITIONS))) continue;
            var arguments=invoke.getArgs();
            boolean conditional=!invoke.getMethodSignature().getParameterTypes().isEmpty()
                    && invoke.getMethodSignature().getParameterTypes().getFirst().toString().equals("boolean");
            int offset=conditional?1:0;
            if(conditional && arguments.getFirst() instanceof IntConstant constant && constant.getValue()==0) continue;
            String condition=conditional?slot(step.point().value(arguments.getFirst()),"boolean",analysis):"";
            if(method.equals("clear")) { where.setLength(0);set.setLength(0);ordering.setLength(0);grouping.setLength(0);selected="";pendingOr="";continue; }
            if(method.equals("or") && arguments.size()==offset) {
                pendingOr=condition.isEmpty()?"true":pendingOr.isEmpty()?condition:"("+pendingOr+") or "+condition;
                continue;
            }
            if(method.equals("orderBy") && arguments.size()>=3) {
                String ascending=slot(step.point().value(arguments.get(1)),"boolean",analysis);
                String suffix="<choose><when test='"+ascending+"'> ASC</when><otherwise> DESC</otherwise></choose>";
                var expressions=new ArrayList<String>();
                for(int i=2;i<arguments.size();i++)expressions.add(columnList(step.point().value(arguments.get(i)),analysis,suffix));
                ordering.append(wrap(String.join(",",expressions)+",",condition));continue;
            }
            if(Set.of("and","or","nested","not").contains(method) && arguments.size()==offset+1) {
                var nested=lambda(step.point().value(arguments.get(offset)),analysis,depth+1);
                String prefix=method.equals("or")?"OR":method.equals("and")?"AND":connector(pendingOr);
                where.append(wrap(" "+prefix+" "+(method.equals("not")?"NOT ":"")+group(nested.where()),condition)); pendingOr=remainingOr(pendingOr,condition);continue;
            }
            if(method.equals("select") && operation.equals("selectCount")
                    && metadata.getOrDefault("framework", "").equals("mybatis-plus")
                    && !Boolean.parseBoolean(metadata.getOrDefault("countSelectColumns", "true"))) continue;
            if(Set.of("orderByAsc","orderByDesc","groupBy","select").contains(method) && arguments.size()>offset) {
                var expressions=new ArrayList<String>();
                String suffix=method.equals("orderByAsc")?" ASC":method.equals("orderByDesc")?" DESC":"";
                for(int i=offset;i<arguments.size();i++) expressions.add(columnList(step.point().value(arguments.get(i)),analysis,suffix,method.equals("select")));
                String text=String.join(",",expressions);
                if(method.equals("select")) selected=text;
                else (method.equals("groupBy")?grouping:ordering).append(wrap(text+",",condition));
                continue;
            }
            if(arguments.size()<=offset) { unsupported(step,where);continue; }
            String column="${"+column(step.point().value(arguments.get(offset)),analysis)+"}";
            String fragment;
            if(Set.of("isNull","isNotNull").contains(method)) fragment=column+(method.equals("isNull")?" IS NULL":" IS NOT NULL");
            else if(Set.of("eq","ne","gt","ge","lt","le","like","notLike","likeLeft","likeRight","notLikeLeft","notLikeRight","set").contains(method) && arguments.size()>=offset+2) {
                String value=slot(step.point().value(arguments.get(offset+1)),arguments.get(offset+1).getType().toString(),analysis);
                if(method.toLowerCase(Locale.ROOT).contains("like")) {
                    String bound=nextName();
                    String conversion=(method.endsWith("Right")?"":"'%' + ")+value+(method.endsWith("Left")?"":" + '%'");
                    fragment="<bind name='"+bound+"' value=\""+conversion+"\"/>"+column+(method.startsWith("not")?" NOT LIKE ":" LIKE ")+"#{"+bound+"}";
                } else {
                    String operator=switch(method) { case "eq","set"->"=";case "ne"->"<>";case "gt"->">";case "ge"->">=";case "lt"->"<";default->"<="; };
                    String mapping="";
                    if(method.equals("set")&&arguments.size()==offset+3) {
                        if(arguments.get(offset+2) instanceof StringConstant text) mapping=","+xml(text.getValue());
                        else {unsupported(step,where);continue;}
                    }
                    fragment=column+" "+xml(operator)+" #{"+value+mapping+"}";
                }
                if(method.equals("set")) { set.append(wrap(fragment+",",condition));continue; }
            } else if(Set.of("between","notBetween").contains(method) && arguments.size()==offset+3) {
                String first=slot(step.point().value(arguments.get(offset+1)),"java.lang.Object",analysis);
                String second=slot(step.point().value(arguments.get(offset+2)),"java.lang.Object",analysis);
                fragment=column+(method.equals("between")?" BETWEEN ":" NOT BETWEEN ")+"#{"+first+"} AND #{"+second+"}";
            } else if(Set.of("in","notIn").contains(method) && arguments.size()==offset+2) {
                var elements=elements(step.point().value(arguments.get(offset+1)),analysis,0);
                if(elements.isEmpty()) {unsupported(step,where);continue;}
                var bindings=new ArrayList<String>();
                for(var element:elements.get()) bindings.add("#{"+slot(element,element.value().getType().toString(),analysis)+"}");
                fragment=column+(method.equals("in")?" IN ":" NOT IN ")+"("+String.join(",",bindings)+")";
            } else { unsupported(step,where);continue; }
            where.append(wrap(" "+connector(pendingOr)+" "+fragment,condition));pendingOr=remainingOr(pendingOr,condition);
        }
        return new Parts(where.toString(),set.toString()," <trim prefix='GROUP BY ' suffixOverrides=','>"+grouping+"</trim> <trim prefix='ORDER BY ' suffixOverrides=','>"+ordering+"</trim>",selected);
    }
    private static String connector(String pending) {
        if(pending.isEmpty())return "AND";if(pending.equals("true"))return "OR";
        return "<choose><when test='"+pending+"'>OR</when><otherwise>AND</otherwise></choose>";
    }
    private static String remainingOr(String pending,String condition) { return pending.isEmpty()||condition.isEmpty()?"":"("+pending+") and !("+condition+")"; }
    private Parts lambda(ValuePoint consumer,DataFlowAnalysis analysis,int depth) {
        var origins=analysis.origins(consumer,site->site.invocation() instanceof JDynamicInvokeExpr);
        if(!origins.complete()||origins.values().size()!=1||!(origins.values().getFirst().value() instanceof JDynamicInvokeExpr dynamic))
            return unresolved("Nested Wrapper Consumer source is unresolved");
        if(!dynamic.getBootstrapMethodSignature().getDeclClassType().toString().equals("java.lang.invoke.LambdaMetafactory")) return unresolved("Unknown Consumer factory");
        var handle=dynamic.getBootstrapArgs().stream().filter(value->value instanceof MethodHandle).map(value->(MethodHandle)value).findFirst();
        if(handle.isEmpty()||!(handle.get().getReferenceSignature() instanceof MethodSignature signature)) return unresolved("Consumer method handle is unavailable");
        var method=analysis.program().view().getMethod(signature);
        if(method.isEmpty()||!method.get().hasBody()||!method.get().isStatic()) return unresolved("Only static nested Consumer bodies are modeled");
        var capture=origins.values().getFirst();
        ValueFlowModel captures=(input,dependency)->{
            if(input.point().method().getSignature().equals(signature)) {
                var sources=input.value() instanceof JParameterRef?new Origins(List.of(input),true):
                        input.value() instanceof Local?analysis.origins(input,ignored->false):new Origins(List.of(),false);
                if(sources.complete()&&sources.values().size()==1&&sources.values().getFirst().value() instanceof JParameterRef parameter
                        && parameter.getIndex()<dynamic.getArgCount())
                    return Optional.of(dependency.apply(capture.point().value(dynamic.getArg(parameter.getIndex()))));
            }
            return Optional.empty();
        };
        var nestedFlow=analysis.withModel(ignored->captures);
        var logical=nestedFlow.implicitInvocation(consumer.point(),method.get(),captures);
        var returns=method.get().getBody().getStmts().stream().filter(stmt->stmt instanceof ReturnStmt).toList();
        if(returns.size()!=1) return unresolved("Nested Consumer has multiple return paths");
        var parameter=method.get().getBody().getStmts().stream().filter(stmt->stmt instanceof AbstractDefinitionStmt assignment
                && assignment.getRightOp() instanceof JParameterRef ref && ref.getIndex()==dynamic.getArgCount())
                .map(stmt->((AbstractDefinitionStmt)stmt).getLeftOp()).findFirst();
        if(parameter.isEmpty()) return unresolved("Nested Consumer Wrapper parameter is unavailable");
        return parts(new FlowPoint(method.get(),returns.getFirst(),consumer.point().context().push(logical)).value(parameter.get()),nestedFlow,true,depth);
    }
    private void unsupported(Step step,StringBuilder where) { String issue="Unsupported string Wrapper operation: "+step.invocation().getMethodSignature();issues.add(issue);where.append(" AND ${").append(unknown(issue,step.point().value(step.invocation()))).append("}"); }
    private Parts unresolved(String issue) { issues.add(issue);return new Parts(" AND ${wrapperUnresolved}","${wrapperUnresolved}","",""); }
    private String unknown(String reason,ValuePoint input) {
        String name=nextName();var variable=input.snapshot(flow.program()).withType("java.lang.String");
        parameters.put(name,new TemplateSqlInput.Parameter(new ValueTrace(variable,input.point().context().snapshot(),new ValueTrace.Node(variable,ValueTrace.Kind.UNRESOLVED,reason,List.of())),"java.lang.String"));return name;
    }
    private String slot(ValuePoint value,String type,DataFlowAnalysis analysis) {
        String name=nextName(); parameters.put(name,new TemplateSqlInput.Parameter(analysis.trace(value),type));return name;
    }
    private String nextName() { String name;do{name="w"+(names++);}while(!reserved.add(name));return name; }
    private String columnList(ValuePoint value,DataFlowAnalysis analysis,String suffix) {
        return columnList(value,analysis,suffix,false);
    }
    private String columnList(ValuePoint value,DataFlowAnalysis analysis,String suffix,boolean select) {
        if(value.value().getType().toString().endsWith("[]")) {
            var elements=elements(value,analysis,0);
            if(elements.isPresent()) return String.join(",",elements.get().stream().map(element->"${"+column(element,analysis,select)+"}"+suffix).toList());
        }
        String name=column(value,analysis,select);
        return value.value().getType().toString().endsWith("[]")?"<foreach collection='"+name+"' item='column' separator=','>${column}"+suffix+"</foreach>":"${"+name+"}"+suffix;
    }
    private String column(ValuePoint value,DataFlowAnalysis analysis) {
        return column(value,analysis,false);
    }
    private String column(ValuePoint value,DataFlowAnalysis analysis,boolean select) {
        if(entity==null||entity.entityType().isBlank())return unknown("Wrapper entity mapping is unresolved",value);
        if(value.value() instanceof JArrayRef array && array.getIndex() instanceof IntConstant index) {
            var owner=analysis.origins(value.point().value(array.getBase()),ignored->false);
            var sources=new ArrayList<ValuePoint>();var failures=new ArrayList<String>();
            analysis.backward(value.point(),false,(point,escaped)->{
                boolean unsafe=escaped;var call=point.invocation(analysis.program());
                if(call.isPresent()&&analysis.resolution(call.get(),point.context()).hasExternalTargets())
                    for(var argument:call.get().invocation().getArgs())if(argument.getType().toString().endsWith("[]")||argument.getType().toString().equals("java.lang.Object"))
                        unsafe|=owner.aliasTo(analysis.origins(point.value(argument),ignored->false))!=Alias.NONE;
                if(point.statement() instanceof AbstractDefinitionStmt assignment&&assignment.getLeftOp() instanceof JArrayRef write
                        &&write.getIndex().equals(index)&&owner.aliasTo(analysis.origins(point.value(write.getBase()),ignored->false))==Alias.MUST) {
                    if(unsafe) failures.add("Lambda column array escaped");else sources.add(point.value(assignment.getRightOp()));
                    return Decision.stop(unsafe);
                }
                return Decision.next(unsafe);
            },end->{if(end.end()!=End.FOUND||!end.issues().isEmpty())failures.add("Lambda column array history incomplete");});
            if(failures.isEmpty()&&sources.size()==1)return column(sources.getFirst(),analysis,select);
        }
        if(value.value().getType().toString().equals("java.lang.String")) {
            String name=slot(value,"java.lang.String",analysis);
            literalColumn(value,analysis).ifPresent(column -> structuralConstants.put(name,column));
            return name;
        }
        var origins=analysis.origins(value,site->site.invocation() instanceof JDynamicInvokeExpr);
        if(origins.complete()&&origins.values().size()==1&&origins.values().getFirst().value() instanceof JDynamicInvokeExpr dynamic
                && dynamic.getArgCount()==0 && dynamic.getBootstrapMethodSignature().getDeclClassType().toString().equals("java.lang.invoke.LambdaMetafactory")) {
            var handle=dynamic.getBootstrapArgs().stream().filter(input->input instanceof MethodHandle).map(input->(MethodHandle)input).findFirst();
            if(handle.isPresent()&&handle.get().getReferenceSignature() instanceof MethodSignature getter
                    &&getter.getParameterTypes().isEmpty()&&!getter.getName().startsWith("lambda$")) {
                String name=getter.getName();String property=name.startsWith("get")&&name.length()>3?name.substring(3):name.startsWith("is")&&name.length()>2?name.substring(2):"";
                if(!property.isEmpty()) {
                    if(property.length()==1||!Character.isUpperCase(property.charAt(1))) property=Character.toLowerCase(property.charAt(0))+property.substring(1);
                    String selected=property;
                    var field=entity.fields().stream().filter(input->input.property().equals(selected)).findFirst();
                    var owner=analysis.program().view().getIdentifierFactory().getClassType(entity.entityType());
                    var visited=new HashSet<sootup.core.types.ClassType>();boolean matches=false;
                    while(visited.add(owner)) {
                        analysis.checkpoint();if(owner.equals(getter.getDeclClassType())){matches=true;break;}
                        var type=analysis.program().view().getClass(owner);if(type.isEmpty()||type.get().getSuperclass().isEmpty())break;
                        owner=type.get().getSuperclass().get();
                    }
                    if(field.isPresent()&&matches) {
                        var variable=value.snapshot(analysis.program()).withType("java.lang.String");String parameter=nextName();
                        String column=select?field.get().selectColumn():field.get().column();
                        parameters.put(parameter,new TemplateSqlInput.Parameter(new ValueTrace(variable,value.point().context().snapshot(),
                                new ValueTrace.Node(variable,ValueTrace.Kind.CONSTANT,"Lambda column: "+getter,List.of(),
                                        new ValueTrace.Computation(ValueTrace.Operation.LITERAL,column))),"java.lang.String"));
                        structuralConstants.put(parameter,column);
                        return parameter;
                    }
                }
            }
        }
        String reason="Lambda column method reference is unresolved or does not map to the entity: "+value.value();issues.add(reason);return unknown(reason,value);
    }
    private static Optional<String> literalColumn(ValuePoint value,DataFlowAnalysis analysis) {
        if(value.value() instanceof StringConstant constant)return Optional.of(constant.getValue());
        var origins=analysis.origins(value,ignored -> false);
        if(!origins.complete()||origins.values().isEmpty()||origins.values().stream().anyMatch(input -> !(input.value() instanceof StringConstant)))
            return Optional.empty();
        var columns=origins.values().stream().map(input -> ((StringConstant)input.value()).getValue()).distinct().toList();
        return columns.size()==1?Optional.of(columns.getFirst()):Optional.empty();
    }
    private Optional<List<ValuePoint>> elements(ValuePoint value,DataFlowAnalysis analysis,int depth) {
        analysis.checkpoint();if(depth>16)return Optional.empty();
        var origins=analysis.origins(value,site->{
            var method=site.invocation().getMethodSignature();return method.getDeclClassType().toString().equals("java.util.List")&&method.getName().equals("of")
                    ||method.getDeclClassType().toString().equals("java.util.Arrays")&&method.getName().equals("asList");
        });
        if(!origins.complete()||origins.values().size()!=1)return Optional.empty();
        var origin=origins.values().getFirst();
        if(origin.value() instanceof JNewArrayExpr array && array.getSize() instanceof IntConstant length
                && length.getValue()>=0 && length.getValue()<=1024 && value.value() instanceof Local local) {
            var result=new ArrayList<ValuePoint>();
            for(int index=0;index<length.getValue();index++) result.add(value.point().value(new JArrayRef(local,IntConstant.getInstance(index))));
            return Optional.of(result);
        }
        if(origin.value() instanceof AbstractInvokeExpr invoke) {
            if(invoke.getArgCount()==1 && invoke.getArg(0).getType().toString().endsWith("[]")) {
                boolean mutable=invoke.getMethodSignature().getDeclClassType().toString().equals("java.util.Arrays");
                if(mutable && !origin.point().method().equals(value.point().method())) return Optional.empty();
                return elements((mutable?value.point():origin.point()).value(invoke.getArg(0)),analysis,depth+1);
            }
            return Optional.of(invoke.getArgs().stream().map(argument->origin.point().value(argument)).toList());
        }
        return Optional.empty();
    }
    private static String group(String text) { return "<trim prefix='(' suffix=')' prefixOverrides='AND |OR ' suffixOverrides='AND |OR '>"+text+"</trim>"; }
    private static String wrap(String text,String condition) { return condition.isEmpty()?text:"<if test='"+condition+"'>"+text+"</if>"; }
    private static String head(EntityMapping entity,String operation,String columns) {
        if(operation.equals("update")||operation.equals("delete")&&entity.logicDelete().isPresent()) return "UPDATE "+xml(entity.table());
        return operation.equals("delete")?"DELETE FROM "+xml(entity.table()):"SELECT "+columns+" FROM "+xml(entity.table());
    }
    private static String columns(EntityMapping entity,String operation) {
        var fields=new ArrayList<EntityMapping.Field>();entity.primaryKey().ifPresent(fields::add);
        entity.fields().stream().filter(EntityMapping.Field::select).filter(field->!entity.primaryKey().filter(field::equals).isPresent()).forEach(fields::add);
        return xml(String.join(",",fields.stream().map(EntityMapping.Field::selectColumn).toList()));
    }
    private String countColumns(String selected) {
        boolean useSelection=Boolean.parseBoolean(metadata.getOrDefault("countSelectColumns", "false"));
        String expression=useSelection&&!selected.isEmpty()?selected:xml(metadata.getOrDefault("countExpression", "*"));
        return "COUNT( " + expression + " )" + xml(metadata.getOrDefault("countSuffix", ""));
    }
    private static String literal(String value,String type) { return type.equals("java.lang.String")&&!value.equalsIgnoreCase("null")?"'"+value+"'":value; }
    private static String xml(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;"); }
}
