/**
 * 输入准备和 SQL 分析模块的扩展协议，以及协议交换的来源、调用点与 SQL 输入数据。
 * 输入解析器将路径转换为 {@link org.u80dc.jsqlint.core.spi.PreparedInput}；
 * SQL 模块通过 {@link org.u80dc.jsqlint.core.spi.DiscoveryRegistry} 注册发现规则，
 * 并通过提取回调使用 core 提供的数据流能力。
 * 本协议明确使用 SootUp 和 core 分析引擎，扩展模块须遵守这些能力的执行期契约。
 * 方法定义回调允许按完整签名关联没有方法体的入口，复用同一分析起点与定义数据，
 * 不要求构造实际调用位置；请求接口和解析后的声明类型在继承场景下分别保留。
 *
 * <p>对 core 根包的直接依赖仅允许 {@link org.u80dc.jsqlint.core.SqlDefinition}。
 * 对 core.dataflow 的直接依赖允许 DataFlowAnalysis、FlowPoint（含 ValuePoint）、
 * ValueTrace 和 ControlFlowEdge；对 core.node 允许 SqlNode 与 SqlValueSnapshots；
 * 对 core.resource 允许 AnalysisResource 与 AnalysisResourceSource。
 * 此外允许 SootUp、JDK 与空值注解库。
 * 不依赖 core 根包的会话、发现和提取实现，不依赖 core.input 的具体解析器、
 * core.parameter 的推断实现、core.template 的恢复实现或 SQL 框架扩展模块。</p>
 *
 * <p>发现、提取及输入准备的执行对象只在约定的分析期内使用。
 * SQL 框架特有的资源解释、调用识别与参数定位由扩展模块实现；
 * 本包定义共享协议和交换数据，不承担这些框架算法。</p>
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.spi;
