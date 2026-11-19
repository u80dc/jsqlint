# core 包职责与依赖约定

本说明与各包的 `package-info.java` 定义当前实现的维护边界。
`CorePackageArchitectureTest` 检查编译产物中的直接类型依赖，防止后续修改绕过这些边界。

下文包名和类型名均相对于 `org.u80dc.jsqlint.core`。
允许类型同时包含其嵌套类型；输入位置桥接另有更严格的限定。
所有 core 包均不得直接依赖项目的其他模块，或 MyBatis、Spring、JPA、Hibernate 的框架实现。

## 职责与允许的 core 依赖

| 包 | 职责 | 允许的其他 core 包或类型 |
| --- | --- | --- |
| 根包 | 公共入口、配置、结果契约，以及一次分析的流程协调 | 可组合各能力包；具体算法由相应能力包实现 |
| `node` | SQL 模板结构、语法构建、表达式接口与部分渲染 | `ReplayBudgets`、`SqlRenderBudgets`、`semantics.ValueEvaluationLimitException` |
| `parameter` | 参数候选重演、路径条件求解、联合输入模型推断 | `dataflow`、`node`、`semantics`；`JointBudgets`、`ReplayBudgets`、`RawSql`、`SqlExtraction`；`spi.InvocationSite`、`spi.SqlCallSite` |
| `dataflow` | 程序索引、调用上下文、控制流查询、逆向数据流与冻结来源证据 | `semantics`；`spi.InvocationSite`、`spi.SqlCallSite`、`spi.SourceLocation`；下述精确桥接及兼容例外 |
| `semantics` | Java 值运算、入口事实校验、已知方法的引用效果 | `dataflow.ValueTrace` 及其嵌套类型，仅作为共享数据描述 |
| `template` | 根据冻结来源恢复 SQL，再从候选投影节点模板 | `node`；`RawSql`、`ReplayBudgets`、`SqlRecoveryBudgets`；`dataflow.ValueTrace`、`dataflow.ValueTraceGraphs`；下述参数重演类型；`semantics.ValueEvaluationLimitException`、`spi.SqlCallSite` |
| `spi` | 输入解析与 SQL 分析模块的扩展协议、协议交换数据 | `SqlDefinition`；`dataflow.DataFlowAnalysis`、`dataflow.FlowPoint`、`dataflow.ValueTrace`、`dataflow.ControlFlowEdge`；`node.SqlNode`、`node.SqlValueSnapshots`；`resource.AnalysisResource`、`resource.AnalysisResourceSource` |
| `input` | 已编译目录、JAR、单个 class 的输入准备 | `spi.AnalysisInputResolver`、`spi.PreparedInput`、`resource.PathResourceSource` |
| `resource` | 资源 URI、枚举与打开流的契约和路径实现 | 无 |
| `bytecode` | 方法参数名称与注解的字节码元数据读取 | 无 |

`template` 仅允许使用以下参数重演类型：`ParameterValueInferencer`、
`ParameterValueInferenceResult`、`InferredParameterValue`、`ExternalValueGenerator`。
它不查询程序视图、方法体或活动数据流引擎。

`resource` 仅依赖 JDK。`node` 不依赖 SootUp。
`semantics` 的 SootUp 引用仅限 `sootup.core.jimple`、`sootup.core.signatures`、`sootup.core.types`；
`template` 仅限后两者，不引入活动视图和分析模型。
其他包按职责使用现有 SootUp、ASM、日志及注解等基础依赖。
具体允许范围还见各包的 `package-info.java`。

## 生命周期与封装

`BoundAnalyzer` 位于根包，只保存原 Analyzer 和规范化的目标、依赖路径，委托现有分析入口。
绑定时不准备输入；每次分析读取当前程序内容并沿用独立会话及输入清理契约。

程序视图、方法体、`FlowPoint`、`CallContext`、路径搜索状态和来源查询缓存属于分析期间。
参数样例缓存、联合重演状态、模板恢复预算和节点求值作用域属于本次执行，
不得在独立分析或渲染之间共享。

对外结果保存冻结来源、SQL 结构、候选值及完整性信息；
签名和类型元数据不代表活动程序视图。
节点与绑定值按各自快照契约保护容器；快照保留的普通对象仍由调用方保证不可变。
输入流和归档按资源访问契约及时关闭，准备后的输入按 `PreparedInput` 契约使用。

根包的会话协调、结果组装校验，以及 `node`、`parameter` 的执行协作接口保留包级封装。
Java 子包不拥有父包的包级访问权限；后续拆包应先定义适当的公开契约，
避免仅为移动类而公开内部存储、活动句柄和执行状态。

## 共享数据、桥接与兼容入口

`semantics` 使用 `dataflow.ValueTrace` 的现有运算描述，
`dataflow` 又使用 `semantics` 的 Java 语义模型。
这是共享数据类型与算法之间的依赖，不能仅凭包图中的双向边认定为活动引擎相互调用。
检查按具体类型约束：`semantics` 不得依赖 `DataFlowAnalysis`、`ProgramIndex` 等活动实现。

`dataflow.FlowPoint.ValuePoint.input()` 将值的使用位置转换为 `spi.SqlInput.ValueAt`。
只允许 `FlowPoint$ValuePoint` 引用 `SqlInput` 和 `SqlInput$ValueAt` 两个精确类型，
其中外部接口也是编译产物的内部类元数据引用。
这不向其他数据流实现开放 SQL 输入类型，也不允许桥接引入 `SqlInput.Template` 等其他输入形式。

保留以下原入口的转发依赖：

| 原入口 | 额外允许的依赖 |
| --- | --- |
| `dataflow.RawSqlBuilder` | `template.RawSqlBuilder`、`RawSql`、`SqlRecoveryBudgets` |
| `dataflow.SqlTemplateProjection` | `template.SqlTemplateProjection`、`RawSql`、`node.SqlNode` |
| `parameter.ValueTraceAnalyzer` | `template.RawSqlBuilder`、`template.SqlTemplateProjection`、`RawSql`、`spi.SqlInput` |

`parameter.JavaValueOperations` 继续转发到共享语义包。
以上四个旧入口不新增恢复、投影或值语义算法，新 core 实现不得依赖这些旧入口。
兼容检查不限制外部模块继续使用现有公开入口。

## 可执行检查

检查位于 `jsqlint-test/src/test/java/org/u80dc/jsqlint/core/CorePackageArchitectureTest.java`，
直接扫描实际加载的 core 编译目录或 JAR，覆盖所有 core 类及嵌套类。
测试使用项目已有 ASM，不新增依赖、不加载或执行被引用的目标项目类型。

检查覆盖框架适配依赖、包间类型依赖、新增包的文档、精确例外边界，
以及检测器本身对描述符、泛型内部类、注解、方法体、bootstrap 参数和类字面量的识别。
普通字符串不视为类型依赖；运行期反射、动态类名和任意传递依赖不在检查范围内。
生命周期和算法职责仍需代码审查，类型依赖检查不能自动验证它们。

在仓库根目录运行（PowerShell）：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dtest=CorePackageArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```

仓库默认配置跳过测试，运行本检查必须显式关闭两个跳过开关。
`surefire.failIfNoSpecifiedTests=false` 用于没有该测试的前置模块。
新增包或合理的跨包依赖时，应同时更新包文档、本文和测试规则，并说明具体职责，
避免通过放宽整个包的允许范围掩盖新增的耦合。
