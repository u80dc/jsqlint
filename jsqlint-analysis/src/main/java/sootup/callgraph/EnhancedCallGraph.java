package sootup.callgraph;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootUtils;
import sootup.java.core.views.JavaView;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 调用图，对 SootUp 原有{@link CallGraph}进行增强
 *
 * @author zhangcs
 */
@Slf4j
public class EnhancedCallGraph {

    @Getter
    private final CallGraph cg;

    @SuppressWarnings("unused")
    private final JavaView view;

    private EnhancedCallGraph(JavaView view, CallGraph callGraph) {
        this.cg = callGraph;
        this.view = view;
    }

    @NonNull
    public static EnhancedCallGraph initialize(JavaView view) {
        CallGraphAlgorithm algorithm = new SpringClassHierarchyAnalysisAlgorithm(view);
        CallGraph cg = algorithm.initialize();
        return new EnhancedCallGraph(view, cg);
    }

    @NonNull
    public Set<MethodSignature> getMethodSignatures() {
        return cg.getMethodSignatures();
    }

    @NonNull
    public Set<MethodSignature> callSourcesTo(MethodSignature signature) {
        try {
            return cg.callSourcesTo(signature);
        } catch (IllegalArgumentException ignore) {
            // 调用图中没有目标方法，可能是无实现的接口方法或者依赖（SootUp默认不对依赖文件构建调用图）
            return findCallersOf(signature);
        }
    }

    /**
     * 查找调用点
     *
     * @param target 目标方法
     * @return 返回访问目标方法的所有调用点方法
     */
    private Set<MethodSignature> findCallersOf(MethodSignature target) {
        Set<MethodSignature> targetMethods = cg.getMethodSignatures().stream()
                .filter(sig -> JavaSootUtils.equals(sig, target))
                .collect(Collectors.toSet());

        if (log.isTraceEnabled()) {
            log.trace("Target: {}", target.getDeclClassType().getFullyQualifiedName()
                    + "#" + target.getSubSignature());
            log.trace("Found {} caller method(s)", targetMethods.size());
        }

        Set<MethodSignature> callers = new java.util.HashSet<>();
        for (MethodSignature targetSig : targetMethods) {
            Set<MethodSignature> found = getCallersOfMethod(cg, targetSig);
            callers.addAll(found);
        }

        if (log.isTraceEnabled()) {
            log.trace("Caller methods: {}", callers.stream()
                    .map(c -> c.getDeclClassType().getFullyQualifiedName() + "#" + c.getSubSignature())
                    .collect(Collectors.joining(", ")));
        }
        return callers;
    }

    /**
     * 获取调用指定方法的源方法
     */
    private Set<MethodSignature> getCallersOfMethod(CallGraph cg, MethodSignature targetSig) {
        return cg.getMethodSignatures().stream()
                .filter(srcSig -> {
                    Set<MethodSignature> targets = cg.callTargetsFrom(srcSig);
                    return targets.stream().anyMatch(tgt -> JavaSootUtils.equals(tgt, targetSig));
                })
                .collect(Collectors.toSet());
    }

}
