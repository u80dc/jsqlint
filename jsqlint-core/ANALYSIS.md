# 分析入口与扩展

用户始终引入 `jsqlint-core`，按需求添加 `jsqlint-commons`、`jsqlint-jdbc`、
`jsqlint-mybatis`。core 不依赖这些可选模块，也不引入 Maven/Gradle 编译依赖。
旧 `jsqlint-analysis` 保持独立，未接入新流程。

## 统一入口

```java
import org.u80dc.jsqlint.core.Analyzer;
import org.u80dc.jsqlint.core.AnalyzerConfig;
import org.u80dc.jsqlint.core.AnalysisOptions;
import org.u80dc.jsqlint.core.AnalysisResult;
import java.nio.file.Path;
import java.time.Duration;

var analyzer = new Analyzer();
AnalysisResult result = analyzer.analyze(Path.of("target/classes"));
// 引入 commons 后，同一入口可以接收 .java 文件、源码目录、Maven/Gradle 项目：
AnalysisResult projectResult = analyzer.analyze(Path.of("project"), Path.of("libraries"),
        AnalysisOptions.defaults());

// 指定本次编译的输出标识，实际路径为 ${java.io.tmpdir}/.jsqlint/my-project/。
var options = AnalysisOptions.defaults()
        .withClassOutputId("my-project")
        .withCleanupClassOutput(true); // 本次分析结束后清理输出。
AnalysisResult compiledResult = analyzer.analyze(Path.of("project"), options);
```

- Path 入口自动加入当前 JDK 运行时；三参数入口的 dependencies 可以为 null、依赖 JAR、类目录或包含依赖 JAR 的目录。
  依赖目录本身和其中递归查找的 JAR 均作为 Library。
- core 内置字节码目录、JAR、单个 class 文件支持。单个 class 从字节码读取实际类名。
- commons 提供 `AnalysisInputResolver`，使用已有的 `AutoCompileAnalysisInputLocation`。
  高优先级解析器优先，同级冲突明确报错；编译失败不回退到陈旧字节码。
  可通过 `AnalysisOptions.withClassOutputId(...)` 指定输出标识，Analyzer 生成
  `${java.io.tmpdir}/.jsqlint/<指定标识>/`；未指定时生成 `${java.io.tmpdir}/.jsqlint/<随机标识>/`。
  Analyzer 始终将完整路径传给解析器，再传入 `AutoCompileAnalysisInputLocation`，不回退到解析器配置的路径。
  标识必须是单个非空目录名；同一标识复用同一路径，每次编译前递归清空该目录中的原有内容。
  默认在分析结束后保留产物；`withCleanupClassOutput(true)` 在关闭输入后删除本次输出目录，
  成功、失败、取消或超时均执行。清理失败会报告诊断并返回 FAILED。
  同一标识用于顺序复用；并发分析应使用不同标识或默认的随机标识。
  清理不越过输出目录，不允许输出目录包含源码或依赖，也不允许输出根目录通过链接重定向。
  已编译字节码无需输出目录；列表和 AnalysisRequest 入口直接使用调用方提供的输入，不再次编译。
  这些入口不清理编译输出目录，即使指定的标识已有对应目录。
- Path 入口关闭自身创建的输入，包括取消和失败的情况。
- `analyze(List<? extends AnalysisInputLocation>)` 保留调用方输入顺序和 SourceType，
  不自动添加运行时，不关闭调用方输入，也不猜测其资源路径。

显式提供代码、资源和本次执行选项：

```java
var request = new AnalysisRequest(
    locations,
    List.of(new PathResourceSource(Path.of("src/main/resources"))),
    AnalysisOptions.defaults());
AnalysisResult result = analyzer.analyze(request);
```

允许仅分析资源，但代码和资源不能同时为空。资源来源接口也支持自定义内存、归档或远程资源，
不要求存在本地 Path。远程访问策略由资源实现控制。

commons 的项目资源默认扫描项目目录，排除 src/test、target、build、.git、.gradle、.idea；
特殊资源目录、构建生成资源或依赖中的资源可通过 AnalysisRequest 显式提供。

## 实例配置与单次选项

可复用的默认配置在创建 Analyzer 时传入，只对该实例生效：

