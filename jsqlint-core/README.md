# jsqlint-core

核心节点位于 `org.u80dc.jsqlint.core.node`，作为通用动态 SQL 中间表示，
独立于 JDBC、MyBatis、旧 analysis 模块和 SootUp。两种来源共用同一套节点：

```text
MyBatis 动态语句 ──适配/展开──┐
                            ├─ 统一动态 SQL 模板 ─ SqlNodeBuilder ─ SqlNode ─ SqlNodeRenderer
JDBC 字符串拼接 ──转换───────┘
```

`jsqlint-jdbc` 后续负责把拼接逻辑转换成模板；`jsqlint-mybatis` 后续负责
mapper、include、参数名等框架上下文。`core` 接收转换结果，也允许直接构造节点。
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
- 已完成的前缀绑定保存在 `parameters()` 中；继续渲染时应基于该快照补充参数。
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
本模板对空白拼接、循环绑定作用域有上述明确定义，不保证逐字符复刻 MyBatis 的运行时行为；
需要严格复刻时，由适配层在归一化阶段处理框架的隐式空格、绑定作用域等差异。
渲染结果用于分析预览，不应直接作为数据库执行入口。

`isDynamic()` 表示节点包含需运行期求值的模板语法，不代表 SQL 注入风险；
普通 SQL 的 JDBC 占位符绑定需要由 JDBC 适配层处理。

## 验证

测试源码统一位于 `jsqlint-test` 模块，在仓库根目录执行：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' test
```
