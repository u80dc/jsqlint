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
| `AnalyzerConfig` | 默认超时、最大追踪深度、方法缓存容量、默认结束清理策略 |
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

旧用法迁移：`analyze(source, null)` 改为 `analyze(source)`，
`analyze(source, dependencies)` 改为 `analyze(source, dependencies, AnalysisOptions.defaults())`。
不保留双 Path 重载，避免与 `analyze(source, options)` 产生 null 参数歧义。
原 AnalysisOptions 构造器中的深度和缓存容量移至 AnalyzerConfig，单次选项推荐通过 with 方法设置。

## 进度、结果与长任务控制

`AnalysisResult` 暂时只包含内部枚举 `Status`：

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

`new Analyzer()` 和 `new Analyzer(config)` 用 ServiceLoader 发现两种 SPI：

- `org.u80dc.jsqlint.core.spi.AnalysisInputResolver`：可选输入准备能力。
- `org.u80dc.jsqlint.core.spi.SqlAnalysisModule`：框架资源、起点及语义适配。

服务声明放在对应模块的 `META-INF/services/<接口全名>` 文件中。
也可以显式构造 `new Analyzer(modules, resolvers)` 或
`new Analyzer(config, modules, resolvers)`，只启用传入扩展；
core 的字节码解析器始终存在。配置编译选项时，可显式传入
`new AutoCompileInputResolver(inputLocationOptions)`，避免与默认 commons 实现重复注册。

模块在 `register(DiscoveryRegistry)` 中注册资源、方法元数据和调用点规则。
同一资源可以被多个模块处理。每个模块实例应无执行状态，每次 register 内部创建会话专属状态。
处理器同步发出 AnalysisSeed，core 在发现阶段结束后读取其 SqlInput：

- Template：模块已经转换好的通用 SQL 节点。
- ValueAt：某个方法及语句位置上的值，由 core 追踪。

方法级处理器产生 ValueAt 起点时应调用 `registry.requireCallIndex()`，
使 core 建立共享调用者索引。调用点规则自动启用此能力。
参数、来源及框架专属元数据可以由模块自己的 AnalysisSeed 实现保存。

core 负责通用 SQL 提取、值与参数溯源、保守推断和节点渲染。
模块负责识别 SQL 位置与框架语义，不能依靠 core 中的 JDBC/MyBatis 枚举进行分发。

## 当前覆盖范围

- JDBC：Connection 的 prepareStatement、prepareCall、nativeSQL，以及 Statement 的
  execute、executeQuery、executeUpdate、executeLargeUpdate、addBatch 的字符串参数。
  支持已解析的实现类/子类型，不以 Controller 或 main 可达性限制发现范围。
- MyBatis：Mapper XML 语句、可唯一确定的跨文件 include、Select/Insert/Update/Delete SQL 注解；
  动态标签转换为现有通用节点。
- core 值追踪：常量、控制流到达定义、已索引的参数调用来源、静态/特殊调用返回值、
  Java StringConcatFactory 连接和 String.concat。不同调用的参数上下文分别处理；
  分支保留为符号选择，不取第一个结果。
- 已知参数可以通过 AnalysisSeed.parameters() 提供，core 渲染分析样例。
  外部输入不会随机伪造；SQL 文本和样例均不作为数据库执行入口。

尚未完整迁移旧 analysis 的框架能力：StringBuilder 等可变对象建模、复杂虚调用/字段传播、
JDBC setXxx 绑定参数关联、Spring JDBC、MyBatis Provider、动态 include 属性、
selectKey 与 JPA 等仍需后续扩展。遇到已发现起点中不支持的结构，会保留未知节点并报告
PARTIAL；没有启用相应框架模块的代码不属于本次框架识别范围。

MyBatis 适配沿用 core 动态节点的空白及作用域语义，尚不保证逐字符复刻 MyBatis
运行时的隐式空格及全部 OGNL 行为。完整运行时 SQL 集合也无法通过任意静态分析穷举。
