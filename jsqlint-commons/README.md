# jsqlint-commons

引入本模块后，`core.Analyzer` 可通过 ServiceLoader 自动发现
`AutoCompileInputResolver`，在 Path 入口按需编译源码及 Maven/Gradle 项目。
编译产物交回 core 分析，core 无需反向依赖 commons。配置构建参数时可显式注册
`new AutoCompileInputResolver(inputLocationOptions)`。
统一入口、输入所有权及取消机制见 [分析器说明](../jsqlint-core/ANALYSIS.md)。

公共基础模块，包路径为 `org.u80dc.jsqlint.commons`。`location` 包提供统一输入入口
`AutoCompileAnalysisInputLocation implements AnalysisInputLocation`，可直接传给 SootUp 的 `JavaView`。

包职责划分：

- `compiler`：Maven、Gradle、普通 Java 源码的编译实现，以及构建方式识别、源码收集、
  编译分派和产物与依赖校验。普通源码的 OTF 调用和指定输出目录的 JDK 编译均由此包负责。
- `location`：统一输入适配、输入选项与参数校验，将编译产物接入 SootUp。

## 输入识别与处理

| 输入类型 | 识别条件 | 处理方式 |
| --- | --- | --- |
| 已编译字节码 | JAR 文件，或包含 `.class` 的 classpath 根目录 | `JavaClassPathAnalysisInputLocation`，不执行编译 |
| 普通 Java 源码 | 单个 `.java`、多个 `.java` 或源码目录 | 默认由 `OTFCompileAnalysisInputLocation` 编译；指定 class 输出目录时使用 JDK 编译器 |
| Maven | `pom.xml` | Maven Embedder 执行 `compile`，通过有效 reactor 模型读取各模块输出目录及依赖 |
| Gradle | `build.gradle(.kts)` 或 `settings.gradle(.kts)` | Tooling API 执行 main source set 的 classes 任务，通过临时 init script 收集输出目录和 classpath |

自动识别优先检查传入目录的构建文件，因此项目已经有编译产物时仍会执行构建。
普通目录优先查找源码；同时存在 `.java` 和旧 `.class` 时重新编译源码。
源码目录依次选择 `src/main/java`、`src`、传入目录，递归收集 `.java`；选择项目的 `src` 时排除 `src/test`。
显式传入源码目录时，`build`、`out`、`test` 等合法包名不会被过滤。
字节码目录必须是包路径的根目录，例如 `target/classes`，而非其中的包目录或单个 `.class` 文件。

`List<Path>` 可以传入多个源码文件/目录，或者多个字节码目录/JAR。多个源码输入会合并编译，
支持相互引用；这两种输入不能混用。Maven/Gradle 输入每次接收一个项目根目录，支持其内部多模块。
同时存在 Maven、Gradle 标志时会报错，可通过 `InputLocationOptions.inputMode()` 明确选择。
`InputMode` 表示调用方选择的处理方式，可选 `AUTO`、`JAVA_SOURCE`、`MAVEN_PROJECT`、`GRADLE_PROJECT`；
其中 `JAVA_SOURCE` 强制按普通 Java 源码处理。输入不合法时抛出 `IllegalArgumentException`；
输入扫描、构建或 OTF 编译失败时抛出 `AnalysisInputException`，保留诊断信息，不回退到已有字节码。

## 使用

```java
import java.nio.file.Path;
import java.util.List;
import org.u80dc.jsqlint.commons.location.AutoCompileAnalysisInputLocation;
import sootup.java.core.views.JavaView;

// 自动识别 Maven/Gradle 项目、普通源码目录、class 目录或 JAR。
try (var input = new AutoCompileAnalysisInputLocation(Path.of("/path/to/project"))) {
    JavaView view = input.createView(); // 包括构建依赖及当前 JDK 运行时
    var type = view.getIdentifierFactory().getClassType("example.Example");
    var clazz = view.getClass(type).orElseThrow();
    clazz.getMethods().stream().filter(method -> method.hasBody())
            .forEach(method -> System.out.println(method.getBody())); // 惰性转换为 Jimple
}

// 多个源码文件也可以分别位于不同目录。
try (var input = new AutoCompileAnalysisInputLocation(
        List.of(Path.of("/src/Example.java"), Path.of("/src/Helper.java")))) {
    JavaView view = new JavaView(input);
}
```

目标代码默认为 `SourceType.Application`；构建工具解析的依赖以及额外 classpath 自动以
`SourceType.Library` 接入。`new JavaView(input)` 包括目标代码和这些依赖，`createView()` 额外加入当前
JDK 的运行时类。完整构造器可自定义目标 `SourceType` 与 body interceptors。
`getInputType()` 返回实际识别的 `InputType`：`BYTECODE`、`JAVA_SOURCE`、`MAVEN_PROJECT` 或 `GRADLE_PROJECT`，
不包含 `AUTO`。`getDependencies()` 返回接入的依赖路径。

`InputLocationOptions(inputMode, classpath, buildArguments, gradleInstallation)` 可设置额外分析依赖、
Maven/Gradle 构建参数及显式 Gradle 安装目录。`classpath` **仅用于分析**；Maven/Gradle 的编译依赖
由目标项目配置决定。SootUp 3.0.1 的 OTF 没有编译 classpath 或 javac 参数配置接口，沿用当前 JVM
的编译 classpath 和默认编译参数，因此不再提供自定义 `javacOptions`。
需要额外编译依赖或编译选项时，应使用 Maven/Gradle 项目，或传入已编译的字节码。

