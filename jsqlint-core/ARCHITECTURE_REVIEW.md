# jsqlint-core 功能与抽象复审

审查日期：2026-11-18。问题描述与行号对应本轮实施前的工作区，包括上一轮尚未提交的修复；暂不评价命名。

## 实施进度

以下重点建议已逐步实施；原始发现保留作设计依据，实际验证结果在本节更新。

| 建议 | 当前处理 |
|---|---|
| R1 | 无参大小写转换因目标 Locale 未知而保守处理，未添加隐式宿主环境前提。 |
| R2 | 统一带类型的已知运算执行；独立重演、求解及前缀剪枝传递实际文本长度容量。 |
| R3 | 拒绝重复联合参数名、矛盾的整体状态；构造时校验模板、绑定顺序、值及证据。 |
| R4 | RenderedSql 增加明确恢复模式，保留旧四参数构造器；参数化包装保留模式。 |
| R5 | 每个完整模板模型关联自己的候选，候选的最终绑定快照供模型和样例共用，移除反向匹配。 |
| R6 | 持久容器隔离原输入和公开取值；补齐模板环境、派生数组及引用跟踪边界；共享容器比较/哈希及嵌套 Known 清理使用身份缓存。 |
| R7 | 查询使用身份缓存，SQL 来源及候选去重使用迭代结构编号，完整性按唯一节点迭代检查。 |
| R8 | 会话内缓存语句位置及复合调用键，内部查询使用有索引重载，保留旧接口。 |

快照支持无环数组、List、Set 和 Map；数组防御副本保留运行时类型。普通业务对象保留引用，
仍要求调用方不可变。循环容器、超过 256 层的容器、容器 Map 键及不能容纳只读副本的具体集合类型数组明确拒绝。
Node 的公开结构 equals/hashCode、旧无索引接口、自带求值器的扩展策略和已注明的预算范围仍保留。
类型枚举、兼容入口和 POM 精简继续作为可选事项，未删除既有公开 API。

验证：第一批契约回归 55 项通过；本轮 core/JDBC/MyBatis 及标准 JDBC API 验证回归 462 项通过；
最后的快照和依赖跟踪修复完成后，针对性回归 104 项通过，其中快照测试 11 项。
三轮均无失败、错误或跳过，相关模块编译成功，`git diff --check` 通过。测试范围存在重叠，不相加计数。
日志保存在 `target/architecture-contract-test.log`、`target/architecture-regression-test.log` 和
`target/architecture-final-test.log`。命名调整及可选精简未开展，未提交 Git。

## 结论与判断依据

目前主流程和主要抽象合理，具有实际使用场景，应该继续保留。主要维护负担来自结果状态的重复表示、边界约束不足、部分执行语义仍分散，以及查询成本未充分隐藏在索引和缓存内部。下一轮工作宜收紧这些契约，暂不扩大接口数量或引入通用分析框架。

本次按职责单一、高内聚低耦合、信息隐藏、契约一致性、避免重复、避免提前泛化、可重复性与可验证性审查。判断抽象是否必要，采用四个问题：它是否隐藏不同的变化原因；是否有真实消费者；是否承载独立的语义或生命周期约束；其维护成本是否低于调用方自行处理这些差异的成本。类长度和接口实现数量只作为线索，不作为判定标准。

这是源码和实际调用关系复审，不是某项 ISO 标准的认证。Brooks-Lint 本机入口仅转发到未找到的技能，本次没有执行该插件的专用审计流程。初次复审未修改 Java 实现或运行新测试；后续实施和验证见顶部进度。以下原始性能判断基于算法成本，尚无性能基准数据。

## 功能与抽象的必要性

