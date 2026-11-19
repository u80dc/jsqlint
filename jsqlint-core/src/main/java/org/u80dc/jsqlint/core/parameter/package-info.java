/**
 * 基于结构化来源的参数候选重演、路径条件求解和联合输入模型推断。
 * 纯值操作使用 {@code core.semantics} 的共享 Java 语义，来源查询使用 {@code core.dataflow}；
 * 推断结果分别保存候选值、来源、问题和路径覆盖状态，供提取结果消费层使用。
 * {@link org.u80dc.jsqlint.core.parameter.TraceSqlExpressionEvaluator} 将冻结的 Java 来源
 * 适配到 {@code core.node} 的表达式接口。
 *
 * <p>可保存的结果和冻结表达式定义只持有来源快照及受保护的值数据。
 * 参数重演实例、样例缓存、路径搜索和求解状态属于一次分析或语句，
 * 用于保持同一入口变量的样例一致性及同一输入模型的联合重演，不在独立分析之间共享。
 * 程序路径查询期间使用的 SootUp 方法体、语句和活动调用上下文属于分析期间状态；
 * 值重演解释已支持的纯值语义，不执行目标项目的方法。
 *
 * <p>允许依赖本包、{@code core.dataflow}、{@code core.node}、{@code core.semantics}，
 * {@code core.spi.InvocationSite} 与 {@code core.spi.SqlCallSite} 的调用位置契约，
 * 以及根包的 {@code JointBudgets}、{@code ReplayBudgets}、{@code RawSql} 和 {@code SqlExtraction}。
 * SootUp 依赖用于程序路径模型及旧入口适配。
 * 输入准备、框架发现、结果发布和会话终态由调用层负责；本包不调用分析入口、会话、
 * 提取管线或 JDBC/MyBatis 分析模块。
 *
 * <p>旧 {@link org.u80dc.jsqlint.core.parameter.ValueTraceAnalyzer} 保留来源查询与模板恢复的兼容入口，
 * 是本包依赖 {@code core.template.RawSqlBuilder}、{@code core.template.SqlTemplateProjection}
 * 与 {@code core.spi.SqlInput}（含嵌套类型）的例外。
 * 它复用结构化数据流和模板恢复实现，不建立第二套传播或 SQL 恢复算法；
 * 新调用方直接组合这些实现，其他参数推断类不依赖模板恢复包。
 * 旧 {@link org.u80dc.jsqlint.core.parameter.JavaValueOperations} 仅转发到 {@code core.semantics}，
 * 新语义实现和新调用方使用共享语义包。
 *
 * <p>路径切片、历史组合、搜索器及求解器的协作接口保持包级封装。
 * Java 子包没有本包的访问权限；调整包结构时应先明确公开结果与内部执行接口，
 * 保留查询状态和缓存的执行生命周期。
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.parameter;
