package org.u80dc.jsqlint.core.spi;

/**
 * 可选输入解析器的配置契约。具体字段由提供解析器的模块定义，core 只负责传递。
 * 实现应不可变，以便同一个 Analyzer 在多次调用中安全复用配置。
 *
 * @author u80dc
 */
public interface AnalysisInputOptions {}
