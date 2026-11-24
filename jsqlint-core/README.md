# jsqlint-core

包职责、依赖边界、生命周期及可执行检查见 [包结构约定](PACKAGES.md)。
统一分析入口、可选模块 SPI、资源输入及长任务控制见 [分析器使用说明](ANALYSIS.md)。
固定目标及依赖、连续执行全程序或方法分析见 [路径绑定入口](ANALYSIS.md#路径绑定入口)。
指定方法及下游 SQL、无调用方 Mapper 定义分析见 [方法范围入口](ANALYSIS.md#指定方法及下游-sql)，支持完整 SootUp 签名和 JVM 方法引用。
上一轮十项修复、能力边界及验证结果见 [core 改进记录](CORE_IMPROVEMENTS.md)。
本次六项设计契约修复与验证见 [core 设计修复记录](CORE_DESIGN_FIXES.md)。
core 独立提供核心分析能力；commons、jdbc、mybatis 按需引入。

结果统一保留 `AnalysisResult.status/sqlResults/diagnostics`，以及可选的 `statistics` 快照；调用、独立定义和样例通过
`calls()/definitions()/samples()` 读取。已移除 `extractions()` 及旧二参数构造器，
显式样例聚合的执行上下文使用 `SqlSampleRenderer.render(result, context)`。
结果模型本轮收敛与迁移说明见 [结果模型简化](RESULT_MODEL.md)。

分析统计默认关闭；单次通过 `AnalysisOptions.defaults().withStatistics(true)` 启用，
在 `result.statistics()` 中读取总耗时及各类工作的次数、包含子工作的耗时、自身耗时和最大耗时。
各类别自身耗时可加总为本次总耗时；扩展测量保存单位和请求/线程/JVM 归属范围，
后续可增加 CPU、内存、GC 收集。当前仅收集时间，使用及口径见 [分析统计](ANALYSIS.md#分析统计)。

核心节点位于 `org.u80dc.jsqlint.core.node`，作为通用动态 SQL 中间表示，
独立于 JDBC、MyBatis、旧 analysis 模块和 SootUp。两种来源共用同一套节点：

```text
MyBatis 动态语句 ──适配/展开──┐
                            ├─ 统一动态 SQL 模板 ─ SqlNodeBuilder ─ SqlNode ─ SqlNodeRenderer
JDBC 字符串拼接 ──转换───────┘
```

`jsqlint-jdbc` 识别 SQL 参数位置，通用拼接值追踪与节点构造由 core 执行；
`jsqlint-mybatis` 适配 mapper、include 等框架上下文。`core` 接收转换结果，也允许直接构造节点。
Provider 的隐式方法调用和 Java 返回值通过 `ValueFlowModel` 接入通用追踪，框架只提供参数与对象语义。
`ComputedDefinition` 在共享程序索引就绪后计算未调用定义；冻结的来源可由 `TraceSqlExpressionEvaluator` 重演。
`TemplateSqlInput` 可同时保存真实 Java SQL 来源和命名参数来源，渲染仍使用统一参数化节点。
Map 快照、对象逃逸历史和控制流选择均使用通用模型；core 不解释 Provider 注解或 MyBatis SQL 子句。
`DataFlowAnalysis.traceProperty(receiver, property, type)` 按需恢复公开 getter 或实例字段，
构造器、setter 和辅助方法继续分析真实方法体。实例字段来源按对象身份及调用发生前的写入顺序选择，
继承字段按实际声明归一化；路径历史与逃逸问题随来源冻结，未知属性不借用其他对象的字段值。
节点采用密封接口、不可变列表及构造校验，不需要为不同来源建立平行的节点继承体系。

## 节点数据模型

除 `ValueSqlNode` 外，节点均为 record，使用 `text()`、`contents()`、`test()` 等组件访问器；
foreach 的布尔属性通过 `nullable()`、`skipBlank()` 访问。原来的 `getXxx()` / `isNullable()` /
`isSkipBlank()` 调用需要相应迁移，`SqlNode.isDynamic()` 和 `getType()` 保持不变。

`ValueSqlNode` 保留 final 类、私有构造器和 `raw(...)` / `parameter(...)` 工厂，
避免任意组合输出类型、null 替代文本和绑定选项；它继续使用 `getExpression()`、`getKind()`、
`getNullText()`、`getBindingOptions()` 访问属性。

所有节点按具体类型和全部语义字段进行结构比较，包含子节点顺序、原始文本、表达式和绑定选项。
`ValueSqlNode` 同样实现值语义的 `equals/hashCode`，因此包含表达式的树也能按结构比较。
这不进行 SQL 语义等价判断；空白不同或结构不同，即使渲染结果相同，也可能不相等。
需要区分相同结构的不同出现位置时，使用对象身份或单独的位置标识。

record 构造器保留原有校验与默认值；`MixedSqlNode`、`ChooseSqlNode` 使用 `List.copyOf`
复制列表，防止外部修改影响节点或哈希值。`TextSqlNode` 仍表示尚未解析的模板文本。

## 统一模板与节点语义

模板使用 MyBatis 风格标签，并提供少量用于精确保留拼接语义的扩展。
节点表达语义，标签只是输入格式；表达式通过 `SqlExpressionEvaluator` 求值。

| 语义 | 模板表示 | 核心节点 |
| --- | --- | --- |
| 原样文本 | 普通文本；需要保护占位符时用 `<literal>` | `StaticTextSqlNode` |
| 顺序拼接 | 相邻文本/标签 | `MixedSqlNode` |
| 表达式原样插入 | `${expr}` 或 `<value expression="expr"/>` | `ValueSqlNode`，`RAW_TEXT` |
| 绑定参数 | `#{expr,jdbcType=...}` | `ValueSqlNode`，`PARAMETER`，保留绑定选项 |
| 条件 | `<if test="...">` | `IfSqlNode` |
| if / else if / else | `<choose>` / `<when>` / `<otherwise>` | `ChooseSqlNode` |
| 集合遍历 | `<foreach>` | `ForEachSqlNode` |
| 局部表达式绑定 | `<bind name="..." value="..."/>` | `BindSqlNode` |
| 有意添加/裁剪 SQL 片段 | `<trim>` / `<where>` / `<set>` | 对应裁剪节点 |

`<literal>`、`<value>` 和 foreach 的 `skipBlank` 是本项目统一模板的扩展，
不能直接提交给 MyBatis 执行。`TextSqlNode` 保留为尚未拆分的模板文本入口；
解析器正常输出的树已经将占位符拆分为 `ValueSqlNode`，下游分析不必再次扫描字符串。

### JDBC 转换规则

- 常量文本保留原有空格、引号、换行和相邻顺序；不自动插入空格，也不自动使用 where/trim。
  常量中有 `${}`、`#{}` 时使用 `<literal><![CDATA[...]]></literal>`，CDATA 中的 `]]>` 需拆分。
- Java 字符串拼接的变量使用 `<value expression="name" nullText="null"/>`。
  这会保留 `String.valueOf(null)` 的 `"null"`；`${name}` 的已知 null 则输出空串。
  两者遇到未知值都保留节点，不使用 null 的替代文本。
- 字符串拼接不会自动添加 SQL 引号或转义单引号。例如 `"name='" + name + "'"`
  应保留两侧引号并插入原始值。PreparedStatement 的绑定值才映射到 `#{name}`；
  裸 `?` 的位置、参数名和设置值之间的关联由 JDBC 适配层提供。
- 连续 `if` 保留为多个 if；互斥分支使用 choose。可枚举循环使用 foreach；
  精确复现逐次 append 时指定 `skipBlank="false"`，省略 separator/open/close 即直接拼接。
- XML 中的 `<`、`&` 和属性引号按 XML 规则转义。XML 会规范化原始 CR/CRLF；
  需要保留 CR 时可编码为 `&#13;`，或直接构造节点。XML 排版空白本身也是 SQL 文本。

例如归一化后的模板可以是：

```xml
<script>SELECT * FROM users WHERE 1=1<if test="filter"> AND name='<value expression="name" nullText="null"/>'</if> ORDER BY <value expression="column" nullText="null"/></script>
```

这与 Java 条件 append 共用相同语义。复杂 Java 运算、对象到字符串的特殊转换、
循环间的可变状态，应由来源适配层归一化为参数/纯表达式，或提供相应的求值器。
默认求值器不等价于完整 Java 或 OGNL；core 不执行原始 Java 控制流。

## 解析入口

- `SqlNodeBuilder.text(sql)`：原样保存普通 SQL，不解释模板标签或占位符。
- `SqlNodeBuilder.templateText(source)`：只解析占位符，不解释 XML，保留原始换行；
  `\${...}` 和 `\#{...}` 表示字面的占位符文本。非法占位符抛 `IllegalArgumentException`。
- `SqlNodeBuilder.build(source)`：只自动识别开头的 `<script>` 根元素，其他输入作为普通 SQL。
- `SqlNodeBuilder.buildScript(xml)`：显式解析 XML 脚本或片段，失败抛出 `SqlNodeParseException`。
- `SqlNodeBuilder.parseScript(xml)`：返回包含完整输入、可选节点和诊断的结果；失败时不返回部分节点树。

XML 模式支持 `if`、`choose/when/otherwise`、`foreach`、`where`、`set`、`trim`、`bind`，
支持嵌套、CDATA 和 XML 实体。输入必须满足 XML 转义规则；XML 模式遵循 XML
换行规范化，普通 SQL 模式则逐字符保留文本。未知标签、属性、缺失必填属性、
不匹配的标签和重复的 otherwise 均明确报错。include 等需要外部上下文的语法
应先由框架适配层处理。非法占位符同样导致整个脚本解析失败，不返回已解析的前缀。

## 渲染与未知值

通用模板的 `render` 保留字面量预览语义。参数化输出使用 `renderBound`：

```java
var node = SqlNodeBuilder.templateText("SELECT * FROM users WHERE id=#{id}");
var bound = SqlNodeRenderer.renderBound(node, Map.of("id", 7), SqlExpressionEvaluator.simple());
assert bound.rendered().sql().orElseThrow().equals("SELECT * FROM users WHERE id=?");
assert bound.bindings().getFirst().value().equals(new SqlValue.Known(7));
```

未知绑定值仍输出 `?` 并记录诊断，`BoundSql.valuesComplete()` 单独判断参数是否可用。
绑定表达式、位置、类型提示及适配层选项分别保存；`SqlExpressionEvaluator.evaluateParameter`
允许适配层为绑定路径指定不同于动态表达式的语法。
`BoundParameter.origin()` 保存输入、字面量或派生表达式来源。`evaluateWithReferences` 默认通过只读
Map 的 `get` 和 `containsKey` 访问跟踪依赖，缺失名称也保留输入引用；适配求值器也可返回自身语言的结构化引用。core 根据这些引用关联 Java
传播证据，保留 bind 转换和 foreach 元素来源；值与来源遵守同一局部作用域和回滚规则。
字面量和参数化渲染均接受 checkpoint；`SqlRenderBudgets` 单独限制节点工作、深度和输出长度，
默认 10000 步、128 层、1000000 字符，表达式和 Java 来源重演使用 `ReplayBudgets`。预算耗尽时返回
`RENDER_LIMIT`，回滚当前渲染的绑定和局部值，残余节点配合原始参数可重新渲染。
`MixedSqlNode.separator` 默认空串，适配层可显式指定各层文本的连接方式。

参数化渲染的 `bound.rendered().residual()` 保留原始模板，`parameters()` 保存原始输入快照。
补充该快照后再次调用 `renderBound` 会完整重演模板，重建全部参数位置、值与来源。
重演应继续使用相同的表达式求值器；使用自定义输入来源映射时，也应传入相同的映射回调。
已完成的 SQL 中有未知绑定值时，也可用同一方式补齐。这样不会丢失前缀绑定，
也不会将已经执行的 bind 叠加到局部快照上。快照仅包含输入 Map 可枚举的条目；
自定义 Map 通过隐式名称回退提供参数时，再次渲染应继续传入包含该回退行为的 Map。

`RenderedSql.recoveryMode()` 明确区分 `LITERAL_CONTINUATION` 与 `BOUND_REPLAY`：前者保存部分求值状态，
后者保存完整重演输入。旧四参数构造器默认前者；封装参数化结果时应保留其模式。
绑定位置必须从 1 连续编号，模型构造阶段校验模板、绑定值及来源的一致性。

`SqlValue.Known`、渲染参数与模板常量快照隔离输入数组、List、Set 和 Map 的可变存储。
数组保持运行时类型，读取时返回防御副本；集合视图只读，嵌套数组也与保存的数据隔离。
因此修改原始输入或读取后取得的数组不会改变已有证据。数组及嵌套容器的 Known 值按内容比较，
内容哈希和比较对共享数据图使用缓存。其他业务对象保留引用，调用方应保证其在结果存续期间不可变。
循环容器、超过 256 层的容器、容器类型的 Map 键，以及无法容纳只读副本的具体集合类型数组不属于快照支持范围，
捕获时会明确报错；普通 Object 数组、标量数组及 List/Map 接口类型数组继续保留原类型。

默认表达式求值在一次活动求值中保留输入身份，最终结果才冻结：数组按引用比较，集合沿用自身的
`equals`，数字按数值比较。传入 `Known` 时使用独立的活动副本；重复读取和同源别名保持一致，
依赖跟踪不会为自反比较枚举无关字段。`Known.toString()` 只输出有长度上限的标量或容器摘要。
`Evaluation` 显式携带活动值句柄、结构化来源与容量状态；句柄只在一次执行中有效。
旧三参数结果通过接口边界补全直接来源，旧 `SqlValue` 返回扩展通过有界结果关联桥接传递活动身份，
透明求值器包装与 record/getter 属性访问遵循相同契约，渲染器不识别具体求值器实现。
`directReference` 仅表示表达式直接引用根参数，属性的精确活动来源通过 `ActivityValue.reference` 传递。
参数化渲染的子作用域共享追加绑定缓冲，失败按作用域起点回滚；foreach 不再复制全部历史绑定。
必要的定义绑定渲染失败会令分析返回 `PARTIAL`，已保存的来源和其他成功定义仍可读取。

```java
SqlNode node = SqlNodeBuilder.buildScript(
        "SELECT * FROM users <if test='enabled'>WHERE id=#{id}</if>");

var result = SqlNodeRenderer.render(node, Map.of("id", 7));
assert !result.complete();       // enabled 未知，不等价于 false
assert result.sql().isEmpty();   // 不提供可能误导调用方的残缺 SQL

var parameters = new LinkedHashMap<String, Object>(result.parameters());
parameters.put("enabled", true);
var completed = SqlNodeRenderer.render(result.residual(), parameters);
String sql = completed.sql().orElseThrow();
```

- 已知 null 使用 `SqlValue.Known(null)` 或参数 Map 中显式存在的 null；
  缺失参数和不支持的表达式使用 `SqlValue.Unknown`。
- 未知条件、绑定或循环保留在剩余节点树中。未完成的控制结构不提交内部绑定；
  后续节点也保持未求值，避免基于错误的变量状态生成结果。
- 字面量 `render` 的已完成前缀局部绑定保存在 `parameters()` 中；继续渲染时应基于该快照补充参数。
  参数化 `renderBound` 使用上面描述的原始模板与原始输入重演。
- 文本直接拼接，不执行全局空白压缩或括号格式化。字符串字面量、行注释终止换行保持原样。
- `#{}` 渲染为样例字面量，`${}` 渲染为原始文本；未知值不会变成 NULL 或空串。
  jdbcType 等绑定选项保存在 `ValueSqlNode.bindingOptions`；选项不参与执行语义模拟。
- foreach 支持集合、数组及 Map，迭代变量和 bind 在单次迭代中局部生效；
  item 未指定时默认为 `item`，index 未指定或为空白时不绑定索引变量，因此不会隐式遮蔽外层索引。
  item 与 index 允许同名；先绑定索引、再绑定元素，同名时使用元素值（包括已知 null）。
  迭代结束后保留外层变量原值。
  已知空集合产生空片段，默认跳过纯空白迭代片段；`skipBlank="false"` 保留所有迭代片段。
  未知集合始终产生未完成结果。已知 null 默认也产生未完成结果，显式指定 `nullable="true"`
  才视为空集合。若 MyBatis 设置了全局 nullable 默认值，适配层需把有效值写入模板。
- `renderSamples` 只处理调用方提供的参数样例，不自动制造跨参数组合。

默认表达式求值器支持字面量、属性及下标、比较、加减、布尔运算、
size/length/isEmpty。数值零为 false、非零为 true；BigDecimal 与 BigInteger 使用精确判零，
避免极小非零值在转换为 double 后被误判。它不是完整 OGNL 引擎；任意方法调用等不支持语法返回 Unknown。
未来的框架适配层可通过 `SqlExpressionEvaluator` 注入无副作用的求值实现。
默认对象属性访问为保持兼容会反射调用 getter 或 record 访问器，不能保证这些方法无副作用；
调用方应提供纯访问器的参数对象或已冻结的数据 Map，也可注入自定义求值器控制访问策略。
这些访问只针对调用方传入的参数对象。模板重演不加载目标代码、不重演原始 Java 控制流；
传入对象的访问器仍由分析器 JVM 调用，其纯度由调用方保证。
本模板对空白拼接、循环绑定作用域有上述明确定义，不保证逐字符复刻 MyBatis 的运行时行为；
需要严格复刻时，由适配层在归一化阶段处理框架的隐式空格、绑定作用域等差异。
渲染结果用于分析预览，不应直接作为数据库执行入口。

`isDynamic()` 表示节点包含需运行期求值的模板语法，不代表 SQL 注入风险；
普通 SQL 的 JDBC 占位符绑定需要由 JDBC 适配层处理。

## 验证

冻结的 Java 模板表达式保存 `AnalyzerConfig.replayBudgets()`，后续定义重演沿用该策略。
单次执行可以通过 `SqlEvaluationContext` 提供预算、取消检查和容量回调；旧渲染入口仍可使用。
`SqlDefinition.withReplayBudgets` 配置来源重演；整次 SQL 节点遍历的限制通过显式执行上下文传入。

`SqlDefinition.withTransform(SqlBoundTransform)` 可在完整模板渲染后改写 SQL 与绑定。
改写保留原模板、输入快照和恢复模式；框架模块通过 `SqlRewriteStep` 保存每一步前后的 SQL、绑定及诊断，
结果统一由 `SqlSample.rewrites()` 暴露。`withMetadata` 和 `withReplayBudgets` 保留已配置的改写器。
core 的改写协议仅依赖通用节点类型，具体插件语义由框架模块实现。
改写结果必须保留输入快照、原诊断顺序和既有改写记录；SQL 或绑定发生变化时必须提供连续证据，
第一步从实际输入开始，最后一步与最终 SQL/绑定一致。非法契约抛出 IllegalArgumentException。
SqlRenderBudgets 的 steps/length/depth 同时限制改写绑定、证据条目、说明文字和来源表达式；
超限返回 TRANSFORM_LIMIT（Effect.LIMIT）及未知 SQL，保留原模板、输入、绑定和超限记录。
这限制结果发布规模，不能阻止扩展在返回前自行分配内存；取消信号继续向调用方传播。

`DataFlowAnalysis.traceProperty(..., PropertyAccess)` 允许适配层选择 JAVA_BEAN、FIELD 或 ACCESSOR。
默认入口保留 getter 优先、字段回退；FIELD 只读取字段，ACCESSOR 只读取同名公开无参方法，
适用于 record accessor 等访问方式。上述读取均通过字节码追踪，不执行目标对象。
SqlNodeBuilder 的占位符/XML 是明确的内置模板 DSL；使用其他模板语法的模块应转换为通用 SqlNode。

```java
var context = new SqlEvaluationContext(ReplayBudgets.defaults().withSteps(2000),
        SqlRenderBudgets.defaults().withSteps(4000),
        checkpoint, message -> reportLimit(message));
var bound = definition.render(parameters, context);
```

预算及取消检查属于每次执行，保存的定义不引用已结束的分析会话。
数组别名在同次模板执行中共享活动身份，公开结果继续提供防御快照。
深层 SQL 节点和表达式引用处理受容量保护，超限保存未完成结果并报告 `RENDER_LIMIT`。
本轮第 1–9 项修正及验证结果见 [维护修复记录](CORE_MAINTENANCE_FIXES.md)。

测试源码统一位于 `jsqlint-test` 模块，在仓库根目录执行：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' test
```
