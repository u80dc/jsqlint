package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.statistics.StatisticsRecorder;
import java.util.Objects;

/** 输入准备期间的请求上下文；停止信号与统计作用域沿编译扩展传递，不写入持久输入。 */
public record AnalysisInputContext(Runnable checkpoint, StatisticsRecorder statisticsRecorder) {
    public AnalysisInputContext {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(statisticsRecorder, "statisticsRecorder");
    }
}