```java
var config = AnalyzerConfig.defaults()
        .withTimeout(Duration.ofMinutes(5))
        .withMaxTraceDepth(32)
        .withMethodCacheSize(128)
        .withCleanupClassOutput(true);
var analyzer = new Analyzer(config);

analyzer.analyze(Path.of("project-a")); // 使用实例默认配置。
var options = AnalysisOptions.defaults()
        .withClassOutputId("project-b")
        .withTimeout(Duration.ZERO)
        .withCleanupClassOutput(false);
analyzer.analyze(Path.of("project-b"), options); // 仅本次不限时并保留产物。
analyzer.analyze(Path.of("project-c")); // 仍使用五分钟超时和结束清理。
```

| 配置归属 | 内容 |
|---|---|
| `AnalyzerConfig` | 默认超时、最大追踪深度、方法缓存容量、默认结束清理策略、输入扩展配置 |
| `AnalysisOptions` | 本次超时覆盖、取消信号、监听器、输出标识、结束清理覆盖 |

配置优先级为：单次显式覆盖 → Analyzer 实例默认值；`AnalyzerConfig.defaults()` 提供系统默认值：
不限时、追踪深度 32、方法缓存容量 128、保留编译产物。
`AnalysisOptions.defaults()` 的 timeout 和 cleanupClassOutput 为 null，表示继承实例配置；
显式传入 `Duration.ZERO` 表示本次不限时，显式传入 false 表示本次保留产物。
`withTimeout(null)`、`withCleanupClassOutput(null)` 可恢复继承。

两种配置均不可变，with 方法返回新对象。每次调用独立合并配置，不修改 Analyzer；
输出标识、取消信号、监听器不会从上一次调用继承。Path、输入列表和 AnalysisRequest 入口
均使用相同的默认配置规则；输入列表需覆盖选项时使用 AnalysisRequest。
计时器从本次分析开始计时，缓存及其他执行状态仍由本次 AnalysisSession 独占。
扩展实例会复用，其并发安全性由具体扩展保证。

用户在调用前构造编译配置时，通过 `AnalyzerConfig.withInputOptions(...)` 交给 Analyzer。
core 仅声明通用 SPI `AnalysisInputOptions`，由输入模块实现具体配置，不引入 Maven/Gradle 的类型或依赖。
例如引入 commons 后：

```java
var inputOptions = InputLocationOptions.defaults().withMavenRepositories(repositories);
var analyzer = new Analyzer(AnalyzerConfig.defaults().withInputOptions(inputOptions));
analyzer.analyze(project);
```

