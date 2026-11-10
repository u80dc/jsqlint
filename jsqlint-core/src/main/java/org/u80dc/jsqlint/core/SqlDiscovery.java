package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.resource.AnalysisResource;
import org.u80dc.jsqlint.core.spi.*;
import sootup.core.model.SourceType;
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
    private final List<Registered> modules = new ArrayList<>();
    private final List<Pending> seeds = new ArrayList<>();
    private final List<ExtractionRequest> extractions = new ArrayList<>();
    private long resources, methods;

    SqlDiscovery(Runnable checkpoint, AnalysisErrorHandler errors, Consumer<Progress> updates,
                 BiConsumer<String, SqlCallSite> callSites) {
        this.checkpoint = checkpoint;
        this.errors = errors;
        this.updates = updates;
        this.callSites = callSites;
    }

    Plan discover(AnalysisRequest request, List<SqlAnalysisModule> extensions) throws Exception {
        checkpoint.run();
        // 每次重新注册，允许模块建立本次独占的处理器和跨资源定义索引。
        for (var module : extensions) {
            var registry = new DiscoveryRegistry();
            module.register(registry);
            modules.add(new Registered(module.id(), registry.resources(), registry.methods(), registry.invocations(),
                    registry.sqlMethods(), registry.sqlInvocations(), registry.extractions(), registry.definitions(), registry.isCallIndexRequired()));
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
                        for (Registered module : modules) dispatch(module.id(), module.resources(), resource);
                        progress(Stage.RESOURCES);
                    }
                } catch (Exception e) { errors.recover("RESOURCE_DISCOVERY", "Cannot enumerate resources", e); }
            }
        }
        progress(Stage.CODE);
        boolean codeRequested = modules.stream().anyMatch(module ->
                !module.methods().isEmpty() || !module.sqlMethods().isEmpty()
                        || !module.sqlInvocations().isEmpty() || module.callIndexRequired());
        boolean callIndexRequired = modules.stream().anyMatch(Registered::callIndexRequired);
        boolean invocationsRequested = callIndexRequired
                || modules.stream().anyMatch(module -> !module.sqlInvocations().isEmpty());
        List<JavaSootClass> classes = new ArrayList<>();
        List<InvocationSite> calls = new ArrayList<>();
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
                            for (Registered module : modules) {
                                if (matches(module.id(), module.sqlMethods(), rule -> rule.test(method, view)))
                                    callSites.accept(module.id(), SqlCallSite.declaration(method));
                            }
                            for (Registered module : modules) dispatch(module.id(), module.methods(), method);
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
                                        if (callIndexRequired) calls.add(site);
                                        for (Registered module : modules) {
                                            if (matches(module.id(), module.sqlInvocations(), rule -> rule.test(site)))
                                                callSites.accept(module.id(), SqlCallSite.invocation(site));
                                        }
                                        for (Registered module : modules) dispatch(module.id(), module.invocations(), site);
                                        for (Registered module : modules) for (var rule : module.extractions()) {
                                            if (matches(module.id(), List.of(rule), candidate -> candidate.matches().test(site)))
                                                extractions.add(new ExtractionRequest(module.id(), site, rule.handler()));
                                        }
                                    }
                                } catch (Exception e) { errors.recover("METHOD_BODY", method.getSignature().toString(), e); }
                            }
                            progress(Stage.CODE);
                        }
                    } catch (Exception e) { errors.recover("CLASS_DISCOVERY", source.getClassType().toString(), e); }
                }
            } catch (Exception e) { errors.recover("CODE_DISCOVERY", "Cannot enumerate application classes", e); }
        }
        for (Registered module : modules) for (var handler : module.definitions()) {
            checkpoint.run();
            try { handler.discover(seed -> accept(module.id(), seed)); }
            catch (Exception e) { errors.recover("MODULE_DEFINITIONS", module.id(), e); }
        }
        progress(Stage.CODE);
        return new Plan(view, classes, calls, seeds, extractions);
    }

    private void progress(Stage stage) {
        updates.accept(new Progress(stage, resources, methods, seeds.size()));
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
        for (var rule : rules) {
            checkpoint.run();
            try {
                if (rule.matches().test(input)) rule.handler().discover(input, seed -> accept(moduleId, seed));
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
    record Plan(JavaView view, List<JavaSootClass> classes, List<InvocationSite> calls,
                List<Pending> seeds, List<ExtractionRequest> extractions) {
        Plan {
            classes = List.copyOf(classes); calls = List.copyOf(calls);
            seeds = List.copyOf(seeds); extractions = List.copyOf(extractions);
        }
    }
    private record Registered(String id, List<DiscoveryRegistry.Rule<AnalysisResource>> resources,
                              List<DiscoveryRegistry.Rule<JavaSootMethod>> methods,
                              List<DiscoveryRegistry.Rule<InvocationSite>> invocations,
                              List<BiPredicate<JavaSootMethod, JavaView>> sqlMethods,
                              List<Predicate<InvocationSite>> sqlInvocations,
                              List<DiscoveryRegistry.ExtractionRule> extractions,
                              List<DiscoveryRegistry.DefinitionHandler> definitions, boolean callIndexRequired) {}
}
