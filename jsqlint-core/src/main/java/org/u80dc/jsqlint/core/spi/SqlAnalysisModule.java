package org.u80dc.jsqlint.core.spi;

/**
 * 可由 ServiceLoader 发现或显式注册的框架扩展。
 * 实例应无执行状态；register 每次会话调用，可在其中创建会话专属处理器和缓存。
 *
 * @author u80dc
 */
public interface SqlAnalysisModule {
    /**
     * 非空且在同一分析器内唯一，用于关联起点、输出和诊断。
     */
    String id();

    /**
     * 注册本次会话使用的规则，不应在此重新遍历整个项目或启动异步发现任务。
     */
    void register(DiscoveryRegistry registry);
}
