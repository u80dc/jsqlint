package org.u80dc.jsqlint.core.dataflow;

import java.util.Objects;

/** 框架状态回溯所选中的过程内边；联合分析期间使用，最终结果只保存独立的条件传播链。 @author u80dc */
public record ControlFlowEdge(FlowPoint from, FlowPoint to, boolean exceptional) {
    public ControlFlowEdge { Objects.requireNonNull(from); Objects.requireNonNull(to); }
}
