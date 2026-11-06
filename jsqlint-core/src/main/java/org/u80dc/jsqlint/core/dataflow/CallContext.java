package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.spi.InvocationSite;
import org.u80dc.jsqlint.core.spi.SqlCallSite;
import java.util.*;

/** 从最外层入口到当前方法的调用栈；不同调用位置的实参不共用上下文。 @author u80dc */
public record CallContext(List<InvocationSite> calls) {
    public static final CallContext EMPTY = new CallContext(List.of());
    public CallContext { calls = List.copyOf(calls); }
    public CallContext push(InvocationSite call) {
        var copy = new ArrayList<>(calls); copy.add(call); return new CallContext(copy);
    }
    public CallContext parent() { return new CallContext(calls.subList(0, calls.size() - 1)); }
    public List<SqlCallSite> snapshot() { return calls.stream().map(SqlCallSite::invocation).toList(); }
}
