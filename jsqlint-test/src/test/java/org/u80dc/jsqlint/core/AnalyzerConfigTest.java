package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.spi.AnalysisInputOptions;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** 确认预算在旧接口及单次配置合并中不会丢失或互相覆盖。 @author u80dc */
class AnalyzerConfigTest {
    @Test
    void legacyConstructorsKeepTheirSharedDepthMeaning() {
        var config = new AnalyzerConfig(Duration.ZERO, 7, 16, false);
        assertEquals(7, config.maxCallDepth());
        assertEquals(7, config.maxTraceDepth());
        assertEquals(10000, config.maxTraceNodes());
        assertEquals(config, new AnalyzerConfig(Duration.ZERO, 7, 16, false, null));
    }

    @Test
    void independentBudgetsSurviveConfigurationAndInvocationOverrides() {
        AnalysisInputOptions input = new AnalysisInputOptions() {};
        var config = AnalyzerConfig.defaults().withMaxCallDepth(3).withMaxTraceDepth(80).withMaxTraceNodes(99)
                .withMethodCacheSize(4).withInputOptions(input).withTimeout(Duration.ofSeconds(10)).withCleanupClassOutput(true);
        var resolved = AnalysisOptions.defaults().withTimeout(Duration.ZERO).withCleanupClassOutput(false).resolveAgainst(config);
        assertEquals(3, resolved.maxCallDepth());
        assertEquals(80, resolved.maxTraceDepth());
        assertEquals(99, resolved.maxTraceNodes());
        assertEquals(4, resolved.methodCacheSize());
        assertSame(input, resolved.inputOptions());
        assertEquals(Duration.ZERO, resolved.timeout());
        assertFalse(resolved.cleanupClassOutput());
        assertEquals(Duration.ofSeconds(10), config.timeout());
        assertTrue(config.cleanupClassOutput());
    }

    @Test
    void nonPositiveBudgetsAreRejected() {
        var defaults = AnalyzerConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxCallDepth(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxTraceDepth(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxTraceNodes(0));
    }
}
