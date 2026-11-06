package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.dataflow.*;
import java.util.List;

/** 在全项目索引就绪后定位 SQL/参数变量；通用数据流查询由 core 提供。 @author u80dc */
@FunctionalInterface
public interface SqlExtractionHandler {
    List<SqlTraceSource> extract(FlowPoint call, DataFlowAnalysis analysis);
}
