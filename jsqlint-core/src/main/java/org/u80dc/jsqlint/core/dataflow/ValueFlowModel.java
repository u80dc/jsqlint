package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.java.core.JavaSootMethod;
import java.util.Optional;
import java.util.function.Function;

/** 框架隐式调用或库值的语义适配；依赖通过回调展开，共用 core 的取消和溯源预算。 @author u80dc */
@FunctionalInterface
public interface ValueFlowModel {
    Optional<ValueTrace.Node> resolve(ValuePoint input, Function<ValuePoint, ValueTrace.Node> dependency);
    /** 框架已知接收者实际类型时，可精确选择目标；返回值与副作用仍由 core 追踪。 */
    default Optional<JavaSootMethod> invocation(InvocationSite site, CallContext context) { return Optional.empty(); }
}
