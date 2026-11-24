package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.mybatis.*;
import org.u80dc.jsqlint.mybatis.spi.*;
import sootup.java.core.views.JavaView;
import java.net.URI;
import java.util.*;

/** MyBatis-Plus 默认 CRUD 的静态语义扩展；复用 MyBatis 模板、绑定及 core 分析结果。 */
public final class MybatisPlusExtension implements MybatisExtension {
    private static final String BASE_MAPPER = PlusEntityMetadataReader.BASE_MAPPER;
    private final MybatisPlusOptions options;
    private final PlusPluginConfig plugins;
    private final boolean projectConfiguration;
    private final Optional<PlusSqlInjectorModel> injector;
    public MybatisPlusExtension() { this(MybatisPlusOptions.defaults(),PlusPluginConfig.none(),true); }
    public MybatisPlusExtension(MybatisPlusOptions options) { this(options,PlusPluginConfig.none()); }
    public MybatisPlusExtension(MybatisPlusOptions options,PlusPluginConfig plugins) {this(options,plugins,false);}
    /** projectConfiguration=true 时项目资源可覆盖默认值；显式 Options 构造器保留完整调用方覆盖语义。 */
    public MybatisPlusExtension(MybatisPlusOptions options,PlusPluginConfig plugins,boolean projectConfiguration) {this(options,plugins,projectConfiguration,Optional.empty());}
    public MybatisPlusExtension(MybatisPlusOptions options,PlusPluginConfig plugins,boolean projectConfiguration,Optional<PlusSqlInjectorModel> injector) {this.options=Objects.requireNonNull(options);this.plugins=Objects.requireNonNull(plugins);this.projectConfiguration=projectConfiguration;this.injector=Objects.requireNonNull(injector);}
    @Override public String id() { return "mybatis-plus"; }
    @Override public Session createSession(MybatisOptions mybatisOptions) { return new PlusSession(options, mybatisOptions,plugins,projectConfiguration,injector); }

