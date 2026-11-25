/**
 * 输入准备和 SQL 分析模块的扩展协议，以及协议交换的来源、调用点与 SQL 输入数据。
 * 输入解析器将路径转换为 {@link org.u80dc.jsqlint.core.spi.PreparedInput}；
 * 可通过 {@link org.u80dc.jsqlint.core.spi.AnalysisInputPlan} 在输出预清理之前决定
 * 是否申请分析器管理的编译目录。已有产物或扩展缓存只借用输入句柄，
 * 不将产物删除所有权交给本次分析；旧解析器仍按原编译声明及 resolve 协议兼容。
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
 * 输入上下文可使用 core.statistics.StatisticsRecorder 的通用观测协议。
 * 此外允许 SootUp、JDK 与空值注解库。
 * 不依赖 core 根包的会话、发现和提取实现，不依赖 core.input 的具体解析器、
 * core.parameter 的推断实现、core.template 的恢复实现或 SQL 框架扩展模块。</p>
 *
 * <p>发现、提取及输入准备的执行对象只在约定的分析期内使用。
 * SQL 框架特有的资源解释、调用识别与参数定位由扩展模块实现；
 * 项目缓存策略、产物清单和构建工具留在输入扩展，core 不解释其实现。
 * 计划只在本次准备期使用，返回后的输入由 PreparedInput 管理；
 * 清单可以保存路径和模式等描述，不能保存活动 JavaView、分析状态或认证信息。
 * 本包定义共享协议和交换数据，不承担这些框架算法。</p>
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.spi;
