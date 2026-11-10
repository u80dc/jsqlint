package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.bytecode.MethodParameterMetadata;
import org.u80dc.jsqlint.core.dataflow.FlowPoint;
import org.u80dc.jsqlint.core.spi.*;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.util.*;
import java.util.function.IntFunction;

/** MyBatis ParamNameResolver 规则与通用 ValuePoint 之间的适配，不承担 Java 值传播。 @author u80dc */
final class MapperParameterResolver {
    private static final Set<String> SCALARS = Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double",
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal", "java.math.BigInteger");
    private final MybatisOptions options;
    private final Map<MethodSignature, MethodParameterMetadata> metadata = new HashMap<>();
    MapperParameterResolver(MybatisOptions options) { this.options = options; }

    SqlTraceSource resolve(SqlDefinition definition, JavaSootMethod method, InvocationSite site, FlowPoint point) {
        return resolve(definition, method, site.view(), index -> new TemplateSqlInput.Parameter(
                point.value(site.invocation().getArg(index)), method.getParameterType(index).toString()));
    }
    SqlTraceSource resolve(SqlDefinition definition, JavaSootMethod method, JavaView view,
                           IntFunction<TemplateSqlInput.Parameter> values) {
        List<String> issues = new ArrayList<>();
        var info = parameters(method, view, issues);
        List<Argument> arguments = new ArrayList<>();
        boolean annotated = false;
        for (int i = 0; i < method.getParameterCount(); i++) {
            String type = method.getParameterType(i).toString();
            if (subtype(type, "org.apache.ibatis.session.RowBounds", view)
                    || subtype(type, "org.apache.ibatis.session.ResultHandler", view)) continue;
            String name = null;
            for (var annotation : info.parameters().get(i).annotations()) {
                if (annotation.type().equals("org.apache.ibatis.annotations.Param")) {
                    name = MybatisDefinitionIndex.string(annotation.values().get("value"), null);
                    annotated = true; break;
                }
            }
            if (name == null) name = options.useActualParamName() ? info.parameters().get(i).name().orElse("arg" + i)
                    : Integer.toString(arguments.size());
            arguments.add(new Argument(name, values.apply(i)));
        }
        Map<String, TemplateSqlInput.Parameter> aliases = new LinkedHashMap<>();
        Optional<TemplateSqlInput.Parameter> fallback = Optional.empty(); Optional<String> parameterMapName = Optional.empty();
        Map<String, Object> constants = new LinkedHashMap<>(); constants.put("_databaseId", options.databaseId().orElse(null));
        if (arguments.isEmpty()) constants.put("_parameter", null);
        else if (arguments.size() == 1 && !annotated) {
            var argument = arguments.getFirst();
            aliases.put("_parameter", argument.parameter());
            if (SCALARS.contains(argument.parameter().type())) {
                aliases.put("value", argument.parameter()); fallback = Optional.of(argument.parameter());
            } else issues.add("MyBatis object and collection parameter tracking is not supported: " + argument.parameter().type());
        } else {
            parameterMapName = Optional.of("_parameter");
            Set<String> named = new HashSet<>(); arguments.forEach(argument -> named.add(argument.name()));
            for (int i = 0; i < arguments.size(); i++) {
                var argument = arguments.get(i); aliases.put(argument.name(), argument.parameter());
                String generic = "param" + (i + 1);
                if (!named.contains(generic)) aliases.put(generic, argument.parameter());
                if (!SCALARS.contains(argument.parameter().type())) issues.add("MyBatis object and collection parameter tracking is not supported: " + argument.parameter().type());
            }
        }
        return SqlTraceSource.template(new TemplateSqlInput(definition, aliases, fallback, parameterMapName, constants), issues);
    }

    MethodParameterMetadata parameters(JavaSootMethod method, JavaView view, List<String> issues) {
        if (method.getParameterCount() == 0) return new MethodParameterMetadata(List.of());
        var cached = metadata.get(method.getSignature()); if (cached != null) return cached;
        try {
            var loaded = MethodParameterMetadata.read(method, view); metadata.put(method.getSignature(), loaded); return loaded;
        } catch (IOException | RuntimeException e) {
            issues.add("MyBatis parameter metadata is unavailable: " + e.getMessage());
            return new MethodParameterMetadata(Collections.nCopies(method.getParameterCount(), new MethodParameterMetadata.Parameter(Optional.empty(), List.of())));
        }
    }
    static boolean subtype(String name, String target, JavaView view) {
        if (name.equals(target)) return true;
        if (Set.of("void", "null", "boolean", "byte", "char", "short", "int", "long", "float", "double").contains(name)) return false;
        if (target.equals("java.lang.Object")) return true;
        if (target.equals("java.lang.CharSequence") && Set.of("java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer").contains(name)) return true;
        if (name.equals("org.apache.ibatis.binding.MapperMethod$ParamMap")
                && Set.of("java.util.Map", "java.util.HashMap", "java.util.AbstractMap", "java.io.Serializable", "java.lang.Cloneable").contains(target)) return true;
        if (name.endsWith("[]")) return Set.of("java.lang.Cloneable", "java.io.Serializable").contains(target);
        Deque<ClassType> pending = new ArrayDeque<>(); Set<ClassType> visited = new HashSet<>();
        pending.add(view.getIdentifierFactory().getClassType(name));
        while (!pending.isEmpty()) {
            var type = pending.removeFirst(); if (!visited.add(type)) continue;
            if (type.toString().equals(target)) return true;
            var clazz = view.getClass(type);
            if (clazz.isPresent()) { pending.addAll(clazz.get().getInterfaces()); clazz.get().getSuperclass().ifPresent(pending::add); }
        }
        return false;
    }
    private record Argument(String name, TemplateSqlInput.Parameter parameter) {}
}
