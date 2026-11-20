package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.core.signatures.MethodSignature;
import sootup.core.signatures.MethodSubSignature;
import sootup.core.types.ClassType;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.util.*;

/**
 * 将 Mapper XML/SQL 注解绑定到接口方法；索引仅在本次分析内共享。
 * 直接从 JavaView 按需解析元数据，不依赖调用者与 Mapper 的扫描先后顺序。
 *
 * @author u80dc
 */
final class MapperMethodMatcher {
    private static final String ANNOTATION_PACKAGE = "org.apache.ibatis.annotations.";
    private static final Set<String> SQL_ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete",
            "SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider");
    private final Map<String, Set<String>> statements = new HashMap<>();
    private final Map<ClassType, Map<MethodSubSignature, JavaSootMethod>> interfaces = new HashMap<>();
    private final Map<MethodSignature, Boolean> calls = new HashMap<>();
    private Set<MethodSignature> xmlDeclarations;

    void addStatement(String namespace, String id) {
        if (!namespace.isBlank() && !id.isBlank())
            statements.computeIfAbsent(namespace, ignored -> new HashSet<>()).add(id);
    }

    boolean isSqlMethod(JavaSootMethod method, JavaView view) {
        if (!method.isAbstract()) return false;
        if (!view.getClass(method.getDeclClassType()).orElseThrow().isInterface()) return false;
        if (!sqlAnnotations(method).isEmpty()) return true;
        // core 先扫描全部资源再扫描代码，首次构建后不需要重新计算 XML 绑定。
        if (xmlDeclarations == null) {
            Set<MethodSignature> declarations = new HashSet<>();
            for (var entry : statements.entrySet()) {
                ClassType type = view.getIdentifierFactory().getClassType(entry.getKey());
                for (JavaSootMethod candidate : interfaceMethods(type, view).values()) {
                    if (candidate.isAbstract() && entry.getValue().contains(candidate.getName()))
                        declarations.add(candidate.getSignature());
                }
            }
            xmlDeclarations = declarations;
        }
        return xmlDeclarations.contains(method.getSignature());
    }

    boolean isSqlInvocation(InvocationSite site) {
        MethodSignature signature = site.invocation().getMethodSignature();
        return calls.computeIfAbsent(signature, ignored -> {
            JavaSootMethod method = interfaceMethods(signature.getDeclClassType(), site.view())
                    .get(signature.getSubSignature());
            // default/static 方法属于普通 Java 逻辑，即便名称与 XML id 相同也不直接执行映射 SQL。
            return method != null && method.isAbstract() && (!sqlAnnotations(method).isEmpty()
                    || hasXmlStatement(signature.getDeclClassType(), method, site.view(), new HashSet<>()));
        });
    }

    Optional<JavaSootMethod> resolveMethod(InvocationSite site) {
        var signature = site.invocation().getMethodSignature();
        var method = interfaceMethods(signature.getDeclClassType(), site.view()).get(signature.getSubSignature());
        return method != null && method.isAbstract() ? Optional.of(method) : Optional.empty();
    }

    Optional<JavaSootMethod> resolveMethod(MethodSignature signature, JavaView view) {
        return Optional.ofNullable(interfaceMethods(signature.getDeclClassType(), view).get(signature.getSubSignature()));
    }

    /** 依次尝试调用接口与真实声明所在的继承链，保持 MyBatis 的映射查找边界。 */
    List<String> definitionIds(InvocationSite site, JavaSootMethod method) {
        return definitionIds(site.invocation().getMethodSignature(), method, site.view());
    }
    /** 方法入口和真实调用共用继承映射规则；签名保留使用接口的 namespace。 */
    List<String> definitionIds(MethodSignature signature, JavaSootMethod method, JavaView view) {
        var result = new LinkedHashSet<String>();
        definitionIds(signature.getDeclClassType(), method, view, new HashSet<>(), result);
        return List.copyOf(result);
    }
    private void definitionIds(ClassType type, JavaSootMethod method, JavaView view, Set<ClassType> visited, Set<String> ids) {
        if (!visited.add(type)) return;
        ids.add(type + "." + method.getName());
        if (type.equals(method.getDeclClassType())) return;
        var clazz = view.getClass(type);
        if (clazz.isEmpty()) return;
        for (ClassType parent : clazz.get().getInterfaces()) {
            var inherited = interfaceMethods(parent, view).get(method.getSubSignature());
            if (inherited != null && inherited.getSignature().equals(method.getSignature()))
                definitionIds(parent, method, view, visited, ids);
        }
    }

    private boolean hasXmlStatement(ClassType type, JavaSootMethod method, JavaView view, Set<ClassType> visited) {
        if (!visited.add(type)) return false;
        if (statements.getOrDefault(type.toString(), Set.of()).contains(method.getName())) return true;
        // 对应 MyBatis 的继承查找边界：子接口重新声明的方法不会回退到被遮蔽的父方法映射。
        if (type.equals(method.getDeclClassType())) return false;
        var clazz = view.getClass(type);
        if (clazz.isEmpty()) return false;
        for (ClassType parent : clazz.get().getInterfaces()) {
            JavaSootMethod inherited = interfaceMethods(parent, view).get(method.getSubSignature());
            if (inherited != null && inherited.getSignature().equals(method.getSignature())
                    && hasXmlStatement(parent, method, view, visited)) return true;
        }
        return false;
    }

    /** 保留最近的声明，包括 default 覆盖，避免误用已被遮蔽的父接口 SQL 方法。 */
    private Map<MethodSubSignature, JavaSootMethod> interfaceMethods(ClassType type, JavaView view) {
        var cached = interfaces.get(type);
        if (cached != null) return cached;
        Map<MethodSubSignature, JavaSootMethod> methods = new LinkedHashMap<>();
        Deque<ClassType> pending = new ArrayDeque<>();
        Set<ClassType> visited = new HashSet<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            ClassType current = pending.removeFirst();
            if (!visited.add(current)) continue;
            var clazz = view.getClass(current);
            if (clazz.isEmpty() || !clazz.get().isInterface()) continue;
            for (JavaSootMethod method : clazz.get().getMethods()) {
                if (!method.isStatic() && !method.isPrivate()) methods.putIfAbsent(method.getSubSignature(), method);
            }
            pending.addAll(clazz.get().getInterfaces());
        }
        interfaces.put(type, methods);
        return methods;
    }

    /** 支持单个 SQL/Provider 注解及其可重复注解容器；不按任意 Provider 后缀猜测。 */
    static List<AnnotationUsage> sqlAnnotations(JavaSootMethod method) {
        List<AnnotationUsage> result = new ArrayList<>();
        for (AnnotationUsage annotation : method.getAnnotations()) {
            String type = annotation.getAnnotation().toString();
            if (!type.startsWith(ANNOTATION_PACKAGE)) continue;
            String name = type.substring(ANNOTATION_PACKAGE.length());
            if (SQL_ANNOTATIONS.contains(name)) result.add(annotation);
            else if (name.endsWith("$List") && SQL_ANNOTATIONS.contains(name.substring(0, name.length() - 5))) {
                Object value = annotation.getValues().get("value");
                if (value instanceof List<?> nested) for (Object item : nested) {
                    if (item instanceof AnnotationUsage usage
                            && usage.getAnnotation().toString().equals(type.substring(0, type.length() - 5)))
                        result.add(usage);
                }
            }
        }
        return result;
    }
}
