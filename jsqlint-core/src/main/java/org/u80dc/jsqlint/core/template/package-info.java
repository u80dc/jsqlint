/**
 * 消费冻结来源证据的 SQL 恢复与模板投影层。
 * {@link org.u80dc.jsqlint.core.template.RawSqlBuilder} 从结构化值来源恢复 raw SQL、占位符和候选关系；
 * {@link org.u80dc.jsqlint.core.template.SqlTemplateProjection} 消费已恢复候选，折叠已知输入并派生 SQL 节点模板。
 *
 * <p>允许依赖 core 根包的 RawSql、ReplayBudgets、SqlRecoveryBudgets，
 * core.dataflow 的 ValueTrace 与 ValueTraceGraphs，core.node 的模板节点，
 * core.parameter 的 ParameterValueInferencer、ParameterValueInferenceResult、InferredParameterValue
 * 与 ExternalValueGenerator，core.semantics.ValueEvaluationLimitException 及 core.spi.SqlCallSite。
 * SootUp 依赖仅限 sootup.core.signatures 与 sootup.core.types 的签名和类型元数据。
 * 这里组合已有证据，不重新执行程序来源查询。</p>
 *
 * <p>恢复、投影的缓存和预算计数属于本次执行；生成结果保存冻结来源和候选数据，
 * 不保留活动分析上下文，不在独立分析之间复用执行状态。</p>
 *
 * <p>不得依赖或调用 core.dataflow.DataFlowAnalysis、core.dataflow.ProgramIndex 等活动分析引擎，
 * 不持有 FlowPoint、CallContext 等活动分析对象，也不查询 SootUp 程序视图或方法体。
 * 不依赖 core 根包的会话与提取流程，不反向调用联合路径分析。</p>
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.template;
