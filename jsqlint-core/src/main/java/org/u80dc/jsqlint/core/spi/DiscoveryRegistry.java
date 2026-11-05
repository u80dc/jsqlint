package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.resource.AnalysisResource;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 模块声明候选规则，由 core 一次遍历分发；方法处理器可检查注解，调用处理器共享方法体扫描。
 * 同一资源或调用允许多个模块命中。处理器必须在返回前同步发出起点，不应自行并发使用 SootUp。
 *
 * @author u80dc
 */
public final class DiscoveryRegistry {
    private final List<Rule<AnalysisResource>> resources = new ArrayList<>();
    private final List<Rule<JavaSootMethod>> methods = new ArrayList<>();
    private final List<Rule<InvocationSite>> invocations = new ArrayList<>();
    private boolean callIndexRequired;

    /**
     * 方法级处理器产生 ValueAt 起点时调用，以便共享扫描为跨方法溯源建立调用者索引。
     */
    public void requireCallIndex() {
        callIndexRequired = true;
    }

    public boolean isCallIndexRequired() {
        return callIndexRequired || !invocations.isEmpty();
    }

    /**
     * 按名称、扩展名等元数据筛选资源，处理器再检查内容是否符合框架语义。
     */
    public void resource(Predicate<AnalysisResource> selector, Handler<AnalysisResource> handler) {
        resources.add(new Rule<>(selector, handler));
    }

    /**
     * 方法级规则适合注解、签名等元数据发现，不会单独触发全量方法体加载。
     */
    public void method(Predicate<JavaSootMethod> selector, Handler<JavaSootMethod> handler) {
        methods.add(new Rule<>(selector, handler));
    }

    /**
     * 调用签名初筛后交给处理器判断；注册此规则会同时启用共享调用者索引。
     */
    public void invocation(Predicate<MethodSignature> selector, Handler<InvocationSite> handler) {
        Objects.requireNonNull(selector, "selector");
        invocations.add(new Rule<>(site -> selector.test(site.invocation().getMethodSignature()), handler));
    }

    public List<Rule<AnalysisResource>> resources() {
        return List.copyOf(resources);
    }

    public List<Rule<JavaSootMethod>> methods() {
        return List.copyOf(methods);
    }

    public List<Rule<InvocationSite>> invocations() {
        return List.copyOf(invocations);
    }

    @FunctionalInterface
    public interface Handler<T> {
        /**
         * 可同步发出零个或多个起点；发生异常时，已经发出的起点仍会保留。
         */
        void discover(T input, Consumer<AnalysisSeed> seeds) throws Exception;
    }

    public record Rule<T>(Predicate<T> matches, Handler<T> handler) {
        public Rule {
            Objects.requireNonNull(matches, "matches");
            Objects.requireNonNull(handler, "handler");
        }
    }
}