| 功能 | 当前抽象 | 判断及依据 |
|---|---|---|
| 输入准备与关闭 | Analyzer、AnalysisInputResolver、PreparedInput、ClassOutputDirectory | 保留。字节码输入与源码编译有不同实现和所有权；路径入口负责创建及关闭输入，请求入口使用调用方输入。这一差异不能藏在同一个无条件关闭流程里。 |
| 单次执行控制 | AnalysisSession、AnalysisOptions、AnalysisListener | 保留。取消、超时、诊断、交付和终态属于一次执行，独立于可复用 Analyzer。内部通过回调传递控制能力已足够，无需增加公开会话服务接口。 |
| 框架发现与共享扫描 | SqlAnalysisModule、DiscoveryRegistry、SqlDiscovery | 保留。JDBC 与 MyBatis 注册不同规则，core 共享扫描，并在定义和调用点就绪后分析。资源、声明和实际调用有不同语义，不能简单合并成一种规则。 |
| 程序查询 | ProgramIndex、LocalDefinitions | 保留。全程序调用解析与局部到达定义是不同算法、缓存和查询边界。需要增强已有索引能力，而非新增一套索引服务。 |
| 使用位置与证据 | FlowPoint/ValuePoint、CallContext、ValueTrace | 保留。前者依赖活动代码模型，后者保存可在输入关闭后读取的证据；两者具有不同生命周期，不是重复 DTO。 |
| 框架值语义补充 | ValueFlowModel、backward/Transfer、数组与逃逸模型 | 保留。MyBatis SQL Builder、Provider 实参和 JDBC 状态有真实复用；框架补语义，通用层负责遍历和控制。数组覆盖与对象逃逸的状态不同，强行合并会隐藏约束。 |
| Java 值运算 | core.semantics.JavaValueOperations、ValueTrace.Computation | 保留并继续收敛。整数、转型、非空检查和字符串运算需要明确语义；需要进一步统一带类型的已知值执行，见 R1、R2。 |
| SQL 恢复与模板投影 | core.template.RawSqlBuilder、SqlTemplateProjection | 保留。前者恢复文本候选和槽位证据，后者保守折叠与投影为模板；它们消费已有来源，职责不同，不应发展成第二套溯源算法。 |
| SQL 结构与求值 | sealed SqlNode AST、SqlNodeBuilder、SqlNodeRenderer、SqlExpressionEvaluator | 保留。if/choose/foreach/bind 有不同语义；raw 文本与参数绑定必须区分。Simple、MyBatis、Trace 三种求值器已经存在，策略接口有实际必要性。where/set 复用 trim 的执行实现，但有独立输入语义，可继续保留。 |
| 参数与路径分析 | ParameterValueInferencer、JointSqlValueInferencer、JointPathExplorer、PathPredicateSolver | 保留。独立重演给出值及选中来源；联合推断判断不同值能否在同一路径共存。路径探索、约束求解和数值执行不同，不宜合并成一个分析器。 |
| 结果与样例 | SqlResult、SqlExtraction、SqlTemplateResult、SqlAssessment、SqlSample | 概念保留，表示方式应收紧。代码调用与资源定义需要不同结果；来源、文本、取值、覆盖度应分别表达。重点是构造约束和权威快照，而非再加 DTO，见 R3—R6。 |
| 配置和预算 | AnalyzerConfig、InferenceOptions、ReplayBudgets、SqlRecoveryBudgets、JointBudgets | 保留。实例默认值与单次覆盖、算法容量与样例策略有不同语义；三组阶段预算控制不同工作量。无需把它们合并为一个通用 Budget，也不必为了简化构造器新增策略继承体系。 |

实现依据包括 [Analyzer](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/Analyzer.java:98)、[SqlDiscovery](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/SqlDiscovery.java:34)、[ValueFlowModel](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/dataflow/ValueFlowModel.java:9)、[MyBatis 真实求值器](D:/JavaProjects/jsqlint/jsqlint-mybatis/src/main/java/org/u80dc/jsqlint/mybatis/MybatisExpressionEvaluator.java:11) 和 [Provider 的 Trace 求值器使用](D:/JavaProjects/jsqlint/jsqlint-mybatis/src/main/java/org/u80dc/jsqlint/mybatis/ProviderSqlExtractor.java:96)。

## 新发现与改进建议

### R1：环境相关运算被视为确定的纯值运算

性质：正确性问题，优先处理。

[JavaValueOperations](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/semantics/JavaValueOperations.java:36) 接受无参 toLowerCase/toUpperCase，并在 [执行处](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/semantics/JavaValueOperations.java:73) 使用分析进程的默认 Locale。该结果依赖运行环境，而不仅依赖显式操作数。目标环境可以不同，目标代码也可改变默认 Locale。

例如字符串 I 在土耳其 Locale 下小写为 ı，在常见英语 Locale 下为 i。现有模型可能把宿主环境中的结果标成 Known，进而影响 SQL 文本和条件求解。

建议明确运行环境前提；未提供前提时保守处理默认 Locale 运算。需要支持时优先建模显式 Locale 重载。直接换成 Locale.ROOT 会改变目标 Java 的语义，不能作为修复。验证应覆盖不同 Locale 下的同一输入，以及相应结果完整性。

### R2：共享语义仍止于底层工具方法

性质：抽象尚未完整收敛，存在语义漂移风险。

[ParameterValueInferencer](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/ParameterValueInferencer.java:194) 和 [PathPredicateSolver](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/PathPredicateSolver.java:187) 仍各自分发非空、转型、拼接等运算。已有具体差异：重演器的 IDENTITY 按外层基本类型转换；求解器在 [构造表达式时](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/PathPredicateSolver.java:145) 直接去掉外层。

布尔类型的 IDENTITY 包裹整数 1，重演器得到 true，求解表达式保留整数 1。常见 Jimple 比较可能掩盖差异，不能据此断言普通条件全部错误，但新增运算仍必须同步修改两套执行分发。求解器中的部分字符串容量也仍使用固定值，见 [执行处](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/PathPredicateSolver.java:195)。

