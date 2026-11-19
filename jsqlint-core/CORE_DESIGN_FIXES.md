# core 六项设计契约修复

实施日期：2026-11-19。对应最近一次长期维护设计审查的六项问题；此前第 1–9 项记录继续保留在
`CORE_MAINTENANCE_FIXES.md`，此前索引性能优化仍暂缓。本次保留原公共入口和既有修改，未提交 Git。

| 项目 | 修正 | 新回归 |
|---|---|---|
| 1 方法正常完成 | 已支持 Java 方法的值与正常完成条件共用语义，丢弃返回值也消费完成前提；目标异常与分析容量区分。 | JointJavaNormalCompletionTest |
| 2 求值身份契约 | Evaluation 显式保存活动句柄、来源和容量；旧签名通过有界结果关联桥接，渲染器只消费接口结果。 | SqlExpressionResultContractTest |
| 3 兼容投影隔离 | 组合与来源筛选移到会话局部恢复边界，消费恢复容量及检查点；截断保留完整提取结果，取消继续传播。 | SqlCompatibilityProjectionTest |
| 4 绑定模型不变量 | canonical Variant 校验模型归属、值、位置及来源元数据；模板模型校验联合组合归属，statement 保留完整模型，样例直接消费最终快照。 | SqlFinalBindingInvariantTest |
| 5 预算与读取边界 | SQL 恢复/投影、参数重演和节点渲染分别配置；默认样例按保存槽位与组合字符串投影，显式入口接受容量和取消上下文。 | SqlSampleProjectionBudgetTest |
| 6 完整性策略 | 结果、模板模型和样例共用内部汇总策略；分析诊断携带受影响维度，渲染诊断携带未知/容量/缺失快照类型，消费端不按 code 字符串分类。 | SqlAssessmentPolicyContractTest |

扩展兼容：原 AnalyzerConfig 构造器使用默认节点渲染容量，原 SqlEvaluationContext 单预算构造器仍同时
约束节点和表达式，新的双预算构造器可独立设置。原 Evaluation 三参、AnalysisDiagnostic 五参及结果
构造入口继续可用。SqlDiagnostic 两参构造器保留普通未知值语义，容量及缺失快照由生产方显式传入 Effect。
directReference 继续仅表示裸根引用，属性活动来源通过 ActivityValue.reference 保存。
非法绑定快照现在在更早边界拒绝，相应旧测试仅调整异常断言范围。

保留活动身份与持久快照分离、来源计算图、SQL 节点 IR、框架模型 SPI、路径探索与谓词求解分工。
没有新增平行节点体系或通用插件框架。

正常完成证明区分调用的完整返回和到达 callee 内 SQL 的路径前缀。未使用返回值的分支 helper
若仅缺少安全前提的路径选择，复用已有 RETURN 依赖闭包选入真实返回点，再验证其内部条件；
递归、容量不足、开放分派和缺少证明的条件性失败仍保留 UNKNOWN。
本地分配与模型 Map 的非空证明只消费引用来源，Map 值查询继续保留非纯性限制。
分支数组的读写保留来源图的定义身份并投影为长度前提，选中 null 或越界分支不能形成可行证明；
不可投影或不完整来源保留 UNKNOWN。原单一入口数组的元素模型支持域继续保留，不宣称推断了数组长度。
丢弃的整数除法和取模同样保留非零除数前提，浮点运算遵守 IEEE 完成语义。

## 验证

Maven 统一专项 **80 项通过，0 失败、0 错误、0 跳过**。包含本轮六个新测试类的 70 项测试，
以及旧引用契约、JDBC 字符串分支、数组非空与入口别名检查、Spring MVC 和 MyBatis Provider 回归。
日志：`../target/core-design-contract-final-complete.log`。独立探针结果不与 Maven 测试数量累计。

core、JDBC、MyBatis 完整回归 **664 项通过，0 失败、0 错误、0 跳过**，Maven BUILD SUCCESS。
其中 core 419 项、JDBC 183 项、MyBatis 62 项。完整回归包含上述专项，不将两轮数量相加。
日志：`../target/core-design-regression-final-complete.log`。

`git -c core.autocrlf=false diff --check` 通过。未修改 POM、共享样例，未提交 Git。

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dtest=**/core/**/*Test,**/jdbc/**/*Test,**/mybatis/**/*Test' `
    '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```
