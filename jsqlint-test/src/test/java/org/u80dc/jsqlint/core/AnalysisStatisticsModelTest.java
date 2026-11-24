package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisStatisticsModelTest {
    @Test
    void statisticsAreOptInAndEveryOptionsCopyKeepsTheSelection() {
        assertFalse(AnalysisOptions.defaults().statisticsOptions().enabled());
        var selected = AnalysisStatisticsOptions.timings();
        var enabled = AnalysisOptions.defaults().withStatistics(true);
        assertEquals(selected, enabled.statisticsOptions());
        assertEquals(selected, AnalysisOptions.defaults().withStatisticsOptions(selected).statisticsOptions());
        assertFalse(enabled.withStatistics(false).statisticsOptions().enabled());
        assertEquals(AnalysisStatisticsOptions.none(), enabled.withStatisticsOptions(null).statisticsOptions());

        var copies = List.of(enabled.withTimeout(Duration.ZERO), enabled.withCancelled(() -> false),
                enabled.withListener(new AnalysisListener() {}), enabled.withClassOutputId("statistics-fixture"),
                enabled.withCleanupClassOutput(false), enabled.withInferenceOptions(InferenceOptions.defaults()));
        for (var copy : copies) assertEquals(selected, copy.statisticsOptions());
        var chained = enabled.withTimeout(Duration.ZERO).withCancelled(() -> false)
                .withListener(new AnalysisListener() {}).withClassOutputId("statistics-chain")
                .withCleanupClassOutput(true).withInferenceOptions(InferenceOptions.defaults().withMode(InferenceOptions.Mode.EXTRACT));
        assertEquals(selected, chained.statisticsOptions());
        assertEquals(selected, enabled.statisticsOptions(), "复制选项不得修改原选项");
    }

    @Test
    void featureSelectionIsAnImmutableSnapshot() {
        var features = EnumSet.of(AnalysisStatisticsOptions.Feature.TIMINGS);
        var selected = new AnalysisStatisticsOptions(features);
        features.clear();
        assertEquals(EnumSet.of(AnalysisStatisticsOptions.Feature.TIMINGS), selected.features());
        assertTrue(selected.enabled());
        assertTrue(AnalysisStatisticsOptions.none().features().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> selected.features().clear());
    }

    @Test
    void phaseAndMeasurementMapsAreImmutableSnapshots() {
        var phase = new AnalysisStatistics.PhaseCost(1, 12, 12, 12);
        var measurement = new AnalysisStatistics.Measurement(BigDecimal.valueOf(7),
                AnalysisStatistics.Unit.BYTES, AnalysisStatistics.Scope.JVM_PROCESS);
        var phases = new EnumMap<AnalysisStatistics.Phase, AnalysisStatistics.PhaseCost>(AnalysisStatistics.Phase.class);
        phases.put(AnalysisStatistics.Phase.PREPARATION, phase);
        var measurements = new HashMap<String, AnalysisStatistics.Measurement>();
        measurements.put("fixture.bytes", measurement);
        var statistics = new AnalysisStatistics(12, phases, measurements);
        phases.clear();measurements.clear();
        assertEquals(phase, statistics.phases().get(AnalysisStatistics.Phase.PREPARATION));
        assertEquals(measurement, statistics.measurements().get("fixture.bytes"));
        assertThrows(UnsupportedOperationException.class, () -> statistics.phases().clear());
        assertThrows(UnsupportedOperationException.class, () -> statistics.measurements().put("other", measurement));
    }

    @Test
    void heterogeneousMeasurementsKeepTheirUnitsScopesAndExactValues() {
        var expected = java.util.Map.of(
                "thread.cpu.elapsed", new AnalysisStatistics.Measurement(BigDecimal.valueOf(123),
                        AnalysisStatistics.Unit.NANOSECONDS, AnalysisStatistics.Scope.ANALYSIS_THREAD),
                "jvm.heap.used", new AnalysisStatistics.Measurement(BigDecimal.valueOf(1024),
                        AnalysisStatistics.Unit.BYTES, AnalysisStatistics.Scope.JVM_PROCESS),
                "jvm.gc.collections", new AnalysisStatistics.Measurement(BigDecimal.valueOf(2),
                        AnalysisStatistics.Unit.COUNT, AnalysisStatistics.Scope.JVM_PROCESS),
                "request.cache.hitRatio", new AnalysisStatistics.Measurement(new BigDecimal("0.375"),
                        AnalysisStatistics.Unit.RATIO, AnalysisStatistics.Scope.REQUEST));
        var input = new HashMap<>(expected);
        var statistics = new AnalysisStatistics(0, java.util.Map.of(), input);
        input.clear();
        assertEquals(expected, statistics.measurements(), "同一容器中的不同单位/归属不得合并或换算");
        assertEquals(new BigDecimal("0.375"), statistics.measurements().get("request.cache.hitRatio").value());
        assertThrows(UnsupportedOperationException.class, () -> statistics.measurements().clear());
    }

    @Test
    void oldResultAndOptionsConstructorsLeaveStatisticsDisabled() {
        assertTrue(new AnalysisResult(AnalysisResult.Status.SUCCESS).statistics().isEmpty());
        assertTrue(new AnalysisResult(AnalysisResult.Status.PARTIAL, List.of(), List.of()).statistics().isEmpty());
        assertFalse(new AnalysisOptions(null, () -> false, AnalysisListener.NONE, null, null).statisticsOptions().enabled());
        assertFalse(new AnalysisOptions(null, () -> false, AnalysisListener.NONE, null, null,
                InferenceOptions.defaults()).statisticsOptions().enabled());
        var statistics = new AnalysisStatistics(0, java.util.Map.of(), java.util.Map.of());
        var result = new AnalysisResult(AnalysisResult.Status.SUCCESS, List.of(), List.of(), Optional.of(statistics));
        assertEquals(statistics, result.statistics().orElseThrow());
    }
}
