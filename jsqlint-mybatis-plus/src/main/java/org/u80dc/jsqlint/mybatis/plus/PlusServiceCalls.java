package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.mybatis.spi.*;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;
import java.util.*;
import static org.u80dc.jsqlint.mybatis.plus.PlusClassMetadata.GenericType;

/** Service 库方法到逻辑 Mapper 调用的适配；目标项目覆盖的方法仍交给实际方法体分析。 */
final class PlusServiceCalls {
    private static final String PREFIX="com.baomidou.mybatisplus.extension.";
    private static final String BASE="com.baomidou.mybatisplus.core.mapper.BaseMapper";
    private static final String WRAPPER="com.baomidou.mybatisplus.core.conditions.Wrapper";
    private static final String CHAINS=PREFIX+"conditions.";
    private static final Map<String,String> METHODS=Map.ofEntries(Map.entry("save","insert"),Map.entry("updateById","updateById"),
            Map.entry("getById","selectById"),Map.entry("getOptById","selectById"),Map.entry("removeById","deleteById"),
            Map.entry("list","selectList"),Map.entry("listMaps","selectMaps"),Map.entry("listObjs","selectObjs"),
            Map.entry("count","selectCount"),Map.entry("remove","delete"),Map.entry("update","update"));
    private final MybatisExtension.MapperAccess mappers;
    private final org.u80dc.jsqlint.mybatis.MybatisOptions options;
    private final Map<JavaView,Map<String,ServiceType>> types=new IdentityHashMap<>();
    private final Map<JavaView,String> versions=new IdentityHashMap<>();
    private record ServiceType(boolean service,Optional<String> mapper,List<String> issues) {}
    PlusServiceCalls(MybatisExtension.MapperAccess mappers,org.u80dc.jsqlint.mybatis.MybatisOptions options){this.mappers=mappers;this.options=options;}

