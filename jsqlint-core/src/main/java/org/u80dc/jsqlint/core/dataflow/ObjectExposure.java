package org.u80dc.jsqlint.core.dataflow;

import java.util.List;

/** 框架值模型共享的不可变对象逃逸历史，复用 core 的容器别名与调用顺序检查。 @author u80dc */
public final class ObjectExposure {
    public static final ObjectExposure EMPTY = new ObjectExposure(HeapExposure.EMPTY);
    private final HeapExposure history;
    private ObjectExposure(HeapExposure history) { this.history = history; }
    public ObjectExposure before(DataFlowAnalysis flow, FlowPoint point, DataFlowAnalysis.Origins tracked, boolean unmodeledCall) {
        return new ObjectExposure(history.before(flow, point, tracked, unmodeledCall));
    }
    public List<String> issues() { return history.issues(); }
}
