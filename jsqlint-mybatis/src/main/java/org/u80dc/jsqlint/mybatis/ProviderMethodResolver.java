package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.bytecode.MethodParameterMetadata;
import sootup.core.jimple.common.constant.ClassConstant;
import sootup.core.signatures.MethodSubSignature;
import sootup.core.types.ClassType;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;
import java.util.*;

/** 按 MyBatis ProviderSqlSource 的规则选择公开方法；只读取字节码，不执行自定义方法解析器。 @author u80dc */
final class ProviderMethodResolver {
    private final MybatisOptions options;
    private final MapperParameterResolver parameters;
    ProviderMethodResolver(MybatisOptions options) { this.options = options; parameters = new MapperParameterResolver(options); }

    record Method(JavaSootMethod method, List<String> argumentNames, int contextIndex, ClassType providerType) {
        Method { argumentNames = List.copyOf(argumentNames); }
    }

    Method resolve(SqlDefinition definition, JavaSootMethod mapper, JavaView view) {
        String type = definition.metadata().getOrDefault("providerType", "");
        if (type.isEmpty()) type = options.defaultSqlProviderType().orElseThrow(() ->
                new IllegalArgumentException("MyBatis Provider type is missing and no defaultSqlProviderType is configured"));
        var classType = view.getIdentifierFactory().getClassType(type);
        var provider = view.getClass(classType).orElseThrow(() -> new IllegalArgumentException("MyBatis Provider class is unavailable: " + classType));
        List<JavaSootMethod> methods = publicMethods(classType, view);
        String name = definition.metadata().getOrDefault("providerMethod", "");
        if (name.isEmpty()) {
            boolean resolver = MapperParameterResolver.subtype(type, "org.apache.ibatis.builder.annotation.ProviderMethodResolver", view);
            if (resolver) {
                if (methods.stream().anyMatch(method -> method.getName().equals("resolveMethod")
                        && method.getParameterTypes().stream().map(Object::toString).toList().equals(List.of("org.apache.ibatis.builder.annotation.ProviderContext"))
                        && !method.getDeclClassType().toString().equals("org.apache.ibatis.builder.annotation.ProviderMethodResolver")))
                    throw new IllegalArgumentException("Custom MyBatis ProviderMethodResolver cannot be executed during static analysis: " + type);
                var matching = candidates(methods, mapper.getName(), view);
                if (matching.size() != 1) throw new IllegalArgumentException("Default MyBatis ProviderMethodResolver requires one CharSequence method named " + mapper.getName());
                if (provider.isAbstract() || methods.stream().noneMatch(method -> method.getName().equals("<init>") && method.getParameterCount() == 0))
                    throw new IllegalArgumentException("MyBatis ProviderMethodResolver requires a public no-argument constructor: " + type);
                name = mapper.getName();
            }
            if (name.isEmpty()) name = "provideSql";
        }
        var matching = candidates(methods, name, view);
        if (matching.isEmpty()) throw new IllegalArgumentException("No public CharSequence MyBatis Provider method: " + type + "." + name);
        if (matching.size() > 1) throw new IllegalArgumentException("Ambiguous overloaded MyBatis Provider method: " + type + "." + name);
        var selected = matching.getFirst();
        if (!selected.hasBody()) throw new IllegalArgumentException("MyBatis Provider method body is unavailable: " + selected.getSignature());
        if (!selected.isStatic()) {
            if (provider.isAbstract() || methods.stream().noneMatch(method -> method.getName().equals("<init>") && method.getParameterCount() == 0))
                throw new IllegalArgumentException("MyBatis Provider instance requires a public no-argument constructor: " + type);
        }
        List<String> issues = new ArrayList<>();
        MethodParameterMetadata info = parameters.parameters(selected, view, issues);
        if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("; ", issues));
        List<String> names = new ArrayList<>(); int context = -1;
        for (int i = 0; i < selected.getParameterCount(); i++) {
            String argument = options.useActualParamName() ? info.parameters().get(i).name().orElse("arg" + i) : Integer.toString(i);
            for (var annotation : info.parameters().get(i).annotations()) if (annotation.type().equals("org.apache.ibatis.annotations.Param"))
                argument = MybatisDefinitionIndex.string(annotation.values().get("value"), argument);
            names.add(argument);
            String parameterType = selected.getParameterType(i).toString();
            if (MapperParameterResolver.subtype(parameterType, "org.apache.ibatis.session.RowBounds", view)
                    || MapperParameterResolver.subtype(parameterType, "org.apache.ibatis.session.ResultHandler", view))
                throw new IllegalArgumentException("MyBatis special Mapper parameters cannot be used as Provider arguments: " + parameterType);
            if (selected.getParameterType(i).toString().equals("org.apache.ibatis.builder.annotation.ProviderContext")) {
                if (context >= 0) throw new IllegalArgumentException("MyBatis Provider declares multiple ProviderContext parameters");
                context = i;
            }
        }
        return new Method(selected, names, context, classType);
    }

    private static List<JavaSootMethod> candidates(List<JavaSootMethod> methods, String name, JavaView view) {
        return methods.stream().filter(method -> method.getName().equals(name) && !method.isAbstract()
                && MapperParameterResolver.subtype(method.getReturnType().toString(), "java.lang.CharSequence", view)).toList();
    }
    private static List<JavaSootMethod> publicMethods(ClassType type, JavaView view) {
        var result = new LinkedHashMap<MethodSubSignature, JavaSootMethod>();
        var pending = new ArrayDeque<ClassType>(); pending.add(type); var visited = new HashSet<ClassType>();
        while (!pending.isEmpty()) {
            var next = pending.removeFirst(); if (!visited.add(next)) continue;
            if (visited.size() > 256) throw new IllegalArgumentException("MyBatis Provider hierarchy budget exceeded");
            var clazz = view.getClass(next); if (clazz.isEmpty()) continue;
            for (var method : clazz.get().getMethods()) if (method.isPublic() && !(clazz.get().isInterface() && !next.equals(type) && method.isStatic())
                    && (!method.getName().equals("<init>") || next.equals(type))) result.putIfAbsent(method.getSignature().getSubSignature(), method);
            clazz.get().getSuperclass().ifPresent(pending::add); pending.addAll(clazz.get().getInterfaces());
        }
        return List.copyOf(result.values());
    }
    static String type(Object value) {
        if (!(value instanceof ClassConstant constant)) return "";
        String name = constant.getValue();
        if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
        name = name.replace('/', '.');
        return name.equals("void") || name.equals("V") ? "" : name;
    }
}
