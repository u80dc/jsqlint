package org.u80dc.jsqlint.core;

import org.jspecify.annotations.Nullable;
import org.u80dc.jsqlint.core.parameter.ExternalValueGenerator;
import org.u80dc.jsqlint.core.parameter.RandomExternalValueGenerator;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 控制提取后的参数重演和路径求解。默认启用联合推断，未指定种子时生成随机样例。
 * 自定义工厂须为每次调用返回独立的生成器；不会执行目标项目代码。
 * @author u80dc
 */
public record InferenceOptions(Mode mode, @Nullable Long randomSeed,
                               @Nullable Supplier<? extends ExternalValueGenerator> generatorFactory) {
    public InferenceOptions {
        Objects.requireNonNull(mode, "mode");
        if (randomSeed != null && generatorFactory != null)
            throw new IllegalArgumentException("Choose either a random seed or a custom generator factory");
    }

    public enum Mode {
        /** 只恢复 SQL 和绑定来源，保留符号值。 */
        EXTRACT,
        /** 增加独立参数重演，路径仍未验证。 */
        PARAMETERS,
        /** 增加联合路径求解，用同一入口模型重演 SQL 槽位与绑定值。 */
        JOINT;

        public boolean parametersEnabled() { return this != EXTRACT; }
        public boolean jointEnabled() { return this == JOINT; }
    }

    public static InferenceOptions defaults() { return new InferenceOptions(Mode.JOINT, null, null); }
    public InferenceOptions withMode(Mode mode) { return new InferenceOptions(mode, randomSeed, generatorFactory); }

    /** 设置种子并恢复内置生成器；null 使用未固定种子的内置生成器。 */
    public InferenceOptions withRandomSeed(@Nullable Long seed) { return new InferenceOptions(mode, seed, null); }
    public InferenceOptions withRandomSeed(long seed) { return withRandomSeed(Long.valueOf(seed)); }

    /** 设置外部值工厂并清除随机种子；null 恢复默认内置生成器。 */
    public InferenceOptions withGeneratorFactory(@Nullable Supplier<? extends ExternalValueGenerator> factory) {
        return new InferenceOptions(mode, null, factory);
    }

    /** 每条语句和每次联合重演使用独立实例，防止跨调用上下文共享可变样例状态。 */
    public ExternalValueGenerator createGenerator() {
        if (generatorFactory != null) return Objects.requireNonNull(generatorFactory.get(), "Generator factory returned null");
        return randomSeed == null ? new RandomExternalValueGenerator() : new RandomExternalValueGenerator(randomSeed);
    }
}
