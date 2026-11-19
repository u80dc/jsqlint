/**
 * 框架无关的程序索引、调用上下文、控制流查询和逆向数据流，以及可冻结保存的来源证据。
 * 分析期间的 {@link org.u80dc.jsqlint.core.dataflow.FlowPoint} 和
 * {@link org.u80dc.jsqlint.core.dataflow.CallContext} 可持有 SootUp 对象；对外保存的
 * {@link org.u80dc.jsqlint.core.dataflow.ValueTrace} 只包含签名、文本和不可变数据。
 * 本包记录数据依赖、正常完成前提和候选来源，不将“来源未知”直接判定为 SQL 注入漏洞。
 * 指定方法入口可在共享程序索引上选择下游范围，调用上下文止于该入口；
 * 范围和查询缓存属于一次分析，模型副本继承相同边界，默认查询仍枚举全程序调用上下文。
 *
 * <p>除下述兼容入口外，本包实现对 core 其他包的直接依赖仅允许 core.semantics 的 Java 语义模型
 * 和 core.spi 的 InvocationSite、SqlCallSite、SourceLocation；也可使用 SootUp 与 Java 标准库。
 * 不得依赖参数推断、SQL 模板、节点渲染，或 core 根包的会话与提取流程。</p>
 *
 * <p>{@link org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint#input()} 将值使用位置转换为
 * core.spi.SqlInput.ValueAt；仅该类型允许引用 SqlInput 与 ValueAt，
 * 不允许其他数据流实现引用 SQL 输入类型，也不允许该桥接引入其他输入形式。</p>
 *
 * <p>兼容例外精确限定为两个旧入口：
 * {@link org.u80dc.jsqlint.core.dataflow.RawSqlBuilder} 可转发到 core.template.RawSqlBuilder，
 * 并使用接口所需的 core.RawSql 和 core.SqlRecoveryBudgets；
 * {@link org.u80dc.jsqlint.core.dataflow.SqlTemplateProjection} 可转发到 core.template.SqlTemplateProjection，
 * 并使用接口所需的 core.RawSql 和 core.node.SqlNode。
 * 这些例外只允许兼容转发，不在旧入口新增恢复、推断或投影算法。</p>
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.dataflow;
