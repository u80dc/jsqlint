# core 十项问题修复记录

实施日期：2026-11-19。对应本轮审查的 1–10，保留上一轮 R1–R8 修复与旧 API；暂缓命名调整。

| 项目 | 实施结果 | 主要回归 |
|---|---|---|
| 1 引用比较 | 根据声明类型区分数值与引用；包装内容不证明身份，null 与同一符号保留精确判断，可变 Map 不用于结构矛盾复用。 | PathPredicateSolverGraphTest、JdbcReferenceComparisonTest |
| 2 正常返回 | 有预算的正常完成摘要排除必抛调用，未知、外部和递归保守；可达 catch 不被误判无解。 | JdbcNormalCompletionTest、JointExceptionalCompletionTest |
| 3 数组边界 | 分配加入非负长度条件，来源确定为单个本地分配的元素读写加入合法下标条件；ARRAY_ACCESS_CHECK 保留独立重演边界，文本恢复不丢检查。 | ArrayAccessCheckSemanticsTest、JdbcNormalCompletionTest、SqlArrayRecoveryTest |
| 4 方法分派 | 分开声明解析与接收者目标选择，处理类方法优先、合法覆盖和最具体接口默认方法；无项目具体实现时保留可继承的具体应用声明体。 | ProgramDispatchSelectionTest、JdbcDataFlowTest |
| 5 活动值与快照 | 表达式中间值保留身份，最终结果冻结；跟踪器复用受保护活动副本及同来源包装。 | SqlExpressionActivityValueTest、SqlTrackedActivityIdentityTest |
| 6 求解容量 | 图转换、收集及求值使用身份缓存，浅层结构编号保留等价条件；增加独立 predicateSteps。 | PathPredicateSolverGraphTest |
| 7 日志容量 | 迭代输出节点与输入编号；限制行数、节点、边、列表和文本，显式报告省略；Known 使用安全摘要。 | SqlAnalysisLoggerTest、SqlValueLogSummaryTest |
| 8 渲染终态 | 必要绑定渲染失败返回 PARTIAL，保存来源及其他成功定义；普通未知绑定不当作执行错误。 | SqlRenderingFailureTest |
| 9 选择键作用域 | 合成选择键避开嵌套选择组、表达式和局部业务名，单层无冲突时保留 choice_0。 | SqlTemplateChoiceIsolationTest |
| 10 绑定事务 | 子作用域共享追加缓冲，失败按起点回滚，消除 foreach 全量历史绑定复制。 | SqlNodeBindingTransactionTest |

`predicateSteps` 默认 20,000，原 `JointBudgets` 七参数、十参数构造器保留此默认值。
`Known` 对持久容器的内容相等语义与默认表达式的活动引用比较是不同契约，均保持明确。
普通业务对象仍需调用方保证不可变；异常、外部调用、完整引用身份和数据库状态的全面建模不在本轮范围内。

## 验证

| 验证 | 结果 | 日志 |
|---|---|---|
| 十项修复专项回归 | 178 项通过，0 失败、0 错误、0 跳过，BUILD SUCCESS。 | [专项日志](../target/core-ten-fixes-contract-final.log) |
| 分派兼容与日志截断补充回归 | 31 项通过，0 失败、0 错误、0 跳过，BUILD SUCCESS。 | [补充日志](../target/core-ten-fixes-followup.log) |
| 最新代码的 core、JDBC、MyBatis 整体回归 | 533 项通过，0 失败、0 错误、0 跳过，BUILD SUCCESS。包含 JDBC 标准 API 的独立记录器、手写基准与 H2 对照验证。 | [整体日志](../target/core-ten-fixes-regression-final.log) |

三次回归包含重叠测试，不累计总数。整体回归已覆盖最后补齐的抽象类型包装方法分派与列表省略提示。
相关模块编译成功，`git -c core.autocrlf=false diff --check` 通过；旧 API 与构造器保留，命名调整继续暂缓。未提交 Git。