    void register(DiscoveryRegistry registry) {
        registry.sqlInvocation(this::matches);
        registry.extraction(this::matches,this::extract);
    }
    private boolean matches(InvocationSite site) {
        var signature=site.invocation().getMethodSignature();
        if(site.invocation() instanceof AbstractInstanceInvokeExpr&&signature.getDeclClassType().toString().startsWith(CHAINS))
            return Set.of("list","one","count","exists","update","remove").contains(signature.getName());
        if(signature.getName().equals("update")&&signature.getParameterTypes().isEmpty())return false;
        if(!(site.invocation() instanceof AbstractInstanceInvokeExpr)||!METHODS.containsKey(signature.getName())
                &&!Set.of("saveBatch","updateBatchById","saveOrUpdate","saveOrUpdateBatch","removeByIds","listByIds","getOne","getOneOpt","getMap","getObj","exists","listByMap","removeByMap","page","pageMaps").contains(signature.getName()))return false;
        var type=type(signature.getDeclClassType().toString(),site.view());if(!type.service())return false;
        var declared=declaration(signature,site.view(),new HashSet<>());
        return declared.isEmpty()||declared.get().getDeclClassType().toString().startsWith(PREFIX);
    }
    private List<SqlTraceSource> extract(FlowPoint point,DataFlowAnalysis flow) {
        var site=point.invocation(flow.program()).orElseThrow();var service=type(site.invocation().getMethodSignature().getDeclClassType().toString(),site.view());
        String version=versions.computeIfAbsent(site.view(),this::frameworkVersion);
        if(!PlusFrameworkVersion.is3x(version))return List.of(incomplete(site,"Unsupported or inconsistent Service framework versions: "+version+"; supported release family is 3.x"));
        if(declaration(site.invocation().getMethodSignature(),site.view(),new HashSet<>()).isEmpty())return List.of(incomplete(site,"Service/chain declaration is unavailable"));
        boolean chain=site.invocation().getMethodSignature().getDeclClassType().toString().startsWith(CHAINS);
        String factoryMethod=null;
        if(chain&&site.invocation() instanceof AbstractInstanceInvokeExpr receiver) {
            var roots=new PlusWrapperParameters(flow,options).origins(point.value(receiver.getBase()));
            if(roots.complete()&&roots.values().size()==1&&roots.values().getFirst().value() instanceof AbstractInstanceInvokeExpr factory) {
                factoryMethod=factory.getMethodSignature().toString();var root=roots.values().getFirst();
                service=type(factory.getBase().getType().toString(),site.view());
                if(service.mapper().isEmpty()) {
                    var owners=flow.origins(root.point().value(factory.getBase()),ignored->false);
                    if(owners.complete()&&owners.values().size()==1)service=type(owners.values().getFirst().value().getType().toString(),site.view());
                }
            }
        }
        if(service.mapper().isEmpty()&&site.invocation() instanceof AbstractInstanceInvokeExpr receiver) {
            var origins=flow.origins(point.value(receiver.getBase()),ignored->false);
            if(origins.complete()&&origins.values().size()==1)
                service=type(origins.values().getFirst().value().getType().toString(),site.view());
        }
        if(service.mapper().isEmpty())return List.of(incomplete(site,"Service Mapper type is unresolved: "+service.issues()));
        String operation=METHODS.get(site.invocation().getMethodSignature().getName());
        boolean batch=Set.of("saveBatch","updateBatchById").contains(site.invocation().getMethodSignature().getName());
        if(batch)operation=site.invocation().getMethodSignature().getName().equals("saveBatch")?"insert":"updateById";
        if(chain)operation=switch(site.invocation().getMethodSignature().getName()){case "list","one"->"selectList";case "count","exists"->"selectCount";case "remove"->"delete";case "update"->"update";default->null;};
        if(operation==null)return List.of(incomplete(site,"Service batch/conditional operation is not yet modeled: "+site.invocation().getMethodSignature()));
        var target=mapper(service.mapper().get(),operation,site);
        if(target.isEmpty())return List.of(incomplete(site,"Service overload is not yet modeled: "+site.invocation().getMethodSignature()));
        var context=target.get();
        var definition=mappers.definition(context);
        if(definition.isEmpty())return List.of(incomplete(site,"No active Mapper statement for Service: "+context.signature()));
        if(batch)return batch(site,point,flow,context);
        // 把框架逻辑调用登记到本次索引；真实 Service 调用点仍是提取结果的调用点。
        flow.implicitInvocation(point,context.method(),(input,dependency)->Optional.empty());
        List<? extends sootup.core.jimple.common.Value> arguments=site.invocation().getArgs();
        if(chain&&site.invocation() instanceof AbstractInstanceInvokeExpr receiver) {
            if(operation.equals("update"))arguments=List.of(arguments.isEmpty()?sootup.core.jimple.common.constant.NullConstant.getInstance():arguments.getFirst(),receiver.getBase());
            else arguments=List.of(receiver.getBase());
        } else if(arguments.isEmpty()&&Set.of("selectList","selectMaps","selectObjs","selectCount").contains(operation))
            arguments=List.of(sootup.core.jimple.common.constant.NullConstant.getInstance());
        else if(operation.equals("update")&&site.invocation().getMethodSignature().getParameterTypes().stream().map(Object::toString).toList().equals(List.of(WRAPPER)))
            arguments=List.of(sootup.core.jimple.common.constant.NullConstant.getInstance(),arguments.getFirst());
        var sources=mappers.extract(context,point,flow,arguments);
        var result=new ArrayList<SqlTraceSource>();
        for(var source:sources) {
            if(source.templateInput().isEmpty()) {result.add(source);continue;}
            var input=source.templateInput().get();var original=input.definition();var metadata=new LinkedHashMap<>(original.metadata());
            metadata.put("serviceMethod",site.invocation().getMethodSignature().toString());metadata.put("logicalMapperMethod",context.signature().toString());
            if(factoryMethod!=null)metadata.put("chainFactoryMethod",factoryMethod);
            var adapted=original.withMetadata(metadata);
            var saved=new TemplateSqlInput(adapted,input.parameters(),input.fallback(),input.parameterMapName(),input.constants(),input.sqlSource(),input.renderParameters());
            result.add(new SqlTraceSource(source.sql(),source.bindings(),source.batchEntry(),source.issues(),source.controlFlowPaths(),Optional.of(saved)));
        }
        return List.copyOf(result);
    }
    /** IService/ServiceImpl 曾位于 extension，较新版本另有 repository/spring；只校验目标实际提供的组件。 */
    private String frameworkVersion(JavaView view) {
        String core=PlusFrameworkVersion.read(view);
        var components=new LinkedHashMap<String,String>();components.put("core",core);
        for(String anchor:List.of("repository.IRepository","service.IService","service.impl.ServiceImpl","conditions.query.QueryChainWrapper")) {
            String type=PREFIX+anchor;
            if(view.getClass(view.getIdentifierFactory().getClassType(type)).isPresent())
                components.put(anchor,PlusFrameworkVersion.read(view,type));
        }
        if(components.size()>1&&PlusFrameworkVersion.is3x(core)&&components.values().stream().allMatch(core::equals))return core;
        return components.entrySet().stream().map(entry->entry.getKey()+"="+entry.getValue()).collect(java.util.stream.Collectors.joining(", "));
    }
    private List<SqlTraceSource> batch(InvocationSite site,FlowPoint point,DataFlowAnalysis flow,MapperMethodContext context) {
        if(site.invocation().getArgCount()<1||site.invocation().getArgCount()>2)return List.of(incomplete(site,"Unsupported batch overload"));
        if(site.invocation().getArgCount()==2&&site.invocation().getArg(1) instanceof sootup.core.jimple.common.constant.IntConstant size&&size.getValue()<=0)
            return List.of(incomplete(site,"Batch flush size must be positive; execution cannot be assumed"));
        // 默认单参数方法会委托二参数实现，应用覆盖的批量实现不能冒充框架默认批量行为。
        if(site.invocation().getArgCount()==1) {
            var delegate=site.view().getIdentifierFactory().getMethodSignature(site.invocation().getMethodSignature().getDeclClassType().toString(),
                    site.invocation().getMethodSignature().getName(),"boolean",List.of("java.util.Collection","int"));
            var method=declaration(delegate,site.view(),new HashSet<>());
            if(method.isPresent()&&!method.get().getDeclClassType().toString().startsWith(PREFIX))return List.of(incomplete(site,"Application batch delegate override is not modeled"));
        }
        var read=PlusBatchSnapshot.read(point.value(site.invocation().getArg(0)),flow);
        if(read.snapshot().isEmpty())return List.of(incomplete(site,read.issue()));
        var snapshot=read.snapshot().get();var result=new ArrayList<SqlTraceSource>();int position=0;
        for(var item:snapshot.items()) {
            flow.checkpoint();position++;
            for(var source:mappers.extract(context,snapshot.point(),flow,List.of(item))) {
                if(source.templateInput().isEmpty())return List.of(incomplete(site,"Batch Mapper Provider/value SQL is not yet modeled"));
                var input=source.templateInput().get();var original=input.definition();var metadata=new LinkedHashMap<>(original.metadata());
                metadata.put("serviceMethod",site.invocation().getMethodSignature().toString());metadata.put("logicalMapperMethod",context.signature().toString());
                metadata.put("batchIndex",Integer.toString(position));metadata.put("batchSize",Integer.toString(snapshot.items().size()));
                var issues=new ArrayList<>(source.issues());
                if(site.invocation().getArgCount()==2&&!(site.invocation().getArg(1) instanceof sootup.core.jimple.common.constant.IntConstant))issues.add("Batch flush size is unresolved; execution coverage is incomplete");
                var definition=original.withMetadata(metadata);
                var adapted=new TemplateSqlInput(definition,input.parameters(),input.fallback(),input.parameterMapName(),input.constants(),input.sqlSource(),input.renderParameters());
                var entry=new SourceLocation(site.location().resource(),site.location().element()+" [batch "+position+"/"+snapshot.items().size()+"]");
                result.add(new SqlTraceSource(source.sql(),source.bindings(),Optional.of(entry),issues,source.controlFlowPaths(),Optional.of(adapted)));
            }
        }
        flow.implicitInvocation(point,context.method(),(input,dependency)->Optional.empty());return List.copyOf(result);
    }
    private Optional<MapperMethodContext> mapper(String mapper,String operation,InvocationSite site) {
        var types=site.invocation().getMethodSignature().getParameterTypes().stream().map(Object::toString).toList();
        List<String> expected;
        if(Set.of("saveBatch","updateBatchById").contains(site.invocation().getMethodSignature().getName())
                &&(types.equals(List.of("java.util.Collection"))||types.equals(List.of("java.util.Collection","int"))))expected=List.of("java.lang.Object");
        else if(site.invocation().getMethodSignature().getDeclClassType().toString().startsWith(CHAINS)) {
            if(operation.equals("update")&&types.size()<=1)expected=List.of("java.lang.Object",WRAPPER);
            else if(Set.of("selectList","selectCount","delete").contains(operation)&&types.isEmpty())expected=List.of(WRAPPER);
            else return Optional.empty();
        }
        else if(Set.of("insert","updateById").contains(operation)&&types.size()==1)expected=List.of("java.lang.Object");
        else if(operation.equals("selectById")&&types.equals(List.of("java.io.Serializable")))expected=types;
        else if(operation.equals("deleteById")&&(types.equals(List.of("java.io.Serializable"))||types.equals(List.of("java.lang.Object"))))expected=types;
        else if(Set.of("selectList","selectMaps","selectObjs","selectCount").contains(operation)&&(types.isEmpty()||types.equals(List.of(WRAPPER))))expected=List.of(WRAPPER);
        else if(operation.equals("delete")&&types.equals(List.of(WRAPPER)))expected=types;
        else if(operation.equals("update")&&(types.equals(List.of("java.lang.Object",WRAPPER))||types.equals(List.of(WRAPPER))))expected=List.of("java.lang.Object",WRAPPER);
        else return Optional.empty();
        var base=site.view().getClass(site.view().getIdentifierFactory().getClassType(BASE));if(base.isEmpty())return Optional.empty();
        var method=base.get().getMethods().stream().filter(candidate->candidate.getName().equals(operation)
                &&candidate.getParameterTypes().stream().map(Object::toString).toList().equals(expected)).findFirst();
        if(method.isEmpty())return Optional.empty();
        var signature=site.view().getIdentifierFactory().getMethodSignature(mapper,operation,method.get().getReturnType().toString(),expected);
        var resolved=declaration(signature,site.view(),new HashSet<>());
        return Optional.of(new MapperMethodContext(signature,resolved.orElse(method.get()),site.view()));
    }
    private ServiceType type(String name,JavaView view) {
        return types.computeIfAbsent(view,ignored->new HashMap<>()).computeIfAbsent(name,ignored->{
            var mappers=new LinkedHashSet<String>();var issues=new ArrayList<String>();boolean[] service={false};
            collect(GenericType.type(name),view,new HashSet<>(),mappers,service,issues,0);
            if(mappers.size()>1)issues.add("Ambiguous Service Mapper types: "+mappers);
            return new ServiceType(service[0],mappers.size()==1?Optional.of(mappers.iterator().next()):Optional.empty(),List.copyOf(issues));
        });
    }
    private void collect(GenericType type,JavaView view,Set<GenericType> visited,Set<String> mappers,boolean[] service,List<String> issues,int depth) {
        if(depth>64){issues.add("Service generic inheritance depth exceeded");return;}
        if(!visited.add(type)||type.name().equals("java.lang.Object"))return;
        if(Set.of(PREFIX+"service.impl.ServiceImpl",PREFIX+"repository.CrudRepository",PREFIX+"repository.AbstractRepository").contains(type.name())) {
            service[0]=true;if(type.arguments().size()==2&&type.arguments().getFirst().concrete())mappers.add(type.arguments().getFirst().name());
        }
        if(Set.of(PREFIX+"service.IService",PREFIX+"repository.IRepository").contains(type.name()))service[0]=true;
        try {
            var metadata=PlusClassMetadata.read(type.name(),view);var bindings=new HashMap<String,GenericType>();
            for(int index=0;index<Math.min(type.arguments().size(),metadata.parameters().size());index++)bindings.put(metadata.parameters().get(index),type.arguments().get(index));
            for(var parent:metadata.parents())collect(parent.substitute(bindings),view,visited,mappers,service,issues,depth+1);
        }catch(java.io.IOException|RuntimeException failure){issues.add("Service generic metadata unavailable: "+type.name());}
    }
    private Optional<JavaSootMethod> declaration(MethodSignature signature,JavaView view,Set<String> visited) {
        String owner=signature.getDeclClassType().toString();if(!visited.add(owner))return Optional.empty();
        var type=view.getClass(signature.getDeclClassType());if(type.isEmpty())return Optional.empty();
        var own=type.get().getMethod(signature.getSubSignature());if(own.isPresent())return own;
        var parents=new ArrayList<sootup.core.types.ClassType>();type.get().getSuperclass().ifPresent(parents::add);parents.addAll(type.get().getInterfaces());
        for(var parent:parents) {
            var inherited=view.getIdentifierFactory().getMethodSignature(parent,signature.getSubSignature());
            var method=declaration(inherited,view,visited);if(method.isPresent())return method;
        }
        return Optional.empty();
    }
    private SqlTraceSource incomplete(InvocationSite site,String issue) {
        var definition=new SqlDefinition(site.invocation().getMethodSignature().toString(),site.location(),ValueSqlNode.raw("unresolvedServiceSql"),SqlExpressionEvaluator.simple(),Map.of("serviceMethod",site.invocation().getMethodSignature().toString()),List.of(issue));
        return SqlTraceSource.template(new TemplateSqlInput(definition,Map.of(),Optional.empty()),List.of(issue));
    }
}