建议由 semantics 提供一个共享的“带类型 Computation＋已知操作数”的执行入口，接收明确容量。重演器继续管理来源选择、证据、候选；求解器继续管理符号转换、比较约束、搜索和 Unknown 策略。无需增加访问者、插件注册表或继承体系。验证应比较同一运算在重演和条件求值中的结果及异常语义。

### R3：公共结果和联合输入的约束未集中

性质：明确的公共 API 边界问题；内置调用方目前规避了部分情况。

[inferParameters](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/JointSqlValueInferencer.java:62) 接受任意参数集合，却未拒绝重复名称；[保存组合时](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/JointSqlValueInferencer.java:211) 按名称 Map.put，前一个维度被静默覆盖。模板管线生成唯一键，目前不会触发，但公开 API 不应静默丢失数据。

[SqlTemplateResult](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/SqlTemplateResult.java:18) 同时持有 template、definition、rendered、bound，只验证 bound 需要 definition，未保证模板来源一致。[templates()](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/SqlTemplateResult.java:43) 读取 template，显式 [sample](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/SqlSampleRenderer.java:25) 读取 definition；公共构造器允许同一结果的不同视图使用不同模板。

[JointSqlInferenceResult](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/JointSqlInferenceResult.java:13) 已约束覆盖不完整时为 UNKNOWN，这是正确的；但仍允许顶层 UNSATISFIABLE 且包含 SATISFIABLE 组合等直接矛盾状态。不能简单禁止所有带诊断的可满足结果，因为诊断是否改变状态需要语义约定。

建议首先在现有构造或工厂边界校验名称唯一、模板身份和状态汇总规则。暂不全面更换公开 record。Statement 的 sql 和 definition 不应强制二选一，Provider 实际同时具有 SQL 来源与定义。验证应直接构造非法输入，确认及时拒绝，以及所有结果视图保持一致。

### R4：渲染输出与恢复状态混合在同一个类型中

性质：类型契约问题；上一轮绑定恢复缺陷已经修复，此处不重复该缺陷。

[字面量 render](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/node/SqlNodeRenderer.java:29) 保存已求值前缀和局部环境；[renderBound](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/node/SqlNodeRenderer.java:52) 保存原模板和初始输入，以便完整重建绑定。两者使用同一 RenderedSql，差异由 [Javadoc](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/node/SqlNodeRenderer.java:355) 解释，类型没有恢复模式。

[AnalysisSession](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/AnalysisSession.java:170) 在参数化渲染超限时，把 bound.rendered 放进预览字段，随后通过同一个旧回调交付。通用消费者只有 RenderedSql，无法从类型判断其恢复状态。

建议保留共用 SQL/诊断视图，明确区分部分求值 continuation 与完整 replay 状态，至少给出可识别模式和受控恢复入口。恢复所需的求值器、来源映射及自定义 Map 回退行为也需有明确约定。迁移期间保持旧入口行为，避免一次性改动所有调用方。

### R5：最终绑定模型有多份表示，展示时重新关联

性质：结果抽象的重复职责，建议在边界约束稳定后收敛。

[BoundModel](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/TemplateInferenceResult.java:45) 保存 bound、bindings、combination；[模板管线](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/TemplateExtractionPipeline.java:100) 又派生 RawSql.Variant.BindingSnapshot；[样例生成](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/SqlSampleRenderer.java:125) 再按 SQL 文本、组合和绑定列表反向寻找 variant。

绑定证据数量的校验直到 [SqlBindingSnapshots](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/SqlBindingSnapshots.java:14) 才发生。多个视图可以合法共享同一对象，但独立保存并重新关联使每次修改都需要维持更多一致性条件。

建议在模型构造时形成一个权威的最终绑定快照，其他视图引用或投影它；来源证据与最终值仍分别保留。保留 SqlAssessment 的独立维度，把共同的完整性和可行性规则靠近最终模型，展示层减少再次推导。不要把所有结果强行合并成一个巨型 Result。

### R6：容器不可变不等于证据取值不可变

性质：快照契约问题，对调用方提供数组、集合、Map 的场景有实际影响。

[SqlValue.Known](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/node/SqlValue.java:30) 可保存任意对象。[组合 inputs](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/JointSqlInferenceResult.java:36) 和 [渲染 parameters](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/node/SqlNodeRenderer.java:367) 只复制外围 Map，数组或集合仍与调用方共享。显式数组模型已有真实使用，例如 [JdbcJointConsistencyTest](D:/JavaProjects/jsqlint/jsqlint-test/src/test/java/org/u80dc/jsqlint/jdbc/JdbcJointConsistencyTest.java:169)。

调用方后续修改数组或集合，会改变已保存证据中的输入前提；使用保存参数重新渲染时，也可能得到不同结果。纯访问器约定不能自动阻止这些数据修改。

