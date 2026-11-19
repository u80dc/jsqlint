/**
 * 框架无关的 SQL 模板中间表示、语法构建、表达式求值和部分渲染。
 * 节点保存 SQL 结构及表达式文本；语法构建将普通文本、占位符和 XML 脚本转换为节点，
 * 渲染将节点转换为 SQL 预览或 SQL 与绑定值，并保留尚未解析的结构。
 * 表达式语言通过 {@link org.u80dc.jsqlint.core.node.SqlExpressionEvaluator} 扩展。
 *
 * <p>公共节点及渲染结果是可保存的数据契约。已知值和参数容器按各自快照契约防御复制，
 * 快照保留的普通对象由调用方保证在结果存续期间不可变。
 * {@link org.u80dc.jsqlint.core.node.SqlEvaluationContext} 属于一次执行；
 * 活动值句柄、输入身份、引用跟踪、旧接口结果桥接、检查点计数及渲染作用域在同次执行内共享，
 * 不保存到持久结果，也不在独立执行之间复用。
 *
 * <p>包内求值器实现、跟踪器和结果桥接保持包级封装。
 * 活动值读取、已知值内部快照读取及旧求值结果补全只供同包执行协作使用。
 * Java 子包没有本包的访问权限；拆分包时应先建立保持快照与活动身份隔离的接口，
 * 不能通过公开内部存储或活动句柄读取方法来代替该边界。
 *
 * <p>允许依赖本包、根包的 {@code ReplayBudgets} 与 {@code SqlRenderBudgets}，
 * 以及 {@code core.semantics.ValueEvaluationLimitException} 的容量终止契约。
 * 文本、XML、容器和对象属性读取使用 JDK API，现有节点使用 Lombok 生成值对象访问方法。
 * 程序数据流、Java 来源重演和框架发现由调用层适配到本包的节点与表达式接口；
 * 本包不依赖 {@code core.dataflow}、{@code core.parameter}、{@code core.template}、
 * SootUp 或 JDBC/MyBatis 分析模块，也不调用分析入口、会话和提取管线。
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.node;
