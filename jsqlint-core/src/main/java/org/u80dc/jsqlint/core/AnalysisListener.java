package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlNodeRenderer;
import org.u80dc.jsqlint.core.spi.AnalysisSeed;
import org.u80dc.jsqlint.core.spi.SqlCallSite;

/**
 * 在调用分析器的线程中接收事件；不应阻塞。不同分析请求可使用不同监听器。
 * 监听器异常使本次分析失败，避免悄悄丢失输出。
 * @author u80dc
 */
public interface AnalysisListener {
    AnalysisListener NONE = new AnalysisListener() {};

    /** 阶段及累计计数更新；工作总量未知时不推算百分比。 */
    default void onProgress(Progress progress) {}
    /** 接收错误、能力限制及其他分析说明；并非每条诊断都会改变最终状态。 */
    default void onDiagnostic(String code, String message) {}
    /**
     * 在发现阶段接收 SQL 入口方法/调用位置，早于 SQL 提取；SQL 无法求值时仍会发出。
     * 方法声明与实际调用分别报告，core 同时输出 INFO 日志。
     */
    default void onSqlCallSite(String moduleId, SqlCallSite site) {}
    /** SQL、含命名槽位的 raw SQL、绑定参数及各自的数据依赖树与调用链；同一结果也保存在 AnalysisResult 中。 */
    default void onSqlExtraction(SqlExtraction extraction) {}
    /**
     * 逐个接收分析结果。template 是提取的通用节点，sql 包含样例或尚未求值的残留节点；
     * 未知参数不会被填成空串，因此 sql.sql() 可能为空。
     */
    default void onSql(String moduleId, AnalysisSeed seed, SqlNode template,
                       SqlNodeRenderer.RenderedSql sql) {}

    enum Stage { PREPARING, RESOURCES, CODE, ANALYZING, FINISHED }

    /**
     * 会话累计计数：已枚举的不同资源、已扫描方法、已发现起点，以及成功交给监听器的结果。
     * 同一起点被多个模块发现时分别计数；方法扫描计数不会随模块数量重复累加。
     */
    record Progress(Stage stage, long resources, long methods, long discovered, long analyzed) {}
}