    private static final class PlusSession implements Session {
        private MybatisPlusOptions options;
        private final PlusProjectConfiguration configuration;
        private final Optional<PlusSqlInjectorModel> injector;
        private final MybatisOptions mybatisOptions;
        private final PlusPluginConfig plugins;
        private final Map<JavaView, PlusEntityMetadataReader> readers = new IdentityHashMap<>();
        private final Map<JavaView, String> versions = new IdentityHashMap<>();
        private final Map<JavaView, PlusSqlDefaults> sqlDefaults = new IdentityHashMap<>();
        private final Map<JavaView, Optional<Map<String,String>>> paginationDefaults = new IdentityHashMap<>();
        PlusSession(MybatisPlusOptions options, MybatisOptions mybatisOptions,PlusPluginConfig plugins,boolean projectConfiguration,Optional<PlusSqlInjectorModel> injector) { this.options = options; this.mybatisOptions = mybatisOptions;this.plugins=plugins;this.injector=injector;configuration=new PlusProjectConfiguration(options,projectConfiguration); }
        @Override public void register(DiscoveryRegistry registry, MybatisExtension.MapperAccess mappers) {
            registry.resource(PlusProjectConfiguration::accepts,(resource,seeds)->configuration.read(resource));
            new PlusServiceCalls(mappers, mybatisOptions).register(registry);
        }
        private Optional<EntityMapping> mapping(MapperMethodContext context) {
            configuration.configure(context.view());
            options=configuration.options();
            return readers.computeIfAbsent(context.view(), view -> new PlusEntityMetadataReader(view, options))
                    .resolve(context.signature().getDeclClassType().toString());
        }
        @Override public boolean supports(MapperMethodContext context) {
            if(injected(context).isPresent())return context.method().isAbstract()&&mapping(context).isPresent();
            // default 的框架实现可建模；项目自己覆盖的 Java 方法继续沿其真实方法体分析。
            if (!context.method().getDeclClassType().toString().equals(BASE_MAPPER))
                return context.method().isAbstract() && context.method().getParameterTypes().stream().anyMatch(type->type.toString().equals("com.baomidou.mybatisplus.core.conditions.Wrapper")) && mapping(context).isPresent();
            String name = context.method().getName();
            boolean supported = context.method().isAbstract() || Set.of("deleteById", "selectBatchIds", "selectByMap", "deleteByMap", "deleteBatchIds", "deleteByIds", "selectOne", "selectPage", "selectMapsPage", "insertOrUpdate", "insert", "updateById").contains(name);
            return supported && mapping(context).isPresent();
        }
        @Override public Optional<SqlDefinition> definition(MapperMethodContext context) {
            if (!supports(context)) return Optional.empty();
            var mappedEntity = mapping(context).orElseThrow();
            var issues = new ArrayList<>(mappedEntity.issues());
            issues.addAll(configuration.issues());
            String version = versions.computeIfAbsent(context.view(), PlusFrameworkVersion::read);
            if (!PlusFrameworkVersion.is3x(version)) issues.add("Unsupported MyBatis-Plus version: " + version + "; supported major version is 3.x");
            String pluginVersion=pluginVersion(context,version);
            if(!plugins.plugins().isEmpty()&&!pluginVersion.equals(version))issues.add("Inconsistent MyBatis-Plus plugin component versions: "+pluginVersion);
            String operation = context.method().getName();
            var custom=injected(context);
            if(custom.isPresent()) {
                var model=injector.orElseThrow();var method=custom.get();
                if(!model.frameworkVersion().equals(version))issues.add("Injector model version does not match target version");
                var metadata=new LinkedHashMap<>(configuration.metadata());metadata.putAll(Map.of("framework","mybatis-plus-injector","frameworkVersion",version,"injectorType",model.injectorType(),"configurationSource.injector","caller","method",context.signature().toString(),"command",method.command(),"pluginFrameworkVersion",pluginVersion));
                var definition=MybatisSqlTemplates.definition(mappedEntity.mapperType()+"."+method.name(),new SourceLocation(URI.create("java:"+model.injectorType()),method.name()+" [injected model]"),issues.isEmpty()?method.script():"${unresolvedInjectorSql}",mybatisOptions,metadata,issues);
                return Optional.of(plugins.plugins().isEmpty()?definition:definition.withTransform(new PlusSqlTransform(plugins,Optional.empty(),false,false)));
            }
            if(injector.filter(model->!model.includesDefaultMethods()).isPresent())issues.add("Configured injector model does not include default methods: "+operation);
            var defaults = sqlDefaults.computeIfAbsent(context.view(), PlusSqlDefaults::read);
            var paging = plugins.plugins().stream().anyMatch(plugin -> plugin instanceof PlusPluginConfig.Pagination)
                    ? paginationDefaults.computeIfAbsent(context.view(), PlusPaginationDefaults::read) : Optional.<Map<String,String>>empty();
            if (plugins.plugins().stream().anyMatch(plugin -> plugin instanceof PlusPluginConfig.Pagination)
                    && context.method().getParameterTypes().stream().anyMatch(type -> type.toString().equals("com.baomidou.mybatisplus.core.metadata.IPage"))
                    && paging.isEmpty()) issues.add("MyBatis-Plus pagination protocol is unavailable in target framework");
            var entity = generatedEntity(mappedEntity, defaults, operation);
            entity.issues().stream().filter(issue -> !issues.contains(issue)).forEach(issues::add);
            if (operation.equals("selectCount") && defaults.count().isEmpty()) issues.add("MyBatis-Plus count SQL defaults are unavailable in target framework");
            if (operation.equals("insert") && entity.primaryKey().filter(field -> field.idType().equals("AUTO")).isPresent()
                    && defaults.conditionalAutoIdInsert().isEmpty()) issues.add("MyBatis-Plus AUTO ID insert capabilities are unavailable in target framework");
            if (abstractPaginationOverride(context))
                issues.add("MyBatis-Plus default pagination method was redeclared abstract without an injected SQL statement: " + context.signature());
            var arguments = context.method().getParameterTypes().stream().map(Object::toString).toList();
            boolean wrapper = PlusWrapperParameters.method(operation, arguments);
            if (entity.primaryKey().isEmpty() && !operation.equals("insert") && !wrapper) issues.add("MyBatis-Plus entity primary key is unavailable: " + entity.entityType());
            boolean modeled = operation.equals("selectById") && arguments.equals(List.of("java.io.Serializable"))
                    || operation.equals("deleteById") && (arguments.equals(List.of("java.io.Serializable"))
                    || arguments.equals(List.of("java.lang.Object")) || arguments.equals(List.of("java.lang.Object", "boolean")))
                    || Set.of("insert", "updateById").contains(operation) && arguments.equals(List.of("java.lang.Object")) || wrapper;
            if (!modeled) issues.add("MyBatis-Plus built-in method is not yet supported: " + context.signature());
            var columns = new ArrayList<EntityMapping.Field>();
            entity.primaryKey().ifPresent(columns::add);
            entity.fields().stream().filter(EntityMapping.Field::select).filter(field -> !entity.primaryKey().filter(field::equals).isPresent()).forEach(columns::add);
            if (operation.equals("selectById") && columns.isEmpty()) issues.add("MyBatis-Plus select columns are unavailable");
            String sql = "${unresolvedMybatisPlusSql}";
            if (issues.isEmpty() && !entity.table().isBlank() && wrapper) {
                sql = PlusWrapperParameters.skeleton(entity, operation);
            } else if (issues.isEmpty() && !entity.table().isBlank() && Set.of("insert", "updateById").contains(operation)) {
                sql = PlusCrudTemplates.build(entity, operation, issues,plugins.plugins().stream().anyMatch(plugin->plugin instanceof PlusPluginConfig.OptimisticLock),defaults.conditionalAutoIdInsert().orElse(false));
            } else if (issues.isEmpty() && !entity.table().isBlank() && entity.primaryKey().isPresent()) {
                var key = entity.primaryKey().get();
                String binding = "#{" + key.property() + ",javaType=" + key.type() + "}";
                String where = key.column() + "=" + binding;
                if (entity.logicDelete().isPresent()) {
                    var logic = entity.logicDelete().get();
                    where += " AND " + logic.column() + (logic.logicNotDeleted().equalsIgnoreCase("null")
                            ? " IS NULL" : "=" + literal(logic.logicNotDeleted(), logic.type()));
                }
                if (operation.equals("selectById")) {
                    // MyBatis 的 underscore 设置允许自动映射默认列，显式不同列名需要属性别名。
                    String selected = columns.stream().map(EntityMapping.Field::selectColumn).collect(java.util.stream.Collectors.joining(","));
                    sql = "SELECT " + selected + " FROM " + entity.table() + " WHERE " + where;
                } else if (entity.logicDelete().isPresent()) {
                    var logic = entity.logicDelete().get();
                    sql = "UPDATE " + entity.table() + " SET " + logic.column() + "=" + literal(logic.logicDeleted(), logic.type()) + " WHERE " + where;
                    if (entity.fields().stream().anyMatch(field -> !field.equals(logic) && Set.of("UPDATE", "INSERT_UPDATE").contains(field.fill())))
                        issues.add("MyBatis-Plus logical delete with update fill is not supported");
                } else sql = "DELETE FROM " + entity.table() + " WHERE " + where;
            }
            var location = new SourceLocation(URI.create("java:" + context.signature().getDeclClassType()), context.signature().toString() + " [MyBatis-Plus]");
            var metadata = new LinkedHashMap<>(Map.of("framework", "mybatis-plus", "frameworkVersion", version, "mapperType", entity.mapperType(),
                    "entityType", entity.entityType(), "table", entity.table(), "method", context.signature().toString(),
                    "command", operation.startsWith("select") ? "select" : operation.startsWith("insert") ? "insert"
                            : operation.startsWith("update") || entity.logicDelete().isPresent() ? "update" : "delete"));
            var versionFields=entity.fields().stream().filter(EntityMapping.Field::version).toList();
            metadata.putAll(configuration.metadata());
            paging.ifPresent(metadata::putAll);
            metadata.put("frameworkCompatibility", PlusFrameworkVersion.is3x(version) ? "3.x" : "unsupported");
            defaults.defaultLogicDelete().ifPresent(logical -> metadata.put("defaultLogicDelete", Boolean.toString(logical)));
            defaults.wrapperGrouped().ifPresent(grouped -> metadata.put("wrapperGrouped", Boolean.toString(grouped)));
            if (operation.equals("selectCount")) defaults.count().ifPresent(count -> {
                metadata.put("countExpression", count.expression());
                metadata.put("countSuffix", count.suffix());
                metadata.put("countSelectColumns", Boolean.toString(count.selectedColumns()));
            });
            if (operation.equals("insert")) metadata.put("conditionalAutoIdInsert",
                    defaults.conditionalAutoIdInsert().map(Object::toString).orElse("unknown"));
            metadata.put("pluginFrameworkVersion",pluginVersion);
            metadata.put("configurationSource.plugins",plugins.plugins().isEmpty()?"default:none":"caller");
            if(versionFields.size()==1){
                var field=versionFields.getFirst();metadata.put("versionColumn",field.column());metadata.put("versionProperty",field.property());
                if(operation.equals("updateById")&&field.updateStrategy().equals("NEVER")
                        &&plugins.plugins().stream().anyMatch(plugin->plugin instanceof PlusPluginConfig.OptimisticLock)) {
                    String name="__jsqlint_original_version";
                    var properties=entity.fields().stream().map(EntityMapping.Field::property).toList();
                    while(properties.contains(name))name+="_";
                    metadata.put("originalVersionInput",name);metadata.put("originalVersionType",field.type());
                }
            }
            entity.logicDelete().ifPresent(field->metadata.put("logicDeleteColumn",field.column()));
            String statement=pageAlias(context).orElse(operation);
            var definition=MybatisSqlTemplates.definition(entity.mapperType() + "." + statement, location, sql,mybatisOptions, metadata, issues);
            return Optional.of(plugins.plugins().isEmpty()?definition:definition.withTransform(new PlusSqlTransform(plugins,Optional.empty(),false,false)));
        }
        private Optional<PlusSqlInjectorModel.Method> injected(MapperMethodContext context) {
            return injector.stream().flatMap(model->model.methods().stream()).filter(method->method.name().equals(context.method().getName())&&method.parameterTypes().equals(context.method().getParameterTypes().stream().map(Object::toString).toList())).findFirst();
        }
        @Override public Optional<String> statementAlias(MapperMethodContext context) {
            if(!context.method().getDeclClassType().toString().equals(BASE_MAPPER))return Optional.empty();
            return pageAlias(context);
        }
        private static Optional<String> pageAlias(MapperMethodContext context) {
            // 旧3.x的分页方法直接映射自己的语句；新默认方法委托到selectList/selectMaps。
            if (context.method().isAbstract()) return Optional.empty();
            return switch(context.method().getName()) {
                case "selectPage" -> Optional.of("selectList");
                case "selectMapsPage" -> Optional.of("selectMaps");
                default -> Optional.empty();
            };
        }
        private static boolean abstractPaginationOverride(MapperMethodContext context) {
            if (!context.method().isAbstract() || context.method().getDeclClassType().toString().equals(BASE_MAPPER)
                    || !Set.of("selectPage", "selectMapsPage").contains(context.method().getName())) return false;
            return context.view().getClass(context.view().getIdentifierFactory().getClassType(BASE_MAPPER)).stream()
                    .flatMap(type -> type.getMethods().stream())
                    .anyMatch(method -> method.getName().equals(context.method().getName())
                            && method.getParameterTypes().equals(context.method().getParameterTypes()) && !method.isAbstract());
        }
        @Override public List<SqlTraceSource> postprocess(MapperMethodContext context,List<SqlTraceSource> sources,FlowPoint point,DataFlowAnalysis flow) {
            if(plugins.plugins().isEmpty())return sources;
            var result=new ArrayList<SqlTraceSource>();
            for(var source:sources) {
                if(source.templateInput().isEmpty()){var issues=new ArrayList<>(source.issues());issues.add("Plugin rewriting of value SQL is not yet modeled");result.add(new SqlTraceSource(source.sql(),source.bindings(),source.batchEntry(),issues,source.controlFlowPaths(),source.templateInput()));continue;}
                var input=source.templateInput().get();var parameters=new LinkedHashMap<>(input.renderParameters());var problems=new ArrayList<>(source.issues());
                int pageIndex=-1;for(int index=0;index<context.method().getParameterCount();index++)if(context.method().getParameterType(index).toString().equals("com.baomidou.mybatisplus.core.metadata.IPage"))pageIndex=index;
                Optional<PlusSqlTransform.PageRequest> request=Optional.empty();
                boolean paginated=pageIndex>=0&&input.definition().metadata().getOrDefault("command","").startsWith("select");
                if(paginated&&plugins.plugins().stream().anyMatch(plugin->plugin instanceof PlusPluginConfig.Pagination)) {
                    var page=PlusPageFacts.read(point.value(point.invocation(flow.program()).orElseThrow().invocation().getArg(pageIndex)),flow);problems.addAll(page.issues());
                    if(page.complete()) {
                        var keys=new HashMap<String,String>();var reserved=new HashSet<>(input.parameters().keySet());reserved.addAll(parameters.keySet());reserved.addAll(input.constants().keySet());
                        for(var field:page.traces().entrySet()){String name="__jsqlint_page_"+field.getKey();while(!reserved.add(name))name+="_";keys.put(field.getKey(),name);parameters.put(name,new TemplateSqlInput.Parameter(field.getValue(),Set.of("searchCount","optimizeCount","optimizeJoin").contains(field.getKey())?"boolean":field.getKey().equals("maxLimit")?"java.lang.Long":"long"));}
                        var values=page.values();request=Optional.of(new PlusSqlTransform.PageRequest(((Number)values.get("current")).longValue(),((Number)values.get("size")).longValue(),(Boolean)values.get("searchCount"),(Boolean)values.get("optimizeCount"),(Boolean)values.get("optimizeJoin"),Optional.ofNullable((Number)values.get("maxLimit")).map(Number::longValue),keys.get("current"),keys.get("size")));
                    }
                }
                var metadata=new LinkedHashMap<>(input.definition().metadata());metadata.put("statementRole","main");metadata.put("pluginOrder",plugins.plugins().stream().map(plugin->plugin.getClass().getSimpleName()).collect(java.util.stream.Collectors.joining(",")));
                metadata.putIfAbsent("method",context.signature().toString());
                metadata.put("pluginFrameworkVersion",pluginVersion(context,versions.computeIfAbsent(context.view(),PlusFrameworkVersion::read)));
                metadata.put("configurationSource.plugins","caller");
                mapping(context).ifPresent(entity->{var fields=entity.fields().stream().filter(EntityMapping.Field::version).toList();if(fields.size()==1){metadata.put("versionColumn",fields.getFirst().column());metadata.put("versionProperty",fields.getFirst().property());}});
                if(request.filter(page->page.searchCount()&&page.size()>=0).isPresent()) {
                    var countMetadata=new LinkedHashMap<>(metadata);countMetadata.put("statementRole","count");countMetadata.put("command","select");
                    var definition=input.definition().withMetadata(countMetadata).withTransform(new PlusSqlTransform(plugins,request,true,true));
                    result.add(source(source,input,definition,parameters,problems));problems.add("Pagination count result is unknown; main query execution/overflow is not proven");
                }
                var definition=input.definition().withMetadata(metadata).withTransform(new PlusSqlTransform(plugins,request,paginated,false));result.add(source(source,input,definition,parameters,problems));
            }
            return List.copyOf(result);
        }
        private static SqlTraceSource source(SqlTraceSource source,TemplateSqlInput input,SqlDefinition definition,Map<String,TemplateSqlInput.Parameter> parameters,List<String> issues) {
            var adapted=new TemplateSqlInput(definition,input.parameters(),input.fallback(),input.parameterMapName(),input.constants(),input.sqlSource(),parameters);
            return new SqlTraceSource(source.sql(),source.bindings(),source.batchEntry(),issues,source.controlFlowPaths(),Optional.of(adapted));
        }
        private String pluginVersion(MapperMethodContext context,String coreVersion) {
            if(plugins.plugins().isEmpty())return coreVersion;
            var anchors=new ArrayList<>(List.of("com.baomidou.mybatisplus.extension.plugins.pagination.Page",
                    "com.baomidou.mybatisplus.extension.service.IService", "com.baomidou.mybatisplus.extension.service.impl.ServiceImpl"));
            if(plugins.plugins().stream().anyMatch(plugin->plugin instanceof PlusPluginConfig.Pagination))
                anchors.addAll(List.of("com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor",
                        "com.baomidou.mybatisplus.extension.plugins.PaginationInterceptor"));
            if(plugins.plugins().stream().anyMatch(plugin->plugin instanceof PlusPluginConfig.OptimisticLock))
                anchors.addAll(List.of("com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor",
                        "com.baomidou.mybatisplus.extension.plugins.OptimisticLockerInterceptor"));
            if(plugins.plugins().stream().anyMatch(plugin->plugin instanceof PlusPluginConfig.Tenant))
                anchors.addAll(List.of("com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor",
                        "com.baomidou.mybatisplus.extension.plugins.tenant.TenantSqlParser"));
            if(plugins.plugins().stream().anyMatch(plugin->plugin instanceof PlusPluginConfig.DynamicTables))
                anchors.addAll(List.of("com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor",
                        "com.baomidou.mybatisplus.extension.parsers.DynamicTableNameParser"));
            for (String anchor : anchors) {
                if (context.view().getClass(context.view().getIdentifierFactory().getClassType(anchor)).isEmpty()) continue;
                String extension = PlusFrameworkVersion.read(context.view(), anchor);
                if (!coreVersion.equals(extension)) return coreVersion + "/component("+anchor+"):" + extension;
            }
            return coreVersion;
        }
        @Override public Optional<SqlTraceSource> parameters(MapperMethodContext context, SqlDefinition definition,
                                                             FlowPoint point, DataFlowAnalysis flow) {
            return parameters(context, definition, point, flow, point.invocation(flow.program()).orElseThrow().invocation().getArgs());
        }
        @Override public Optional<SqlTraceSource> parameters(MapperMethodContext context, SqlDefinition definition,
                FlowPoint point, DataFlowAnalysis flow, List<? extends sootup.core.jimple.common.Value> arguments) {
            if(injected(context).isPresent())return Optional.empty();
            var entity = mapping(context).orElseThrow();
            if (definition.metadata().getOrDefault("framework", "").equals("mybatis-plus"))
                entity = generatedEntity(entity, sqlDefaults.computeIfAbsent(context.view(), PlusSqlDefaults::read), context.method().getName());
            var site = point.invocation(context.view()).orElseThrow();
            if (context.method().getParameterTypes().stream().anyMatch(type->type.toString().equals("com.baomidou.mybatisplus.core.conditions.Wrapper")))
                return Optional.of(new PlusWrapperParameters(flow, mybatisOptions).resolve(entity, context.method().getName(), definition, point, context, arguments));
            if (!Set.of("selectById", "deleteById", "insert", "updateById").contains(context.method().getName()) || arguments.isEmpty())
                return Optional.of(SqlTraceSource.template(new TemplateSqlInput(definition, Map.of(), Optional.empty()), List.of()));
            var actual = arguments.getFirst();
            var value = point.value(actual);
            if (PlusEntityParameters.isEntity(value, entity, flow)) {
                var source=PlusEntityParameters.resolve(value, entity, definition, flow, mybatisOptions);
                String originalVersion=definition.metadata().get("originalVersionInput");
                if(originalVersion!=null) {
                    var field=entity.fields().stream().filter(EntityMapping.Field::version).findFirst().orElseThrow();
                    var input=source.templateInput().orElseThrow();var render=new LinkedHashMap<>(input.renderParameters());
                    render.put(originalVersion,new TemplateSqlInput.Parameter(flow.traceProperty(value,field.property(),field.type(),PropertyAccess.FIELD),field.type()));
                    var adapted=new TemplateSqlInput(input.definition(),input.parameters(),input.fallback(),input.parameterMapName(),input.constants(),input.sqlSource(),render);
                    source=new SqlTraceSource(source.sql(),source.bindings(),source.batchEntry(),source.issues(),source.controlFlowPaths(),Optional.of(adapted));
                }
                return Optional.of(source);
            }
            String type = actual.getType().toString();
            String property = entity.primaryKey().map(EntityMapping.Field::property).orElse("id");
            String keyType = type;
            var issues = new ArrayList<String>();
            var parameter = new TemplateSqlInput.Parameter(point.value(actual), keyType);
            boolean scalar = Set.of("byte", "short", "int", "long", "float", "double", "boolean", "char", "null",
                    "java.lang.String", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
                    "java.lang.Double", "java.lang.Boolean", "java.lang.Character", "java.math.BigInteger", "java.math.BigDecimal").contains(type);
            if (!scalar) {
                issues.add("MyBatis-Plus entity or non-scalar ID parameter tracking is not supported: " + type);
                var trace = flow.trace(point.value(actual));
                parameter = new TemplateSqlInput.Parameter(new ValueTrace(trace.variable(), trace.callChain(),
                        new ValueTrace.Node(trace.variable().withType(keyType), ValueTrace.Kind.UNRESOLVED, "Unresolved entity primary key",
                                List.of(trace.root()))), keyType);
            }
            var aliases = new LinkedHashMap<String, TemplateSqlInput.Parameter>();
            aliases.put(property, parameter); aliases.put("_parameter", parameter); aliases.put("value", parameter);
            var constants = new LinkedHashMap<String, Object>(); constants.put("_databaseId", mybatisOptions.databaseId().orElse(null));
            return Optional.of(SqlTraceSource.template(new TemplateSqlInput(definition, aliases, Optional.of(parameter), Optional.empty(), constants), issues));
        }
        private static String literal(String value, String type) {
            if (Set.of("java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.CharSequence").contains(type) && !value.equalsIgnoreCase("null"))
                return "'" + value + "'";
            return value;
        }
        private static EntityMapping generatedEntity(EntityMapping entity, PlusSqlDefaults defaults, String operation) {
            if (operation.equals("insert")) return entity;
            if (entity.logicDelete().isEmpty() || defaults.defaultLogicDelete().filter(Boolean::booleanValue).isPresent()) return entity;
            if (defaults.defaultLogicDelete().isPresent())
                return new EntityMapping(entity.mapperType(), entity.entityType(), entity.table(), entity.fields(),
                        entity.primaryKey(), Optional.empty(), entity.issues());
            var issues = new ArrayList<>(entity.issues());
            issues.add("MyBatis-Plus default logical delete capabilities are unavailable in target framework");
            return new EntityMapping(entity.mapperType(), entity.entityType(), entity.table(), entity.fields(),
                    entity.primaryKey(), entity.logicDelete(), issues);
        }
    }
}
