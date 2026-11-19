/**
 * 供字节码来源追踪、参数重演和路径条件求值共用的 Java 语义模型。
 * {@link org.u80dc.jsqlint.core.semantics.JavaValueOperations} 描述值转换、运算和正常完成前提；
 * {@link org.u80dc.jsqlint.core.semantics.JavaValueFacts} 校验入口事实的声明类型；
 * {@link org.u80dc.jsqlint.core.semantics.JavaMethodEffects} 描述已知方法对引用实参的效果。
 *
 * <p>本包对 core 其他包的直接依赖仅允许 core.dataflow.ValueTrace 及其嵌套类型，
 * 用作现有共享运算描述。也可使用 SootUp 类型、签名、表达式等字节码元数据和 Java 标准库。
 * 这项类型依赖不允许调用 DataFlowAnalysis、ProgramIndex 等活动分析引擎。</p>
 *
 * <p>不得依赖参数推断、模板投影、SQL 节点渲染或会话算法，不依赖框架适配模块。
 * 语义模型不遍历调用图或控制流，不反射执行目标项目的方法。</p>
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.semantics;
