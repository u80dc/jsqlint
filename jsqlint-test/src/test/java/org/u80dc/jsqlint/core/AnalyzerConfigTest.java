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
        assertEquals(InferenceOptions.defaults(), config.inferenceOptions());
        assertEquals(config, new AnalyzerConfig(Duration.ZERO, 7, 16, false, null));
    }

    @Test
    void independentBudgetsSurviveConfigurationAndInvocationOverrides() {
        AnalysisInputOptions input = new AnalysisInputOptions() {};
        var inference = InferenceOptions.defaults().withMode(InferenceOptions.Mode.PARAMETERS).withRandomSeed(42);
        var config = AnalyzerConfig.defaults().withInferenceOptions(inference).withJointBudgets(JointBudgets.defaults().withCombinations(7))
                .withMaxCallDepth(3).withMaxTraceDepth(80).withMaxTraceNodes(99)
                .withMethodCacheSize(4).withInputOptions(input).withTimeout(Duration.ofSeconds(10)).withCleanupClassOutput(true);
        var resolved = AnalysisOptions.defaults().withTimeout(Duration.ZERO).withCleanupClassOutput(false).resolveAgainst(config);
        assertEquals(3, resolved.maxCallDepth());
        assertEquals(80, resolved.maxTraceDepth());
        assertEquals(99, resolved.maxTraceNodes());
        assertEquals(4, resolved.methodCacheSize());
        assertSame(input, resolved.inputOptions());
        assertEquals(Duration.ZERO, resolved.timeout());
        assertFalse(resolved.cleanupClassOutput());
        assertSame(inference, resolved.inferenceOptions());
        assertEquals(7, resolved.jointBudgets().combinations());
        assertEquals(Duration.ofSeconds(10), config.timeout());
        assertTrue(config.cleanupClassOutput());
    }

    @Test
    void nonPositiveBudgetsAreRejected() {
        var defaults = AnalyzerConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxCallDepth(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxTraceDepth(0));
        assertThrows(IllegalArgumentException.class, () -> defaults.withMaxTraceNodes(0));
        assertThrows(IllegalArgumentException.class, () -> JointBudgets.defaults().withCombinations(0));
        assertThrows(NullPointerException.class, () -> new AnalyzerConfig(Duration.ZERO, 8, 16, false, null, 8, 100, null));
    }

    @Test
    void jointBudgetsSurviveConfigurationAndSingleItemAdjustments() {
        var config = AnalyzerConfig.defaults().withJointBudgets(JointBudgets.defaults().withCombinations(7));
        assertEquals(7, config.jointBudgets().combinations());
        assertEquals(512, config.jointBudgets().attempts(), "单项调整不影响其余预算");
        assertEquals(JointBudgets.defaults(), AnalyzerConfig.defaults().jointBudgets());
        assertEquals(JointBudgets.defaults(), AnalyzerConfig.defaults().withJointBudgets(null).jointBudgets());
        // 旧构造器默认携带默认联合预算，等价性不被新字段破坏。
        assertEquals(new AnalyzerConfig(Duration.ZERO, 7, 16, false, null, 7, 100, JointBudgets.defaults()),
                new AnalyzerConfig(Duration.ZERO, 7, 16, false, null, 7, 100));
    }

    @Test
    void invocationInferenceOverrideSurvivesOtherOptionsAndCanReturnToDefaults() {
        var defaults = AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withRandomSeed(42));
        var inference = InferenceOptions.defaults().withMode(InferenceOptions.Mode.EXTRACT);
        var options = AnalysisOptions.defaults().withInferenceOptions(inference).withTimeout(Duration.ZERO)
                .withCancelled(() -> false).withListener(AnalysisListener.NONE).withClassOutputId("inference-test")
                .withCleanupClassOutput(false);
        assertSame(inference, options.resolveAgainst(defaults).inferenceOptions());
        assertEquals(defaults.inferenceOptions(), options.withInferenceOptions(null).resolveAgainst(defaults).inferenceOptions());
        assertEquals(42L, defaults.inferenceOptions().randomSeed());
        assertEquals(InferenceOptions.defaults(), defaults.withInferenceOptions(null).inferenceOptions());
    }

    @Test
    void invalidInferenceConfigurationsAreRejectedEarly() {
        assertThrows(NullPointerException.class, () -> InferenceOptions.defaults().withMode(null));
        assertThrows(IllegalArgumentException.class, () -> new InferenceOptions(InferenceOptions.Mode.JOINT, 42L,
                () -> (type, name) -> new org.u80dc.jsqlint.core.node.SqlValue.Known(1)));
    }
}
