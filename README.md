# JSQLint

JSQLint 从 Java 字节码和 Mapper 资源中提取 SQL，追踪动态条件与参数来源，并生成包含绑定参数的静态样例。
当前支持 JDBC、MyBatis 和 MyBatis-Plus，通过 Java API 接入，适用于 SQL 清单梳理、代码审查和分析工具集成。

[快速开始](#快速开始) · [常用配置](#常用配置) · [模块与文档](#模块与文档) · [开发与贡献](#开发与贡献)

## 功能特性

- **SQL 提取**：识别 JDBC 调用，解析 MyBatis XML、SQL 注解和 Provider，恢复 MyBatis-Plus CRUD 与 Wrapper SQL。
- **参数溯源**：追踪字符串拼接、方法调用和对象属性，保留参数类型、取值来源及调用路径。
- **统一结果**：使用统一模型表示 SQL、绑定参数、动态模板、样例和诊断，保留无法确定的值与不完整状态。
- **多种输入**：支持 class 目录、普通 JAR、Java 源码以及 Maven/Gradle 项目，按需编译主代码。
- **产物复用**：支持构建缓存、失效重建和已编译项目清单，减少重复编译。
- **扩展与统计**：通过模块 SPI 接入分析能力，可选收集总耗时、各阶段成本和缓存指标。

## 环境要求

- JSQLint 的构建和运行需要 **JDK 21 或更高版本**。
- 仓库提供 **Maven Wrapper**，无需单独安装 Maven。
- 分析源码项目时，需要能够解析目标项目的构建依赖；Gradle 项目使用其 Wrapper 或指定发行版。

目标项目可以使用 Java 8、Java 11 等较早的编译目标。Maven 默认在分析器 JVM 中执行，
Gradle 按项目配置选择构建 JDK；目标版本和实际编译 JDK 由项目配置决定，当前不会自动按版本切换 JDK。
需要完整使用旧 JDK 构建的项目，可以先独立编译，再分析已有产物。
工具链配置和兼容性边界见 [运行 JDK 与目标编译 JDK](jsqlint-commons/README.md#运行-jdk-与目标编译-jdk)。

## 快速开始

### 1. 构建并安装

从仓库根目录执行，将当前版本安装到本地 Maven 仓库。

Linux / macOS：

```bash
./mvnw install
```

Windows PowerShell：

```powershell
.\mvnw.cmd install
```

项目默认跳过测试。仅需打包时可运行 `./mvnw package` 或 `.\mvnw.cmd package`，
也可使用 [build.sh](build.sh) / [build.bat](build.bat)；JAR 产物位于各模块的 `target/` 目录。

### 2. 引入所需模块

以下示例用于分析 JDBC 项目，版本与仓库当前 POM 一致；两个模块会传递引入 `jsqlint-core`。

```xml
<properties>
    <jsqlint.version>0.1.0-SNAPSHOT</jsqlint.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.u80dc.jsqlint</groupId>
        <artifactId>jsqlint-commons</artifactId>
        <version>${jsqlint.version}</version>
    </dependency>
    <dependency>
        <groupId>org.u80dc.jsqlint</groupId>
        <artifactId>jsqlint-jdbc</artifactId>
        <version>${jsqlint.version}</version>
    </dependency>
</dependencies>
```

MyBatis 项目将 `jsqlint-jdbc` 替换为 `jsqlint-mybatis`，MyBatis-Plus 项目替换为 `jsqlint-mybatis-plus`。
混合使用时可同时引入对应模块。默认 Analyzer 通过 ServiceLoader 自动发现已引入的模块；
源码与项目编译输入需要 `jsqlint-commons`。

### 3. 分析目标项目

将路径替换为目标 Maven/Gradle 项目的根目录：

```java
import java.nio.file.Path;
import org.u80dc.jsqlint.core.Analyzer;

public class AnalyzeExample {
    public static void main(String[] args) {
        var result = new Analyzer().analyze(Path.of("path/to/project"));

        System.out.println(result.status());
        System.out.println(result.diagnostics());
        for (var sample : result.samples()) {
            System.out.println(sample.sql().orElse("<unknown>"));
            System.out.println(sample.bindings());
            System.out.println(sample.assessment());
        }
    }
}
```

在仓库根目录运行示例时，可将路径设为 `shared-test-resources/jdbc-user-query`，
更多目标项目见 [共享分析样例](shared-test-resources/README.md)。
已有字节码可通过 `analyzer.bind(classDirectory, dependencyDirectory)` 接入；
指定方法及下游 SQL 的分析入口见 [Analyzer 使用说明](jsqlint-core/ANALYSIS.md)。

## 常用配置

### 编译缓存与分析统计

多次分析同一项目时，可启用 AUTO 构建缓存。分析统计默认关闭，通过 `withStatistics(true)` 收集本次请求的耗时：

```java
import java.nio.file.Path;
import org.u80dc.jsqlint.commons.location.BuildPolicy;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.core.AnalysisOptions;
import org.u80dc.jsqlint.core.Analyzer;
import org.u80dc.jsqlint.core.AnalyzerConfig;

var inputs = InputLocationOptions.defaults()
        .withBuildPolicy(BuildPolicy.AUTO)
        .withProjectCacheDirectory(Path.of("cache/jsqlint-projects"));
var analyzer = new Analyzer(AnalyzerConfig.defaults().withInputOptions(inputs));
var result = analyzer.bind(Path.of("path/to/project"))
        .analyze(AnalysisOptions.defaults().withStatistics(true));

result.statistics().ifPresent(statistics -> {
    System.out.println(statistics.elapsedNanos());
    System.out.println(statistics.phases());
    System.out.println(statistics.measurements());
});
```

| 策略 | 行为 |
| --- | --- |
| `ALWAYS`（默认） | 每次执行目标项目构建 |
| `AUTO` | 校验构建配置、已记录输入和产物；缺失、损坏、内容变化或到期时构建新快照 |
| `REUSE` | 读取历史产物，跳过源码新鲜度和 TTL 校验；缓存缺失或损坏明确失败 |

AUTO 默认最长复用 24 小时，未指定缓存目录时使用 `${java.io.tmpdir}/.jsqlint-project-cache`。
分析结束或清理单次编译输出不会删除成功的缓存快照，每次调用仍会执行 SQL 分析。
已有项目也可通过 `ProjectInputs` 直接打开，或保存 `CompiledProjectManifest` 跨进程复用。
详细配置见 [产物复用](jsqlint-commons/README.md#复用已编译项目) 和 [分析统计](jsqlint-core/ANALYSIS.md#分析统计)。

## 结果与支持范围

`result.rawSqls()` 返回原始 SQL 候选及参数来源，`result.samples()` 返回渲染后的统一样例。
SQL 占位符与绑定值分别保存，无法确定的值保留为未知；结果模型见 [结果说明](jsqlint-core/RESULT_MODEL.md)。

分析过程不执行目标业务方法或数据库 SQL。`PARTIAL` 表示存在未完成的分析维度，
应结合 `diagnostics()`、样例的 `assessment()` 和 `feasibility()` 判断结果。
自定义框架行为及未建模表达式的支持情况会通过诊断报告。

MyBatis-Plus 接纳 3.x 目标版本，具体方法、配置和插件按已实现能力处理，见 [版本兼容性](jsqlint-mybatis-plus/VERSION_COMPATIBILITY.md)。
Spring Boot 可执行 JAR 需先展开 `BOOT-INF/classes` 和内嵌依赖；其他输入约束见 [commons 使用说明](jsqlint-commons/README.md)。

## 模块与文档

| 模块 | 职责与文档 |
| --- | --- |
| [jsqlint-core](jsqlint-core/ANALYSIS.md) | 统一分析入口、结果模型、值追踪、动态 SQL 渲染和模块 SPI |
| [jsqlint-commons](jsqlint-commons/README.md) | 输入适配、Maven/Gradle 编译、工具链配置和产物缓存复用 |
| [jsqlint-jdbc](jsqlint-jdbc/README.md) | JDBC SQL 调用、参数绑定和批次分析 |
| [jsqlint-mybatis](jsqlint-mybatis/README.md) | XML、注解、Provider 和 Mapper 调用适配 |
| [jsqlint-mybatis-plus](jsqlint-mybatis-plus/README.md) | 实体映射、CRUD、Wrapper 及框架插件 SQL 适配 |
| [jsqlint-test](jsqlint-test/README.md) | 集中测试、集成验证与运行说明 |
| [shared-test-resources](shared-test-resources/README.md) | 可独立构建的目标项目与使用样例 |

## 开发与贡献

测试统一位于 `jsqlint-test`。从仓库根目录显式启用测试：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Linux / macOS 将命令替换为 `./mvnw`，其余参数相同。
部分集成测试需要本地目标项目、指定 JDK 或 Gradle 安装，具体条件见 [测试说明](jsqlint-test/README.md)。

欢迎提交问题和改进。反馈分析问题时，请提供目标构建方式、JDK 与框架版本、最小复现项目或方法，
以及分析结果和诊断。修改分析行为时，请在 `jsqlint-test` 中补充对应验证，并同步相关模块的使用说明。
