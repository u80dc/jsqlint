package org.u80dc.jsqlint.core.statistics;

import org.u80dc.jsqlint.core.AnalysisStatistics;
import org.u80dc.jsqlint.core.AnalysisStatisticsOptions;

import java.util.*;
import java.util.function.LongSupplier;

/** 请求独占的同步计时器；关闭时只记账，不检查取消、不抛出新的分析异常。 */
public final class StatisticsRecorder {
    @FunctionalInterface
    public interface Scope extends AutoCloseable { @Override void close(); }
    private static final Scope NOOP_SCOPE = () -> {};
    private static final StatisticsRecorder NONE = new StatisticsRecorder();
    private final LongSupplier clock;
    private final Thread owner;
    private final long started;
    private final Deque<Frame> stack;
    private final EnumMap<AnalysisStatistics.Phase, Cost> costs;
    private Optional<AnalysisStatistics> finished;
    private long droppedScopes;

    private StatisticsRecorder() {
        clock = null; owner = null; started = 0; stack = null; costs = null;
    }
    StatisticsRecorder(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock); owner = Thread.currentThread(); started = clock.getAsLong();
        stack = new ArrayDeque<>(); costs = new EnumMap<>(AnalysisStatistics.Phase.class);
    }
    public static StatisticsRecorder none() { return NONE; }
    public static StatisticsRecorder create(AnalysisStatisticsOptions options) {
        Objects.requireNonNull(options);
        return options.features().contains(AnalysisStatisticsOptions.Feature.TIMINGS)
                ? new StatisticsRecorder(System::nanoTime) : NONE;
    }
    public boolean enabled() { return clock != null; }

    public Scope measure(AnalysisStatistics.Phase phase) {
        if (!enabled() || finished != null) return NOOP_SCOPE;
        if (Thread.currentThread() != owner) { return NOOP_SCOPE; }
        var frame = new Frame(Objects.requireNonNull(phase), clock.getAsLong());
        stack.push(frame);
        return frame;
    }

    /** 只调用一次时钟并冻结；缺少埋点的时间计入 OTHER，使各类别 self 成本覆盖请求总时间。 */
    public Optional<AnalysisStatistics> snapshot() {
        if (!enabled()) return Optional.empty();
        if (finished != null) return finished;
        long end = clock.getAsLong();
        while (!stack.isEmpty()) { droppedScopes++; stack.peek().finish(end); }
        long elapsed = Math.max(0, end - started);
        var phases = new EnumMap<AnalysisStatistics.Phase, AnalysisStatistics.PhaseCost>(AnalysisStatistics.Phase.class);
        long accounted = 0;
        for (var entry : costs.entrySet()) {
            var cost = entry.getValue();
            phases.put(entry.getKey(), new AnalysisStatistics.PhaseCost(cost.count, cost.total, cost.self, cost.max));
            accounted += cost.self;
        }
        long remaining = Math.max(0, elapsed - accounted);
        if (remaining > 0) {
            var other = phases.get(AnalysisStatistics.Phase.OTHER);
            phases.put(AnalysisStatistics.Phase.OTHER, other == null
                    ? new AnalysisStatistics.PhaseCost(1, remaining, remaining, remaining)
                    : new AnalysisStatistics.PhaseCost(other.invocations() + 1, other.totalNanos() + remaining,
                        other.selfNanos() + remaining, Math.max(other.maxNanos(), remaining)));
        }
        Map<String, AnalysisStatistics.Measurement> measurements = droppedScopes == 0 ? Map.of()
                : Map.of("statistics.unclosedScopes", new AnalysisStatistics.Measurement(droppedScopes,
                    AnalysisStatistics.Unit.COUNT, AnalysisStatistics.Scope.REQUEST));
        finished = Optional.of(new AnalysisStatistics(elapsed, phases, measurements));
        return finished;
    }

    private static final class Cost { long count, total, self, max; }
    private final class Frame implements Scope {
        private final AnalysisStatistics.Phase phase;
        private final long begin;
        private long children;
        private boolean closed;
        private Frame(AnalysisStatistics.Phase phase, long begin) { this.phase = phase; this.begin = begin; }
        @Override public void close() {
            if (closed || finished != null || Thread.currentThread() != owner) return;
            if (stack.peek() != this) return;
            finish(clock.getAsLong());
        }
        private void finish(long end) {
            if (closed) return;
            closed = true; stack.pop();
            long elapsed = Math.max(0, end - begin), self = Math.max(0, elapsed - children);
            var cost = costs.computeIfAbsent(phase, ignored -> new Cost());
            cost.count++; cost.total += elapsed; cost.self += self; cost.max = Math.max(cost.max, elapsed);
            if (!stack.isEmpty()) stack.peek().children += elapsed;
        }
    }
}