Path 入口将配置和本次输出路径一起交给选中的解析器；commons 再传给 AutoCompileAnalysisInputLocation，
最终由 MavenProjectCompiler 使用仓库配置。该配置不会因单次超时或清理覆盖而丢失。
未指定 inputOptions 时使用解析器自身选项；已编译字节码会忽略编译配置，列表和 AnalysisRequest 入口不准备输入。
构建配置示例见 [commons 使用说明](../jsqlint-commons/README.md#maven-远程仓库与本地仓库)。

旧用法迁移：`analyze(source, null)` 改为 `analyze(source)`，
`analyze(source, dependencies)` 改为 `analyze(source, dependencies, AnalysisOptions.defaults())`。
不保留双 Path 重载，避免与 `analyze(source, options)` 产生 null 参数歧义。
原 AnalysisOptions 构造器中的深度和缓存容量移至 AnalyzerConfig，单次选项推荐通过 with 方法设置。

## 进度、结果与长任务控制

`AnalysisResult` 包含内部枚举 `Status`、不可变的 `extractions()` 列表，以及直接获取 raw SQL 的 `rawSqls()`。
SQL 提取结果在输入关闭后仍可读取，不持有 JavaView、方法体或编译目录资源。

| 终态 | 含义 |
|---|---|
| SUCCESS | 已完成当前启用模块及输入范围的分析；没有 SQL 也是成功 |
| PARTIAL | 存在未完成工作，例如解析失败、不支持的表达式、追踪预算耗尽或超时 |
| FAILED | 输入准备、初始化或输出监听器失败 |
| CANCELLED | 调用方取消或执行线程被中断 |

正常的外部参数保留为符号值，不会单独导致 PARTIAL。部分实现尚不支持的分析操作会报告
PARTIAL，避免把实现能力不足伪装成已完成分析。

```java
var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
var listener = new AnalysisListener() {
    @Override
    public void onProgress(Progress progress) {
        System.out.println(progress.stage() + " / " + progress.analyzed());
    }

    @Override
    public void onSql(String moduleId, AnalysisSeed seed, SqlNode template,
                      SqlNodeRenderer.RenderedSql sql) {
        // sql.sql() 仅在完全求值时有值；template/residual 保留动态条件、分支和未知参数。
        // 可以在这里保存或展示，不需要等最终 AnalysisResult。
    }

    @Override
    public void onDiagnostic(String code, String message) {
        System.err.println(code + ": " + message);
    }
};
var options = AnalysisOptions.defaults()
        .withCancelled(cancelled::get)
        .withListener(listener);
analyzer.analyze(project, dependencies, options);
```

监听器同步执行，不应长时间阻塞；输出监听器异常使本次分析失败。
阶段为 PREPARING、RESOURCES、CODE、ANALYZING、FINISHED，计数不冒充总体百分比。

取消和超时为协作机制，不会强制杀死正在执行的 Maven/Gradle 构建或单次第三方解析调用；
退出这些操作后会再次检查。调用方可以在线程池中执行同步入口，并通过取消信号控制。
单纯取消 CompletableFuture 不保证底层工作停止。

每次调用新建 AnalysisSession、JavaView、调用者索引及有界的到达定义查询缓存。
类与方法扫描由多个模块共享，仅对 SQL 候选执行值追踪。这里的缓存上限不等于整个
JavaView 的内存上限；当前仍需要保存发现的起点和调用索引，未实现磁盘缓存或增量分析。

## 扩展协议

### JDBC SQL 与绑定参数溯源

JDBC 模块在共享代码扫描完成后定位 SQL 和绑定值，core 负责逆向数据依赖、调用图、结果保存和日志输出：

```java
var result = new Analyzer().analyze(project);
for (var extraction : result.extractions()) {
    System.out.println(extraction.callSite()); // 执行/准备/批次加入调用点
    System.out.println(extraction.callChain()); // 从项目入口到该调用点所在方法
    for (var statement : extraction.statements()) {
        statement.sql().ifPresent(trace -> System.out.println(trace.root()));
        for (var binding : statement.bindings()) {
            System.out.println(binding.key());       // 位置、命名参数或显式的 unknown 标识
            System.out.println(binding.value());     // 绑定变量的数据依赖树和调用链
            System.out.println(binding.keyTrace());  // 动态下标/名称本身的来源
        }
    }
}
```

也可通过 `AnalysisListener.onSqlExtraction(...)` 逐条接收。core 的 INFO 日志分别打印
`SQL 提取`、`变量调用链` 和 `逆向数据链`；后者逐条保留根变量到来源的所有候选依赖路径。
各节点带所属方法完整签名、Jimple 语句索引、表达式、类型和追踪原因。未知值不会被填成空串。
`template()` 保存按当前调用上下文恢复的 SQL 节点，`?` 仍保留为 JDBC 占位符；绑定值独立保存，
不直接拼回 SQL，也不执行数据库查询。

### 根据传播链构造 raw SQL

通过 `AnalysisResult.rawSqls()` 可以直接遍历本次分析已产生的全部候选：

```java
var result = new Analyzer().analyze(project);
for (var sql : result.rawSqls()) {
    System.out.println(sql.text());
    System.out.println(sql.placeholders());
    sql.trace().ifPresent(trace -> System.out.println(trace.root()));
}
```

返回值为不可变的 `List<RawSql.Variant>`，从保存的提取结果派生，不重新解析 SQL。
按提取结果、语句、分支的顺序展开，不按文本去重，也不筛掉不完整候选；没有候选时返回空列表。
因此 `rawSqls().size()` 是候选记录数，包含已识别的准备点/执行点和不同调用上下文，不能作为实际执行次数。
只需要不同文本时可使用 `result.rawSqls().stream().map(RawSql.Variant::text).distinct().toList()`。
调用点、绑定参数及各层诊断继续通过 `result.extractions()` 获取；未能生成任何候选的失败来源也保存在其中。

`statement.rawSql()` 保存可直接查看的 SQL 文本候选，core 同时以 `Raw SQL` 日志输出。
构造器消费 `ValueTrace.Node.computation()` 中的计算语义，不重新扫描方法体，也不从 Jimple
打印文本猜测字符串。保留原始空白、引号、反斜线和重复拼接项。通用恢复逻辑位于 core，
框架模块只负责定位 SQL 与绑定参数。

```java
for (var extraction : result.extractions()) {
    for (var statement : extraction.statements()) {
        var raw = statement.rawSql();
        for (var variant : raw.variants()) {
            System.out.println(variant.text());
            // 例如：SELECT * FROM users WHERE id=${id} AND name='${name}'
            variant.trace().ifPresent(trace -> {
                System.out.println(trace.callChain()); // 当前调用上下文
                System.out.println(trace.root());      // 此候选选中的 SQL 传播分支
            });
            for (var slot : variant.placeholders()) {
                System.out.println(slot.name());
                System.out.println(slot.source()); // 变量类型、所在方法、转换操作及上游依赖
            }
        }
        System.out.println(raw.issues());
    }
}
```

- 确定的文本片段合并为字面量，拼接值使用 `${变量名}`；入口/顶层形参即使有已知常量实参，
  仍可保留为命名槽位以便生成不同样例。整条 SQL 的参数转发和可恢复的拼接片段继续向上展开。
- 同一来源重复出现时保留全部位置；不同来源重名时增加 `_2` 等后缀。
  字节码包含局部变量调试信息时使用变量名，否则尽可能使用 `parameterN`，其余未知值使用稳定的合成名。
- JDBC 的 `?` 保持原样，真正的预编译绑定值仍由 `statement.bindings()` 单独保存。
  raw SQL 的槽位表示文本拼接，也可能出现在表名或条件片段中，不能一律当作 JDBC 参数。
- `Placeholder.start/end` 是文本中的 UTF-16 起止位置（结束位置不包含在内），
  `source` 保留变量及转换依赖。SQL 字面量中原本存在的 `${...}` 不会被误识别为槽位。
- 支持 StringConcatFactory、String.concat、参数转发、返回值、分支和可解析的数组元素，
  以及常见的 String.valueOf、String 构造、Objects.requireNonNull 与常量 String.replace。
  动态值上的单字符替换保留转换来源；可能跨越槽位边界的多字符替换保守地保留为整体未知值。
- 分支生成多个候选；这些候选没有路径条件相关性或运行期可行性保证。
  每个候选的 `trace()` 关联其传播树：已展开的赋值/返回值分支仅保留此次选择，拼接保留全部操作数。
  未展开的符号值和未知操作仍保留原始依赖，不表示内部所有分支均已求值。
  同一执行方法有多个调用方时分别生成 `SqlExtraction`，其候选传播树携带对应的 `callChain()`。
  同一调用上下文内存在多个 SQL 分支时分别保存在 `rawSql().variants()`；即使文本相同，
  只要来源不同也保留多个候选。调用方若只关心不同 SQL 文本，可自行对 `Variant.text()` 去重。
  `trace` 由分析器生成时始终有值；手工创建的文本候选允许不提供来源。
  目前上限为 128 个候选、每个文本 1,000,000 个字符，恢复过程还受步数、深度及分析取消/超时约束。
  未知操作或达到限制时保留槽位及诊断，不把未知值吞成空串。

INFO 日志通过 `rawSql[1]`、`rawSql[2]` 等编号将每条 raw SQL 与其传播树对应；编号作用域为当前 Statement。

`raw.complete()` 仅表示 raw SQL 结构恢复没有未知操作或截断，不表示变量已有运行时取值。
它与 `sourcesComplete()`、`templateComplete()` 分别记录状态；`statement.complete()` 要求三者均完整。
因此旧模板求值器尚不支持的表达式仍可能已经具有完整的 raw SQL。

`variant.template()` 可以将该候选转为通用动态 SQL 节点，并通过 `SqlNodeRenderer` 填入样例值。
这里的样例值代表槽位 `source` 所描述转换**之后**的文本，不自动执行 Java 方法或转义/添加引号。
例如 `email.replace("'", "''")` 的槽位需要提供已替换后的文本；原值和转换后的值同时使用时拥有独立名称。
后续模拟器可根据 `source` 中的转换链自动生成这些值。不要对 raw SQL 直接做正则占位符替换，
否则可能误改 SQL 中原本存在的 `${...}` 字面文本。

JDBC 关联规则：

- Statement 的带 SQL 执行/加入批次方法、Connection 的 prepareStatement/prepareCall/nativeSQL
  从 SQL 实参开始追踪；无参 PreparedStatement/CallableStatement 执行先追踪接收对象的工厂来源。
- 追踪局部别名、跨方法传入和工厂返回；工厂在不同调用位置产生的对象按调用上下文区分。
- 识别标准 setXxx、CallableStatement 命名参数；setNull 记录真正的 null，不把 JDBC 类型编号当成绑定值。
  registerOutParameter 是输出参数声明，不作为输入绑定值。
- 从执行点沿 CFG 逆序查询。同一参数以到达执行点的最后一次设置为准，clearParameters 阻断更早的绑定；
  不读取执行之后的 setter。分支分别保存候选状态，动态参数下标不能唯一确定时保留候选并标记不完整。
- addBatch 保存该位置的绑定快照；executeBatch/executeLargeBatch 查询有效 addBatch 历史，
  确定操作同一 Statement 的 clearBatch 才丢弃更早的批次；可能别名的清理或加入保留候选并标记不完整。
  executeBatch 之前、addBatch 之后的新 setter 不会改写已加入批次的值。
  同一对象正常完成 executeBatch/executeLargeBatch 后，后续批次不再包含已提交条目；
  绑定参数仍保留。可能别名或异常退出时不强行清除历史，而是保留候选并标记不完整。
  分支或循环下的记录是静态候选，不代表已穷举运行期批次数量。
- 外部方法接收 Statement 时，绑定和批次均记录未建模的副作用边界；即使没有已知 SQL，
  也保留带诊断的未知来源。只有确定为空且无诊断的批次才返回完整的空列表。

core 的 `dataflow` 包对 JDBC 没有依赖：`ProgramIndex` 使用应用类层次 CHA 解析虚调用/接口调用，
静态/特殊调用按声明解析；`DataFlowAnalysis` 提供调用上下文、引用来源、过程间逆向 CFG 遍历和变量依赖树；
`LocalDefinitions` 为 SQL 文本恢复及变量追踪共用到达定义算法。
调用解析同时保存可分析的项目目标和外部/未解析分支；项目覆盖实现不会吞掉依赖库中的具体实现。
依赖库的接口、抽象类和可继承类型视为开放的分派边界，不将项目实现当作其全部实现。
这些分支同时用于返回值追踪、对象来源和过程间状态回溯。
`ArrayElements` 沿调用路径查询数组元素写入，支持局部别名、跨方法写入、常量下标和新数组默认值；
确定数组与下标匹配才覆盖历史，不确定别名/下标保留候选并标记不完整。入口数组保留参数来源，
无法解析的数组身份、外部修改或未覆盖的数组结构明确保留未知信息。
`HeapExposure` 保留逆向路径上的外部可达容器与字段存储边，支持嵌套容器、数组容器和静态字段的间接逃逸；
未做精确堆别名与字段覆盖建模，遇到可能的外部修改保留候选并报告不完整。
`ObjectContents` 将对象分配与构造参数关联，支持 String 的空构造和 String 拷贝构造；
其他构造保留可见实参来源并标记内容模型缺失，不能只找到 new 就声称内容完整。
调用链在 main 或项目内没有调用方的方法处结束，不在 public 方法处提前停止。
常量的数据依赖可在定义处结束，但仍单独保存该使用上下文通往入口的调用链。
每个入口调用上下文独立分析 SQL 和参数，避免把不同调用方的值合并成一个确定结果。

`Statement.sourcesComplete()` 表示数据来源/关联完整性；`templateComplete()` 表示模板恢复未遇到
不支持的结构，相关诊断保存在该条结果的 `templateIssues()`，与来源诊断 `issues()` 分开。
`Statement.complete()` 同时要求来源、模板及 raw SQL 恢复完整，`SqlExtraction.complete()` 汇总所有语句。
`ValueTrace.complete()` 仍仅表示来源完整性。这些状态并不表示入口参数已有运行时取值，
也不保证 CHA 候选路径在实际运行时一定可行。条件相关性和精确堆 points-to 尚未实现。
字段读取保存项目中的候选字段写入及其上游依赖，并标记别名/写入顺序不确定；
已发现调用点中遇到的反射/库调用边界、无法解析的 Statement 来源、递归和循环不会静默丢弃，
结果及终态标为不完整/PARTIAL。纯反射 SQL 调用和 invokedynamic 回调的完整调用关系尚未建模。
库方法返回值保留调用及其可见输入依赖，不能声称已分析库的实现。
StringBuilder 等可变容器的精确内容恢复仍需后续模型。

跨方法/依赖深度使用 `AnalyzerConfig.maxTraceDepth`（默认 32，可通过 withMaxTraceDepth 调整）；
单次调用路径查询最多保留 256 个上下文，单次依赖/控制流查询最多处理 10000 个节点。
达到限制会保留已发现结果并标为不完整；调用链/数据依赖树使用 LIMIT 标记，引用来源查询返回不完整状态。
取消和超时沿用 Analyzer 的检查点。

后续框架可通过 `registry.extraction(selector, handler)` 接入；handler 接收带调用上下文的 FlowPoint
及 core 的 DataFlowAnalysis，只需返回包含 SQL 变量位置和绑定变量位置的 SqlTraceSource。
core 统一生成 SqlExtraction 和 ValueTrace，框架不需要自行实现调用链遍历。

### SQL 调用点

分析时 core 通过 `org.u80dc.jsqlint.core.AnalysisSession` 的 INFO 日志打印已确认的 SQL 调用点，
并调用 `AnalysisListener.onSqlCallSite(moduleId, site)`。不设置监听器也会输出日志，例如：

```text
SQL 调用点: module=jdbc, kind=INVOCATION, class=java.sql.Statement, method=<java.sql.Statement: boolean execute(java.lang.String)>, caller=<example.Repository: void query(java.sql.Statement)>, location=SourceLocation[resource=java:example.Repository, element=<example.Repository: void query(java.sql.Statement)> @3]
```

`SqlCallSite` 保存目标方法签名、可选调用者签名及位置：

- `target()`：目标所属类全路径、返回类型、方法名和参数类型。
- `caller()`：实际调用所在方法的完整签名；方法声明没有调用者。
- `kind()`：`DECLARATION` 表示 SQL 入口方法声明，`INVOCATION` 表示实际调用。
- `location()`：类 URI、方法签名及实际调用的 Jimple 语句索引；索引不是源码行号。

方法声明与调用分别报告，未使用的 Mapper 方法也能被发现。同一位置被同一模块的多条规则命中时
只报告一次，不同位置调用同一方法分别保留；不同模块的识别结果分别报告。
调用点在发现阶段输出，即使后续 SQL/Provider 无法解析也不会被吞掉。
调用点数与 SQL 提取起点数不相等，例如无参 `PreparedStatement.execute()` 可以是调用点，
SQL 字符串来自先前的 `prepareStatement(sql)`；JDBC 的过程间提取会关联二者并保存参数快照。
`Progress.discovered/analyzed` 继续统计 SQL 提取起点和结果，不统计调用点。

调用者代码只扫描 Application 输入，不以 main/Controller 可达性过滤；Library 输入用于类型解析。
目标签名来自字节码的静态类型，尚未推断运行时多态分派、反射或动态代理的实际实现。
只有 XML 没有 Mapper 字节码时仍提取 XML SQL，但不伪造方法签名。
通过输入列表提供 Mapper 代码时，需要在 `AnalysisRequest.resources` 中同时提供 XML 资源。

### 注册规则

`new Analyzer()` 和 `new Analyzer(config)` 用 ServiceLoader 发现两种 SPI：

- `org.u80dc.jsqlint.core.spi.AnalysisInputResolver`：可选输入准备能力。
- `org.u80dc.jsqlint.core.spi.SqlAnalysisModule`：框架资源、起点及语义适配。

服务声明放在对应模块的 `META-INF/services/<接口全名>` 文件中。
也可以显式构造 `new Analyzer(modules, resolvers)` 或
`new Analyzer(config, modules, resolvers)`，只启用传入扩展；
core 的字节码解析器始终存在。通过 `config.withInputOptions(...)` 可以保留自动发现行为；
原有显式传入 `new AutoCompileInputResolver(inputLocationOptions)` 的方式仍可使用。
输入解析器在 `resolve(source, classOutputDirectory, inputOptions, checkpoint)` 中读取支持的配置类型。
未指定配置时默认实现兼容旧的 resolve 重载；编译解析器不支持传入配置时应报错，避免静默忽略配置。

模块在 `register(DiscoveryRegistry)` 中注册资源、方法元数据和调用点规则。
同一资源可以被多个模块处理。每个模块实例应无执行状态，每次 register 内部创建会话专属状态。
SQL 入口识别通过以下规则单独声明，不要求产生 SQL 提取起点：

```java
registry.sqlMethod((method, view) -> /* 框架方法声明判断 */ false);
registry.sqlInvocation(site -> /* 框架实际调用判断 */ false);
```

`sqlMethod` 可读取方法注解及 view 中的继承关系；`sqlInvocation` 共享 core 的方法体扫描。
只有声明规则时不触发方法体扫描；只有调用点规则时不会为值追踪额外保存调用者索引。
框架识别逻辑放在具体模块，core 统一构造 `SqlCallSite`、打印并分发事件。
原有 `resource/method/invocation` 规则继续用于提取 SQL：

处理器同步发出 AnalysisSeed，core 在发现阶段结束后读取其 SqlInput：

- Template：模块已经转换好的通用 SQL 节点。
- ValueAt：某个方法及语句位置上的值，由 core 追踪。

方法级处理器产生 ValueAt 起点时应调用 `registry.requireCallIndex()`，
使 core 建立共享调用者索引。`invocation` 提取规则自动启用此能力，`sqlInvocation` 识别规则则不启用。
参数、来源及框架专属元数据可以由模块自己的 AnalysisSeed 实现保存。

core 负责通用 SQL 提取、值与参数溯源、保守推断和节点渲染。
模块负责识别 SQL 位置与框架语义，不能依靠 core 中的 JDBC/MyBatis 枚举进行分发。

## 当前覆盖范围

- JDBC：Connection 的 prepareStatement、prepareCall、nativeSQL，以及 Statement 的
  execute、executeQuery、executeUpdate、executeLargeUpdate、addBatch 的字符串参数。
  额外报告 PreparedStatement/CallableStatement 的无参 execute/executeQuery/executeUpdate/
  executeLargeUpdate/addBatch 和 Statement 的 executeBatch/executeLargeBatch 调用。
  支持已解析的实现类/子类型，不以 Controller 或 main 可达性限制发现范围。
- MyBatis：Mapper XML 语句、可唯一确定的跨文件 include、Select/Insert/Update/Delete SQL 注解；
  动态标签转换为现有通用节点。通过 namespace/id 和 SQL/Provider 注解识别接口方法声明与调用，
  支持继承方法、重复注解及重载签名；XML 按方法名绑定，无法区分重载，因此保留全部候选签名。
  default/static 辅助方法不作为映射 SQL 入口，其内部调用仍正常扫描。Provider 可以作为调用点被发现，
  但 Provider SQL 内容仍需后续适配。直接调用 SqlSession 的字符串 statementId 入口尚未适配。
- core 值追踪：常量、控制流到达定义、已索引的参数调用来源、静态/特殊调用返回值、
  Java StringConcatFactory 连接和 String.concat。不同调用的参数上下文分别处理；
  分支保留为符号选择，不取第一个结果。
- 已知参数可以通过 AnalysisSeed.parameters() 提供，core 渲染分析样例。
  外部输入不会随机伪造；SQL 文本和样例均不作为数据库执行入口。

尚未完整迁移旧 analysis 的框架能力：StringBuilder 等可变对象建模、复杂虚调用/字段传播、
精确堆别名、Spring JDBC、MyBatis Provider、动态 include 属性、
selectKey 与 JPA 等仍需后续扩展。遇到已发现起点中不支持的结构，会保留未知节点并报告
PARTIAL；没有启用相应框架模块的代码不属于本次框架识别范围。

MyBatis 适配沿用 core 动态节点的空白及作用域语义，尚不保证逐字符复刻 MyBatis
运行时的隐式空格及全部 OGNL 行为。完整运行时 SQL 集合也无法通过任意静态分析穷举。
