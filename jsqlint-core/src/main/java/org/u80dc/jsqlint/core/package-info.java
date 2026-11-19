/**
 * 分析公共入口、配置与结果契约，以及一次分析的内部流程协调。
 * {@link org.u80dc.jsqlint.core.Analyzer} 负责输入准备和会话创建；包内会话依次协调发现、
 * 来源提取、参数推断、模板渲染及结果交付。公共结果保存来源证据、候选关系、最终绑定和完整性评价。
 * {@link org.u80dc.jsqlint.core.BoundAnalyzer} 仅绑定路径并委托原 Analyzer，不持有活动输入或执行状态。
 *
 * <p>入口、结果与会话协作使用同一包级封装边界。
 * 会话、发现器、提取管线、兼容适配、结果绑定校验及评价策略保持 package-private；
 * 公共结果的组装通过同包校验维护来源、组合和最终绑定的一致性。
 * Java 子包没有父包的包级访问权限，不能为迁移类而公开上述实现或结果内部存储。
 * 若未来拆包，应先收敛结果组装入口，再整体迁移内部流程协作类。</p>
 *
 * <p>允许组合 core 各能力包，包括 core.spi 的输入与 SQL 模块协议、core.input.BytecodeInputResolver、
 * core.resource.AnalysisResource 与 AnalysisResourceSource，
 * core.dataflow 的程序分析与来源证据、core.parameter 的推断与结果契约、
 * core.node 的模板与渲染契约，以及 core.template.RawSqlBuilder 与 SqlTemplateProjection。
 * 会话通过这些能力完成组合，不在根包重复实现数据流、路径求解、字节码元数据或节点求值算法。
 * 输入与分析协调可使用 SootUp、JDK、日志及空值注解库；
 * JDBC、MyBatis 等 SQL 框架适配由扩展模块通过 SPI 提供，core 不反向依赖它们。</p>
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core;