### 调用时指定 class 输出目录

```java
var options = InputLocationOptions.defaults()
        .withClassOutputDirectory(Path.of("/path/to/output/classes"));
try (var input = new AutoCompileAnalysisInputLocation(Path.of("/path/to/project"), options)) {
    JavaView view = input.createView(); // 直接分析指定目录中本次编译的产物
}
```

也可以使用五参数构造器
`InputLocationOptions(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory)`。
原四参数构造器继续可用；输出目录为 `null` 时保留原有行为。
通过 `new AutoCompileInputResolver(options)` 注册解析器时，Analyzer 的 Path 入口也会使用该目录。

该路径是**编译输出目录**，不是单个 `.class` 文件路径，也不是额外的分析 classpath。
相对路径以调用进程的工作目录为基准，目录不存在时自动创建。
输出目录不能是已有文件、输入路径本身或输入路径的祖先目录；允许项目内的输出子目录。
字节码目录和 JAR 不执行编译，若同时指定此选项会报错。

- 普通源码：包路径直接位于指定目录下。OTF 3.0.1 未提供输出目录接口，因此此时使用
  JDK `JavaCompiler` 设置 `CLASS_OUTPUT`，编译 classpath 和默认参数与 OTF 保持一致。
- Maven：只有一个参与编译的非 `pom` 模块时直接输出到指定目录；多个模块分别输出到
  `group-<groupId>/module-<artifactId>` 子目录，标识使用 URL 编码。
  本次构建会同步调整 reactor 模型及标准 compiler/resources 插件参数，保留模块间依赖。
- Gradle：只有一个 main source set、一个编译任务时直接输出到指定目录；多个项目按
  `project-<URL编码后的项目路径>` 隔离，例如 `project-%3Aapp`。同一 source set 有多个编译任务时，
  再按任务名分目录。自定义编译任务需要通过 `destinationDirectory` 暴露输出目录。

Maven/Gradle 的配置调整仅在本次构建内生效，不修改目标项目的构建文件。
此选项仅指定 class 产物位置，不迁移整个 `target`/`build` 目录或构建缓存。
输出可复用，但不会自动清空，删除源码后遗留的旧 class 需要调用方自行清理。
编译失败会抛出异常，不使用已有 class 继续分析；失败时可能留下部分产物。
`close()` 不删除指定目录及其内容，生命周期由调用方管理。

Maven、Gradle 后端支持自定义输出目录，分析范围为主代码。
Gradle 后端要求项目提供标准 main source set，Android 等不同构建模型需要额外适配。
Maven 调用会串行执行，并恢复宿主的 Maven 多模块目录属性、上下文类加载器与标准流。
`close()` 转发 SootUp 输入的关闭操作，不删除调用方或项目的字节码产物。
SootUp 3.0.1 的 OTF 未暴露临时输出目录，也未实现清理操作，其临时产物目前会保留；
Gradle 构建过程中由本模块创建的临时脚本和元数据目录会自动删除。

## 构建调用依赖

Maven 依赖参考 main 分支原有的 `jsqlint-common/pom.xml` 与 `MavenCompiler`：
使用 Maven Embedder，通过 `org.apache.maven.cli.MavenCli` 在当前 JVM 中执行构建。

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `org.apache.maven:maven-embedder` | `3.9.16` | 嵌入式 Maven 构建入口 |
| `org.apache.maven.resolver:maven-resolver-connector-basic` | `1.9.27` | Maven 仓库连接 |
| `org.apache.maven.resolver:maven-resolver-transport-http` | `1.9.27` | Maven 仓库 HTTP 传输 |
| `org.apache.maven.wagon:wagon-http` | `3.5.3` | 沿用原有 Wagon HTTP 支持 |
| `org.codehaus.plexus:plexus-utils` | `3.6.1` | Maven/Plexus 工具支持 |
| `org.gradle:gradle-tooling-api` | `9.8.2` | 通过 `GradleConnector` 调用 Gradle 构建 |

Gradle Tooling API 从官方仓库 `https://repo.gradle.org/gradle/libs-releases` 获取。
Tooling API 的版本是调用库版本；目标工程实际使用的 Gradle 发行版由工程 Wrapper
或调用时的显式配置选择，运行构建时可能需要下载该发行版。

日志依赖继承父 POM 的 SLF4J/Logback 配置；Maven 依赖沿用原实现的
`slf4j-simple` 排除配置。测试统一放在 `jsqlint-test` 模块。

## 验证

从仓库根目录执行：

```powershell
.\mvnw.cmd -pl jsqlint-commons -am verify
```

实际编译与 Jimple 转换测试：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Gradle 集成测试默认使用 Tooling API 的默认发行版，首次运行可能下载 Gradle。
可以添加 `'-Djsqlint.test.gradle.home=C:\path\to\gradle'` 使用已有安装；测试使用的 Gradle
须支持在当前 JDK 上运行。Maven 测试使用离线模式，依赖当前构建已解析的编译插件。
