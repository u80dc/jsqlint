package org.u80dc.jsqlint.core.spi;

import java.util.Map;

/**
 * 模块定义的分析起点。input 在发现阶段结束后读取，允许模块先收集跨资源定义。
 * 参数值为已知事实或调用方指定的样例；未知值无需伪造。
 * @author u80dc
 */
public interface AnalysisSeed {
    /** 原始发现位置，即使适配失败也用于定位未解析的 SQL。 */
    SourceLocation location();
    /** 在发现阶段结束后，将框架专属起点转换成通用 SQL 来源。 */
    SqlInput input();
    /** 已知参数或显式指定的分析样例；不应通过伪造值掩盖尚未解析的参数。 */
    default Map<String, ?> parameters() { return Map.of(); }
}
