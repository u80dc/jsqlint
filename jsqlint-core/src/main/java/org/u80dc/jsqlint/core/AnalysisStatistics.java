package org.u80dc.jsqlint.core;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/**
 * 一次分析结束时的不可变统计快照；成本类别与进度阶段独立。
 * totalNanos 包含子工作，selfNanos 仅包含本类别自身工作；概览应汇总 selfNanos。
 * measurements 保存带单位和归属范围的扩展测量，便于添加 CPU、内存及 GC 数据。
 */
public record AnalysisStatistics(long elapsedNanos, Map<Phase, PhaseCost> phases,
                                 Map<String, Measurement> measurements) {
    public AnalysisStatistics {
        if (elapsedNanos < 0) throw new IllegalArgumentException("Elapsed time must not be negative");
        Objects.requireNonNull(phases, "phases");
        var costs = new EnumMap<Phase, PhaseCost>(Phase.class);
        phases.forEach((phase, cost) -> costs.put(Objects.requireNonNull(phase), Objects.requireNonNull(cost)));
        phases = Collections.unmodifiableMap(costs);
        Objects.requireNonNull(measurements, "measurements");
        var values = new LinkedHashMap<String, Measurement>();
        measurements.forEach((name, value) -> {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Measurement name must not be blank");
            values.put(name, Objects.requireNonNull(value));
        });
        measurements = Collections.unmodifiableMap(values);
    }

    public Duration elapsed() { return Duration.ofNanos(elapsedNanos); }

    /** 同一类别可嵌套在其它类别中，不能按进度 Stage 切换推断其成本。 */
    public enum Phase {
        PREPARATION, COMPILATION, WAITING, DISCOVERY, INDEXING, TRACING,
        INFERENCE, RENDERING, REWRITING, NOTIFICATION, LOGGING, CLEANUP, OTHER
    }

    public record PhaseCost(long invocations, long totalNanos, long selfNanos, long maxNanos) {
        public PhaseCost {
            if (invocations < 0 || totalNanos < 0 || selfNanos < 0 || maxNanos < 0
                    || selfNanos > totalNanos || maxNanos > totalNanos
                    || invocations == 0 && (totalNanos != 0 || selfNanos != 0 || maxNanos != 0))
                throw new IllegalArgumentException("Invalid phase cost");
        }
        /** 单次平均包含耗时，嵌套类别的该值不用于计算请求成本占比。 */
        public double averageNanos() { return invocations == 0 ? 0 : (double) totalNanos / invocations; }
    }

    public enum Unit { NANOSECONDS, BYTES, COUNT, RATIO }
    public enum Scope { REQUEST, ANALYSIS_THREAD, JVM_PROCESS }

    /** JVM 全局测量与本请求/线程测量明确区分；值可表示快照或明确命名的增量。 */
    public record Measurement(BigDecimal value, Unit unit, Scope scope) {
        public Measurement {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(scope, "scope");
        }
        public Measurement(long value, Unit unit, Scope scope) { this(BigDecimal.valueOf(value), unit, scope); }
    }
}
