# core 长期维护问题修复记录

实施日期：2026-11-19。本记录对应最近一次审查的第 1–9 项，与 `CORE_IMPROVEMENTS.md` 的旧编号分别记录。第 10 项索引性能优化暂缓；保留已有修改、旧 API 与兼容构造器，未提交 Git。

| 项目 | 修正 | 回归 |
|---|---|---|
| 1 分派完备性 | 来源候选与运行时目标完备性分离；候选均必抛但覆盖未知时不证明 UNSAT，精确接收者和不可覆盖调用仍可证明。 | JdbcDispatchCompletenessTest |
| 2 数组别名 | canonical 输入只创建一次活动值；bind、foreach 和表达式共享受保护身份，最终结果冻结。 | SqlRenderingActivityScopeTest、TemplateEnvironmentActivityTest |
| 3 入口类型 | 校验全部 primitive、引用和数组声明类型，仅接受合法 Java 赋值转换；直接数组元素读取也校验 base 模型。 | ExternalValueFactContractTest |
| 4 UNSAT 单调性 | 条件模板和空模型后处理保留完整 Java UNSAT 证明，不生成回退样例；可达动态模板仍保守 UNKNOWN。 | TemplateUnsatisfiabilityContractTest |
| 5 来源图容量 | 迭代路径选择、浅层结构键和 DAG memo 贯通联合结果与绑定组装；去重超限保留候选为 UNKNOWN，取消原样传播。 | JointValueGraphBudgetTest、SqlBindingGraphBudgetTest |
| 6 SQL 节点深度 | 结构去重、动态判断及模板控制检测使用迭代图操作；渲染在安全深度处返回未完成结果。 | SqlNodeGraphCapacityTest |
| 7 输入引用容量 | 用前缀树消除父路径，读取及整理有计步、路径深度和取消检查；万层 Map 比较在递归读取前停止，超限报告 Unknown 与 RENDER_LIMIT。 | SqlReferenceTrackingCapacityTest |
| 8 精确方法效果 | 仅按完整签名认可 requireNonNull 的 Object 与 Object/String 重载只读、不逃逸；Supplier 和未知消费者继续保守。 | JdbcMethodEffectsTest |
| 9 重演上下文 | 冻结 evaluator 保存明确预算，单次执行提供取消及容量回调；配置接入定义、模板提取和会话渲染，旧扩展继续生效。 | TraceExpressionExecutionContextTest |

## 验证

| 验证 | 结果 | 日志 |
|---|---|---|
| 本轮专项及兼容契约 | 115 项通过，0 失败、0 错误、0 跳过；BUILD SUCCESS。 | [专项日志](../target/core-maintenance-contract-complete.log) |
| 单模型快捷路径取消补充 | 4 项通过，0 失败、0 错误、0 跳过；BUILD SUCCESS。 | [补充日志](../target/core-maintenance-cancellation-followup.log) |
| core、JDBC、MyBatis 整体回归 | 595 项通过，0 失败、0 错误、0 跳过；BUILD SUCCESS。包含 JDBC 标准 API 的独立记录器、手写基准和 H2 对照验证。 | [整体日志](../target/core-maintenance-regression-complete.log) |

专项覆盖本轮新增 62 项回归和 53 项已有契约用例。首次验证发现未知分派回溯丢失来源，复测又发现具体样例状态不一致；分别修复通用回溯与逐路径正常返回条件后通过。另以独立探针复现深层 Map 比较绕过深度预算，并增加读取阶段保护。

整体首轮中的阶段预算、大循环绑定和定义身份兼容回归已修正：参数重演长度不限制静态 SQL 投影，冻结策略仅由来源 evaluator 保存；渲染节点、引用来源和文本展开分别计步；引用容量失败回滚渲染，普通来源重演未知值保留占位符。策略未改变时保持原定义实例，旧检查点入口以有界批次执行新增内部检查，显式上下文逐步检查。
单模型不需要结构去重，但入口仍检查取消；该快捷路径漏检在整体回归中发现并修复，补充验证后重新启动最终整体回归。

此前日志中的 178、31、533 项属于上一轮，不能作为本轮验证结果；专项与整体回归有重叠，不累计总数。

最终整体回归覆盖单模型取消检查的最后修正。相关模块编译成功，`git -c core.autocrlf=false diff --check` 通过；POM、共享样例和上一轮修复报告保持不变。命名调整与第 10 项索引性能优化继续暂缓，未提交 Git。