建议明确活动求值输入与持久证据的边界，在保存时冻结受支持的数组、列表、集合和 Map。任意业务对象无需尝试通用深克隆，应明确支持范围或保存稳定摘要。冻结后的值也应防止通过结果 getter 再次修改；仅复制数组一次不足以完成这个契约。验证应覆盖调用方修改原输入和通过结果访问数据两种情况。

### R7：递归 record 的结构哈希藏有不受预算计数控制的遍历

性质：算法成本问题，优先于为类拆分文件；尚未做基准测量。

[ValueTrace.Node](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/dataflow/ValueTrace.java:22) 的默认结构 hashCode 包含整个输入子树。[重演缓存](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/parameter/ParameterValueInferencer.java:87) 和 [槽位命名表](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/template/RawSqlBuilder.java:23) 直接使用节点作为哈希键。

深链在每层查询时可能产生平方级重复工作。共享子节点重复出现时，哈希成本按展开路径计算，可能远高于实际节点数。外层 visit 有检查点，但一次 hashCode 递归本身没有检查点，也不增加算法的 steps 计数。

建议保留证据的结构值语义，对查询内缓存使用对象身份或已缓存的稳定指纹。槽位去重另需明确来源和变换身份，不能把所有 Map/Set 无差别改为 IdentityHashMap。后续验证应包含深链及共享子树 DAG，并检查取消和容量限制仍有效。

### R8：语句和调用位置查询未充分利用已有索引

性质：有依据的性能改进建议，需要规模测试确定收益。

[FlowPoint 的快照](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/dataflow/FlowPoint.java:24)、[DataFlowAnalysis 的调用位置](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/dataflow/DataFlowAnalysis.java:358) 反复对语句列表 indexOf；[ProgramIndex 的复合查询](D:/JavaProjects/jsqlint/jsqlint-core/src/main/java/org/u80dc/jsqlint/core/dataflow/ProgramIndex.java:61) 扫描全部调用点。

建议把语句位置与复合调用键缓存放在已有索引或方法缓存中，避免每个传播节点附加一次线性查询。索引应遵守当前方法体稳定性和会话生命周期，保留隐式调用的注册能力。暂不增加公开位置服务接口。

## 可选简化与明确保留的兼容范围

- SqlNodeType 与 sealed AST 形成两份类型清单；当前生产分发主要使用类型 switch。可作为派生标识保留兼容，无需发展为另一套分发机制。尚未检查外部消费者，不能直接删除公开 getType。
- JDBC 同时注册调用点、完整提取和旧 seed，见 [JdbcAnalysisModule](D:/JavaProjects/jsqlint/jsqlint-jdbc/src/main/java/org/u80dc/jsqlint/jdbc/JdbcAnalysisModule.java:16)。当前分别服务发现、提取和兼容事件，不是三套同等的新分析流程。SqlSeedCompatibility 作为迁移边界合理，后续 API 应优先使用 SqlResult，明确兼容周期后再减少旧路径。
- 旧包的 JavaValueOperations、RawSqlBuilder、SqlTemplateProjection 与 ValueTraceAnalyzer 委托应继续保留兼容；不要把它们当成独立算法再扩展。
- 不建议给每个 final 类补接口，也不建议为了 AST 遍历统一新增 visitor。当前有限类型 switch 和少数真正有多实现的策略接口更容易维护。
- 预算 record 合理，但不能据此声称所有扩展和证据处理容量已经统一。TemplateSources 仍有固定来源容量，并会产生 LIMIT；Provider 自行准备模板及自带求值器继续使用所选或默认策略。这些是已知范围，不作为本轮新增静默截断缺陷重复报告。
- POM 中部分 SootUp 能力没有直接源码使用迹象，但可能由传递实现或服务加载使用。依赖精简只能列为待验证项，需进行依赖分析和移除后的编译、相关回归，不应仅靠 import 搜索删除。

## 建议的逐步处理顺序

1. 先处理 R1 和 R3：明确环境前提、拒绝静默覆盖、补结果不变量。改动局部，验收条件清楚。
2. 明确 R6 的支持范围并冻结持久证据；保留求值时业务对象的使用契约。
3. 收敛 R2 的已知值执行；继续保留重演、符号求解和路径探索三者的独立职责。
4. 处理 R7，再用规模测试决定 R8 的索引收益。不要把预算放宽当作性能修复。
5. 在以上契约稳定后处理 R4、R5，逐步统一恢复状态和最终绑定快照，减少展示层的反向匹配。
6. 最后评估类型枚举、兼容入口和依赖精简；命名调整仍单独安排。

每一步以具体行为或不变量验收；无需为纯搬包、兼容委托和机械转发额外编写镜像测试。本次建议没有要求全面重写、删除既有公开 API 或引入新的框架。
