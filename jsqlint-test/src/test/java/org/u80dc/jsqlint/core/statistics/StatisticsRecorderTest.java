package org.u80dc.jsqlint.core.statistics;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.AnalysisStatistics;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsRecorderTest {
    @Test void nestedCategoriesHaveExclusiveCostsAndUnmeasuredTimeIsAccounted() {
        var clock = new AtomicLong();
        var recorder = new StatisticsRecorder(clock::get);
        clock.set(10);
        var discovery = recorder.measure(AnalysisStatistics.Phase.DISCOVERY);
        clock.set(15);
        var indexing = recorder.measure(AnalysisStatistics.Phase.INDEXING);
        clock.set(25); indexing.close();
        clock.set(35); discovery.close();
        clock.set(50);
        var statistics = recorder.snapshot().orElseThrow();
        assertEquals(50, statistics.elapsedNanos());
        assertEquals(new AnalysisStatistics.PhaseCost(1, 25, 15, 25), statistics.phases().get(AnalysisStatistics.Phase.DISCOVERY));
        assertEquals(new AnalysisStatistics.PhaseCost(1, 10, 10, 10), statistics.phases().get(AnalysisStatistics.Phase.INDEXING));
        assertEquals(25, statistics.phases().get(AnalysisStatistics.Phase.OTHER).selfNanos());
        assertEquals(statistics.elapsedNanos(), statistics.phases().values().stream().mapToLong(AnalysisStatistics.PhaseCost::selfNanos).sum());
        clock.set(100);
        assertSame(statistics, recorder.snapshot().orElseThrow());
    }

    @Test void recursiveCategoryAndRepeatedCloseDoNotDuplicateExclusiveTime() {
        var clock = new AtomicLong();
        var recorder = new StatisticsRecorder(clock::get);
        clock.set(5); var outer = recorder.measure(AnalysisStatistics.Phase.TRACING);
        clock.set(10); var inner = recorder.measure(AnalysisStatistics.Phase.TRACING);
        clock.set(20); inner.close(); inner.close();
        clock.set(25); outer.close(); outer.close();
        clock.set(30);
        var statistics = recorder.snapshot().orElseThrow();
        assertEquals(new AnalysisStatistics.PhaseCost(2, 30, 20, 20), statistics.phases().get(AnalysisStatistics.Phase.TRACING));
        assertEquals(10, statistics.phases().get(AnalysisStatistics.Phase.OTHER).selfNanos());
    }

    @Test void exceptionFinallyClosesScopeAndDisabledRecorderHasNoStatistics() {
        var clock = new AtomicLong();
        var recorder = new StatisticsRecorder(clock::get);
        assertThrows(IllegalStateException.class, () -> {
            try (var scope = recorder.measure(AnalysisStatistics.Phase.COMPILATION)) {
                clock.set(7); throw new IllegalStateException("fixture");
            }
        });
        clock.set(8);
        var statistics = recorder.snapshot().orElseThrow();
        assertEquals(7, statistics.phases().get(AnalysisStatistics.Phase.COMPILATION).selfNanos());
        var none = StatisticsRecorder.none();
        assertFalse(none.enabled());
        assertSame(none.measure(AnalysisStatistics.Phase.TRACING), none.measure(AnalysisStatistics.Phase.INFERENCE));
        assertTrue(none.snapshot().isEmpty());
    }

    @Test void snapshotClosesUnfinishedScopesExactlyOnceAndReportsTheirCount() {
        var clock = new AtomicLong();
        var recorder = new StatisticsRecorder(clock::get);
        clock.set(5); var tracing = recorder.measure(AnalysisStatistics.Phase.TRACING);
        clock.set(8); var inference = recorder.measure(AnalysisStatistics.Phase.INFERENCE);
        clock.set(20);
        var statistics = recorder.snapshot().orElseThrow();
        assertEquals(new AnalysisStatistics.PhaseCost(1, 15, 3, 15), statistics.phases().get(AnalysisStatistics.Phase.TRACING));
        assertEquals(new AnalysisStatistics.PhaseCost(1, 12, 12, 12), statistics.phases().get(AnalysisStatistics.Phase.INFERENCE));
        assertEquals(5, statistics.phases().get(AnalysisStatistics.Phase.OTHER).selfNanos());
        assertEquals(new AnalysisStatistics.Measurement(2, AnalysisStatistics.Unit.COUNT, AnalysisStatistics.Scope.REQUEST),
                statistics.measurements().get("statistics.unclosedScopes"));
        assertEquals(20, statistics.elapsedNanos());
        assertEquals(statistics.elapsedNanos(), statistics.phases().values().stream().mapToLong(AnalysisStatistics.PhaseCost::selfNanos).sum());

        clock.set(100);
        inference.close();tracing.close();
        recorder.measure(AnalysisStatistics.Phase.RENDERING).close();
        assertSame(statistics, recorder.snapshot().orElseThrow());
        assertFalse(statistics.phases().containsKey(AnalysisStatistics.Phase.RENDERING));
        assertEquals(new AnalysisStatistics.Measurement(2, AnalysisStatistics.Unit.COUNT, AnalysisStatistics.Scope.REQUEST),
                statistics.measurements().get("statistics.unclosedScopes"));
    }
}
