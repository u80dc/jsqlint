package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.resource.AnalysisResource;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.dataflow.ProgramIndex;
import org.u80dc.jsqlint.core.semantics.JavaValueOperations;
import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.net.URI;
import java.util.*;
import java.util.function.*;

import static org.u80dc.jsqlint.core.AnalysisListener.Stage;

/** 一次注册与共享扫描，产生待分析起点和调用索引，不在发现阶段求值。 @author u80dc */
final class SqlDiscovery {
    private final Runnable checkpoint;
    private final AnalysisErrorHandler errors;
    private final Consumer<Progress> updates;
    private final BiConsumer<String, SqlCallSite> callSites;
    private final StatisticsRecorder statisticsRecorder;
    private final List<Registered> modules = new ArrayList<>();
    private final List<Pending> seeds = new ArrayList<>();
    private final List<ExtractionRequest> extractions = new ArrayList<>();
    private long resources, methods;

    SqlDiscovery(Runnable checkpoint, AnalysisErrorHandler errors, Consumer<Progress> updates,
                 BiConsumer<String, SqlCallSite> callSites) {
        this(checkpoint, errors, updates, callSites, StatisticsRecorder.none());
    }
    SqlDiscovery(Runnable checkpoint, AnalysisErrorHandler errors, Consumer<Progress> updates,
                 BiConsumer<String, SqlCallSite> callSites, StatisticsRecorder statisticsRecorder) {
        this.checkpoint = checkpoint;
        this.errors = errors;
        this.updates = updates;
        this.callSites = callSites;
        this.statisticsRecorder = Objects.requireNonNull(statisticsRecorder);
    }

