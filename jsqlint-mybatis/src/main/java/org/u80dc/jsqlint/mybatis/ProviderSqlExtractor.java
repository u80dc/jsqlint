package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.TraceSqlExpressionEvaluator;
import org.u80dc.jsqlint.core.spi.*;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** Provider 的隐式调用、参数对象与返回 SQL 适配；Java 数据传播由 core 完成。 @author u80dc */
final class ProviderSqlExtractor {
    private final MybatisOptions options;
    private final ProviderMethodResolver methods;
    private final MapperParameterResolver parameters;

    ProviderSqlExtractor(MybatisOptions options) {
        this.options = options;
        methods = new ProviderMethodResolver(options);
        parameters = new MapperParameterResolver(options);
    }

    List<SqlTraceSource> extract(SqlDefinition definition, JavaSootMethod mapper, SqlTraceSource source,
                                 FlowPoint point, DataFlowAnalysis flow) {
        try { return extractResolved(definition, mapper, source, point, flow); }
        catch (IllegalArgumentException error) {
            var input = source.templateInput().orElseThrow();
            return List.of(SqlTraceSource.template(new TemplateSqlInput(failure(definition, error), input.parameters(), input.fallback(),
                    input.parameterMapName(), input.constants()), source.issues()));
        }
    }

    private List<SqlTraceSource> extractResolved(SqlDefinition definition, JavaSootMethod mapper, SqlTraceSource source,
                                                 FlowPoint point, DataFlowAnalysis flow) {
        var provider = methods.resolve(definition, mapper, flow.program().view());
        var input = source.templateInput().orElseThrow();
        var arguments = new ProviderArgumentModel(provider, arguments(provider, input, mapper, flow.program().view()), flow,
                point.context(), true, point.invocation(flow.program().view()).orElseThrow().invocation().getMethodSignature().getDeclClassType().toString(),
                mapper.getName(), options.databaseId());
        var invocation = flow.implicitInvocation(point, provider.method(), values(arguments, flow));
        var trace = flow.trace(point.value(invocation.invocation()));
        var raw = RawSqlBuilder.build(trace, flow::checkpoint);
        var result = new ArrayList<SqlTraceSource>();
        for (var variant : raw.variants()) {
            flow.checkpoint();
            var adapted = adapt(definition, provider, input, variant, raw.issues(), flow, -1);
            var issues = new LinkedHashSet<>(source.issues()); issues.addAll(raw.issues());
            result.add(SqlTraceSource.template(adapted, List.copyOf(issues)));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("MyBatis Provider has no SQL return value: " + provider.method().getSignature());
        return List.copyOf(result);
    }

    /** 未调用定义使用符号 Mapper 参数；保存的模板与 evaluator 不保留 JavaView。 */
    SqlDefinition definition(SqlDefinition base, JavaSootMethod mapper, DataFlowAnalysis flow) {
        try { return resolveDefinition(base, mapper, flow); }
        catch (IllegalArgumentException error) { return failure(base, error); }
    }

    private SqlDefinition resolveDefinition(SqlDefinition base, JavaSootMethod mapper, DataFlowAnalysis flow) {
        var provider = methods.resolve(base, mapper, flow.program().view());
        var source = parameters.resolve(base, mapper, flow.program().view(), index -> symbolic(mapper, index));
        var input = source.templateInput().orElseThrow();
        var values = arguments(provider, input, mapper, flow.program().view());
        var modeled = flow.withModel(active -> values(new ProviderArgumentModel(provider, values, active, CallContext.EMPTY, false,
                base.metadata().getOrDefault("namespace", mapper.getDeclClassType().toString()), mapper.getName(), options.databaseId()), active));
        var nodes = new ArrayList<Node>();
        for (var statement : provider.method().getBody().getStmts()) {
            flow.checkpoint();
            if (statement instanceof JReturnStmt returned)
                nodes.add(modeled.trace(new FlowPoint(provider.method(), statement, CallContext.EMPTY).value(returned.getOp())).root());
        }
        if (nodes.isEmpty()) throw new IllegalArgumentException("MyBatis Provider has no SQL return value: " + provider.method().getSignature());
        var variable = nodes.getFirst().variable();
        var trace = new ValueTrace(variable, List.of(), new Node(variable, Kind.EXPRESSION, "Provider return alternatives", nodes,
                new Computation(Operation.ALTERNATIVES, "Provider return alternatives")));
        var raw = RawSqlBuilder.build(trace, flow::checkpoint);
        var templates = new ArrayList<SqlNode>();
        var slots = new LinkedHashMap<String, ValueTrace>();
        var aliases = new LinkedHashMap<String, Variable>();
        input.parameters().forEach((name, parameter) -> aliases.put(name, parameter.trace(flow).variable()));
        var issues = new LinkedHashSet<>(base.issues()); issues.addAll(source.issues()); issues.addAll(raw.issues());
        int branch = 0;
        for (var variant : raw.variants()) {
            var adapted = adapt(base, provider, input, variant, raw.issues(), flow, branch++);
            templates.add(adapted.definition().template());
            adapted.parameters().forEach((name, parameter) -> {
                if (!input.parameters().containsKey(name)) slots.put(name, parameter.trace(flow));
            });
        }
        var evaluator = new TraceSqlExpressionEvaluator(new MybatisExpressionEvaluator(options), slots, aliases);
        return new SqlDefinition(base.id(), base.location(), SqlTemplateProjection.alternatives(templates), evaluator,
                metadata(base, provider), List.copyOf(issues));
    }

    private TemplateSqlInput adapt(SqlDefinition base, ProviderMethodResolver.Method provider, TemplateSqlInput input,
                                   RawSql.Variant variant, List<String> issues, DataFlowAnalysis flow, int branch) {
        var aliases = new LinkedHashMap<>(input.parameters());
        var sql = new StringBuilder(); int start = 0, slotIndex = 0;
        for (var slot : variant.placeholders()) {
            flow.checkpoint();
            sql.append(variant.text(), start, slot.start());
            String name;
            do {
                flow.checkpoint();
                if (++slotIndex > DataFlowAnalysis.MAX_STEPS) throw new IllegalArgumentException("MyBatis Provider placeholder naming budget exceeded");
                name = "__jsqlint_provider_value_" + (branch < 0 ? "" : branch + "_") + slotIndex;
            }
            while (variant.text().contains(name) || aliases.containsKey(name));
            sql.append("${").append(name).append('}');
            var saved = new ValueTrace(slot.source().variable(), variant.trace().map(ValueTrace::callChain).orElse(List.of()), slot.source());
            aliases.put(name, new TemplateSqlInput.Parameter(saved, "java.lang.String"));
            start = slot.end();
        }
        sql.append(variant.text(), start, variant.text().length());
        var evaluator = new MybatisExpressionEvaluator(options);
        var template = MybatisSqlAdapter.annotation(sql.toString(), options); evaluator.validate(template);
        var definition = new SqlDefinition(base.id(), base.location(), template, evaluator, metadata(base, provider), issues);
        // 即使 SQL 是常量，也把 Provider 返回路径纳入联合求解，避免混用互斥分支。
        variant.trace().ifPresent(saved -> aliases.put(hiddenName(aliases, variant.text(), flow), new TemplateSqlInput.Parameter(saved, "java.lang.String")));
        return new TemplateSqlInput(definition, aliases, input.fallback(), input.parameterMapName(), input.constants(), variant.trace());
    }

    private static String hiddenName(Map<String, ?> aliases, String sql, DataFlowAnalysis flow) {
        for (int i = 0; i < DataFlowAnalysis.MAX_STEPS; i++) {
            flow.checkpoint();
            String name = "__jsqlint_provider_source" + (i == 0 ? "" : "_" + i);
            if (!aliases.containsKey(name) && !sql.contains(name)) return name;
        }
        throw new IllegalArgumentException("MyBatis Provider source naming budget exceeded");
    }

    private List<ProviderArgumentModel.Argument> arguments(ProviderMethodResolver.Method provider, TemplateSqlInput input,
                                                          JavaSootMethod mapper, JavaView view) {
        int ordinary = provider.method().getParameterCount() - (provider.contextIndex() < 0 ? 0 : 1);
        boolean named = input.parameterMapName().isPresent();
        if (!named && ordinary > 1) throw new IllegalArgumentException("Invalid MyBatis Provider argument combination for " + mapper.getSignature());
        var result = new ArrayList<ProviderArgumentModel.Argument>();
        for (int i = 0; i < provider.method().getParameterCount(); i++) {
            if (i == provider.contextIndex()) { result.add(new ProviderArgumentModel.Argument.Context()); continue; }
            String type = provider.method().getParameterType(i).toString();
            if (named && ordinary == 1 && accepts(type, "org.apache.ibatis.binding.MapperMethod$ParamMap", view)) {
                result.add(new ProviderArgumentModel.Argument.Named(input.parameters())); continue;
            }
            TemplateSqlInput.Parameter value;
            if (named) {
                String name = provider.argumentNames().get(i); value = input.parameters().get(name);
                if (value == null) throw new IllegalArgumentException("Missing MyBatis Provider named argument: " + name);
            } else value = input.parameters().getOrDefault("_parameter", nullParameter(mapper));
            if (!accepts(type, value.type(), view))
                throw new IllegalArgumentException("Incompatible MyBatis Provider argument type " + value.type() + " for " + type);
            result.add(new ProviderArgumentModel.Argument.Value(value));
        }
        return List.copyOf(result);
    }

    private static boolean accepts(String target, String source, JavaView view) {
        if (source.equals("null")) return !Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double").contains(target);
        String boxedSource = boxed(source), boxedTarget = boxed(target);
        String primitiveSource = primitive(boxedSource);
        if (!primitiveSource.isEmpty() && !target.contains(".")) {
            if (target.equals(primitiveSource)) return true;
            if (switch (primitiveSource) {
                case "byte" -> Set.of("short", "int", "long", "float", "double").contains(target);
                case "short", "char" -> Set.of("int", "long", "float", "double").contains(target);
                case "int" -> Set.of("long", "float", "double").contains(target);
                case "long" -> Set.of("float", "double").contains(target);
                case "float" -> target.equals("double"); default -> false;
            }) return true;
        }
        return boxedTarget.equals("java.lang.Object") || boxedTarget.equals(boxedSource)
                || MapperParameterResolver.subtype(boxedSource, boxedTarget, view);
    }
    private static String primitive(String type) {
        return switch (type) {
            case "java.lang.Boolean" -> "boolean"; case "java.lang.Byte" -> "byte"; case "java.lang.Character" -> "char";
            case "java.lang.Short" -> "short"; case "java.lang.Integer" -> "int"; case "java.lang.Long" -> "long";
            case "java.lang.Float" -> "float"; case "java.lang.Double" -> "double"; default -> "";
        };
    }
    private static String boxed(String type) {
        return switch (type) {
            case "boolean" -> "java.lang.Boolean"; case "byte" -> "java.lang.Byte"; case "char" -> "java.lang.Character";
            case "short" -> "java.lang.Short"; case "int" -> "java.lang.Integer"; case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float"; case "double" -> "java.lang.Double"; default -> type;
        };
    }
    private static TemplateSqlInput.Parameter symbolic(JavaSootMethod mapper, int index) {
        String type = mapper.getParameterType(index).toString();
        var variable = new Variable(mapper.getSignature(), -1, "@parameter" + index + ": " + type, type);
        return new TemplateSqlInput.Parameter(new ValueTrace(variable, List.of(), new Node(variable, Kind.NO_CALLER,
                "Symbolic Mapper parameter", List.of())), type);
    }
    private static TemplateSqlInput.Parameter nullParameter(JavaSootMethod mapper) {
        var variable = new Variable(mapper.getSignature(), -1, "provider-null-parameter", "null");
        return new TemplateSqlInput.Parameter(new ValueTrace(variable, List.of(), new Node(variable, Kind.CONSTANT,
                "MyBatis null parameter object", List.of(), new Computation(Operation.NULL, "null"))), "null");
    }
    private static Map<String, String> metadata(SqlDefinition base, ProviderMethodResolver.Method provider) {
        var metadata = new LinkedHashMap<>(base.metadata());
        metadata.put("providerType", provider.providerType().toString());
        metadata.put("providerMethod", provider.method().getName());
        metadata.put("providerSignature", provider.method().getSignature().toString());
        return metadata;
    }
    private static ValueFlowModel values(ProviderArgumentModel arguments, DataFlowAnalysis flow) {
        var builder = new MybatisSqlBuilderModel(flow);
        return new ValueFlowModel() {
            public Optional<Node> resolve(FlowPoint.ValuePoint input, java.util.function.Function<FlowPoint.ValuePoint, Node> dependency) {
                return arguments.resolve(input, dependency).or(() -> builder.resolve(input, dependency));
            }
            public Optional<JavaSootMethod> invocation(InvocationSite site, CallContext context) { return arguments.invocation(site, context); }
        };
    }
    private static SqlDefinition failure(SqlDefinition base, IllegalArgumentException error) {
        var issues = new ArrayList<>(base.issues()); issues.add(error.getMessage());
        return new SqlDefinition(base.id(), base.location(), base.template(), base.evaluator(), base.metadata(), issues);
    }
}