    Plan discover(AnalysisRequest request, List<SqlAnalysisModule> extensions, Optional<MethodSignature> entry,
                  int maxCallDepth, BiConsumer<String, String> incomplete) throws Exception {
        try (var timing = statisticsRecorder.measure(AnalysisStatistics.Phase.DISCOVERY)) {
            return discoverPlan(request, extensions, entry, maxCallDepth, incomplete);
        }
    }
    private Plan discoverPlan(AnalysisRequest request, List<SqlAnalysisModule> extensions, Optional<MethodSignature> entry,
                              int maxCallDepth, BiConsumer<String, String> incomplete) throws Exception {
        checkpoint.run();
        // 每次重新注册，允许模块建立本次独占的处理器和跨资源定义索引。
        for (var module : extensions) {
            var registry = new DiscoveryRegistry();
            module.register(registry);
            modules.add(new Registered(module.id(), registry.resources(), registry.methods(), registry.invocations(),
                    registry.sqlMethods(), registry.sqlInvocations(), registry.extractions(), registry.definitions(),
                    registry.methodDefinitions(), registry.isCallIndexRequired()));
        }
        JavaView view = new JavaView(new ArrayList<>(request.locations()));
        progress(Stage.RESOURCES);
        Set<URI> visitedResources = new HashSet<>();
        if (modules.stream().anyMatch(module -> !module.resources().isEmpty())) {
            for (var source : request.resources()) {
                checkpoint.run();
                try (var stream = source.resources()) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        checkpoint.run();
                        AnalysisResource resource = iterator.next();
                        if (!visitedResources.add(resource.uri())) continue;
                        resources++;
                        for (Registered module : modules) dispatch(module.id(), module.resources(), resource,
                                seed -> { if (entry.isEmpty()) accept(module.id(), seed); });
                        progress(Stage.RESOURCES);
                    }
                } catch (Exception e) { errors.recover("RESOURCE_DISCOVERY", "Cannot enumerate resources", e); }
            }
        }
        progress(Stage.CODE);
        boolean codeRequested = entry.isPresent() || modules.stream().anyMatch(module ->
                !module.methods().isEmpty() || !module.sqlMethods().isEmpty()
                        || !module.sqlInvocations().isEmpty() || module.callIndexRequired());
        boolean callIndexRequired = entry.isPresent() || modules.stream().anyMatch(Registered::callIndexRequired);
        boolean invocationsRequested = callIndexRequired
                || modules.stream().anyMatch(module -> !module.sqlInvocations().isEmpty());
        List<JavaSootClass> classes = new ArrayList<>();
        List<InvocationSite> calls = new ArrayList<>();
        List<JavaSootMethod> declarations = new ArrayList<>();
        Map<MethodSignature, List<Pending>> methodSeeds = new LinkedHashMap<>();
        Set<Object> visitedClasses = new HashSet<>();
        if (codeRequested) for (var location : request.locations()) {
            checkpoint.run();
            if (location.getSourceType() == SourceType.Library) continue;
            try (var sources = location.getClassSources(view)) {
                var iterator = sources.iterator();
                while (iterator.hasNext()) {
                    checkpoint.run();
                    var source = iterator.next();
                    if (source.getAnalysisInputLocation().getSourceType() == SourceType.Library
                            || !visitedClasses.add(source.getClassType())) continue;
                    try {
                        var clazz = view.getClass(source.getClassType()).orElseThrow();
                        if (!clazz.isApplicationClass()) continue;
                        classes.add(clazz);
                        for (var method : clazz.getMethods()) {
                            checkpoint.run();
                            methods++;
                            declarations.add(method);
                            if (entry.isEmpty()) reportDeclaration(method, view);
                            for (Registered module : modules) dispatch(module.id(), module.methods(), method,
                                    seed -> {
                                        if (entry.isEmpty()) accept(module.id(), seed);
                                        else methodSeeds.computeIfAbsent(method.getSignature(), ignored -> new ArrayList<>())
                                                .add(new Pending(module.id(), seed));
                                    });
                            // 元数据规则不加载方法体；调用规则和调用者索引共享同一次 Jimple 扫描。
                            if (invocationsRequested && method.hasBody()) {
                                try {
                                    var statements = method.getBody().getStmts();
                                    for (int i = 0; i < statements.size(); i++) {
                                        checkpoint.run();
                                        var statement = statements.get(i);
                                        if (!statement.isInvokableStmt()) continue;
                                        var invocation = statement.asInvokableStmt().getInvokeExpr();
                                        if (invocation.isEmpty()) continue;
                                        var site = new InvocationSite(method, statement, i, invocation.get(), view);
                                        calls.add(site);
                                        if (entry.isEmpty()) dispatchInvocation(site);
                                    }
                                } catch (Exception e) { errors.recover("METHOD_BODY", method.getSignature().toString(), e); }
                            }
                            progress(Stage.CODE);
                        }
                    } catch (Exception e) { errors.recover("CLASS_DISCOVERY", source.getClassType().toString(), e); }
                }
            } catch (Exception e) { errors.recover("CODE_DISCOVERY", "Cannot enumerate application classes", e); }
        }
        var program = new ProgramIndex(view, classes, callIndexRequired ? calls : List.of(), checkpoint, statisticsRecorder);
        Optional<JavaSootMethod> root = entry.map(signature -> view.getMethod(signature)
                .or(() -> program.resolveMethod(signature.getDeclClassType(), signature.getSubSignature()))
                .filter(method -> method.getDeclClassType().equals(signature.getDeclClassType())
                        || !method.isPrivate() && !Set.of("<init>", "<clinit>").contains(method.getName())
                            && (method.isPublic() || method.isProtected()
                                || method.getDeclClassType().getPackageName().equals(signature.getDeclClassType().getPackageName())))
                .orElseThrow(() -> new IllegalArgumentException("Analysis entry method does not exist: " + signature)));
        root.ifPresent(method -> { if (!declarations.contains(method)) declarations.add(method); });
        // 框架也可直接建模依赖库中的 default 入口；命中后无需遍历其实现体。
        if (root.filter(JavaSootMethod::hasBody).filter(method -> classes.stream()
                .noneMatch(clazz -> clazz.getType().equals(method.getDeclClassType()))).isPresent()) {
            for (Registered module : modules) for (var handler : module.methodDefinitions()) {
                checkpoint.run();
                try { handler.discover(entry.orElseThrow(), root.get(), view, seed -> accept(module.id(), seed)); }
                catch (Exception e) { errors.recover("METHOD_SQL_DEFINITION", entry.get().toString(), e); }
            }
        }
        if (root.isPresent() && !seeds.isEmpty()) {
            progress(Stage.CODE);
            return new Plan(program, root.map(JavaSootMethod::getSignature), Set.of(root.orElseThrow().getSignature()), seeds, extractions);
        }
        root.filter(JavaSootMethod::hasBody).filter(method -> classes.stream()
                .noneMatch(clazz -> clazz.getType().equals(method.getDeclClassType()))).ifPresent(method ->
                incomplete.accept("METHOD_SCOPE_EXTERNAL_ENTRY", "Entry body is outside the application code index: " + entry.orElseThrow()));
        Set<MethodSignature> selected = root.map(method -> program.reachableMethods(method, maxCallDepth, incomplete)).orElse(Set.of());
        if (entry.isPresent()) methodSeeds.forEach((signature, values) -> {
            if (selected.contains(signature)) values.forEach(value -> accept(value.moduleId(), value.seed()));
        });
        if (entry.isPresent()) for (var method : declarations) {
            if (selected.contains(method.getSignature())) reportDeclaration(method, view);
        }
        if (entry.isPresent()) for (var site : calls) {
            checkpoint.run();
            if (!selected.contains(site.method().getSignature())) continue;
            boolean modeledSql = dispatchInvocation(site);
            if (!modeledSql && program.resolution(site).hasExternalTargets()
                    && !JavaValueOperations.scalarStringConcat(site.invocation())
                    && !JavaValueOperations.supports(site.invocation().getMethodSignature())) {
                String owner = site.invocation().getMethodSignature().getDeclClassType().toString();
                // 运行时基础库不会作为应用 SQL 方法体遍历；未知项目/第三方调用不能证明没有 SQL。
                if (!owner.startsWith("java.") && !owner.startsWith("javax.") && !owner.startsWith("jdk.")
                        || owner.startsWith("java.lang.reflect.") || owner.equals("java.lang.invoke.MethodHandle"))
                    incomplete.accept("METHOD_SCOPE_UNRESOLVED", "Unresolved downstream call from " + entry.get() + ": " + site.invocation().getMethodSignature());
            }
        }
        if (root.isPresent() && !root.get().hasBody() && seeds.isEmpty()) {
            boolean definitionFailed = false;
            for (Registered module : modules) for (var handler : module.methodDefinitions()) {
                checkpoint.run();
                try { handler.discover(entry.orElseThrow(), root.get(), view, seed -> accept(module.id(), seed)); }
                catch (Exception e) { definitionFailed = true; errors.recover("METHOD_SQL_DEFINITION", entry.get().toString(), e); }
            }
            if (seeds.isEmpty() && !definitionFailed)
                incomplete.accept("METHOD_SQL_DEFINITION_UNAVAILABLE", "No SQL definition could be associated with " + entry.orElseThrow());
        }
        if (entry.isEmpty()) for (Registered module : modules) for (var handler : module.definitions()) {
            checkpoint.run();
            try { handler.discover(seed -> accept(module.id(), seed)); }
            catch (Exception e) { errors.recover("MODULE_DEFINITIONS", module.id(), e); }
        }
        progress(Stage.CODE);
        return new Plan(program, root.map(JavaSootMethod::getSignature), selected, seeds, extractions);
    }

    private void progress(Stage stage) {
        updates.accept(new Progress(stage, resources, methods, seeds.size()));
    }

    private void reportDeclaration(JavaSootMethod method, JavaView view) {
        for (Registered module : modules) if (matches(module.id(), module.sqlMethods(), rule -> rule.test(method, view)))
            callSites.accept(module.id(), SqlCallSite.declaration(method));
    }

    /** 全程序模式在共享扫描时分发，方法范围模式在范围确定后分发；两者共用同一组规则。 */
    private boolean dispatchInvocation(InvocationSite site) {
        boolean modeledSql = false;
        for (Registered module : modules) {
            if (matches(module.id(), module.sqlInvocations(), rule -> rule.test(site))) {
                modeledSql = true;
                callSites.accept(module.id(), SqlCallSite.invocation(site));
            }
        }
        for (Registered module : modules) dispatch(module.id(), module.invocations(), site);
        for (Registered module : modules) for (var rule : module.extractions()) {
            if (matches(module.id(), List.of(rule), candidate -> candidate.matches().test(site))) {
                modeledSql = true;
                extractions.add(new ExtractionRequest(module.id(), site, rule.handler()));
            }
        }
        return modeledSql;
    }

    /** 同一模块的重叠规则取并集；单条规则失败不阻止其他规则。 */
    private <T> boolean matches(String moduleId, List<T> rules, Predicate<T> test) {
        for (T rule : rules) {
            checkpoint.run();
            try { if (test.test(rule)) return true; }
            catch (Exception e) { errors.recover("SQL_CALL_SITE_DISCOVERY", moduleId, e); }
        }
        return false;
    }

    private <T> void dispatch(String moduleId, List<DiscoveryRegistry.Rule<T>> rules, T input) {
        dispatch(moduleId, rules, input, seed -> accept(moduleId, seed));
    }
    private <T> void dispatch(String moduleId, List<DiscoveryRegistry.Rule<T>> rules, T input, Consumer<AnalysisSeed> sink) {
        for (var rule : rules) {
            checkpoint.run();
            try {
                if (rule.matches().test(input)) rule.handler().discover(input, sink);
            } catch (Exception e) { errors.recover("MODULE_DISCOVERY", moduleId + ": " + input, e); }
        }
    }

    private void accept(String moduleId, AnalysisSeed seed) {
        checkpoint.run();
        Objects.requireNonNull(seed, "seed"); Objects.requireNonNull(seed.location(), "seed.location");
        seeds.add(new Pending(moduleId, seed));
    }

    record Progress(Stage stage, long resources, long methods, long candidates) {}
    record Pending(String moduleId, AnalysisSeed seed) {}
    record ExtractionRequest(String moduleId, InvocationSite site, SqlExtractionHandler handler) {}
    record Plan(ProgramIndex program, Optional<MethodSignature> entry, Set<MethodSignature> methods,
                List<Pending> seeds, List<ExtractionRequest> extractions) {
        Plan {
            methods = Set.copyOf(methods);
            seeds = List.copyOf(seeds); extractions = List.copyOf(extractions);
        }
    }
    private record Registered(String id, List<DiscoveryRegistry.Rule<AnalysisResource>> resources,
                              List<DiscoveryRegistry.Rule<JavaSootMethod>> methods,
                              List<DiscoveryRegistry.Rule<InvocationSite>> invocations,
                              List<BiPredicate<JavaSootMethod, JavaView>> sqlMethods,
                              List<Predicate<InvocationSite>> sqlInvocations,
                              List<DiscoveryRegistry.ExtractionRule> extractions,
                              List<DiscoveryRegistry.DefinitionHandler> definitions,
                              List<DiscoveryRegistry.MethodDefinitionHandler> methodDefinitions, boolean callIndexRequired) {}
}
