# jsqlint-commons

公共基础模块，包路径为 `org.u80dc.jsqlint.commons`。`location` 包提供统一输入入口
`AutoCompileAnalysisInputLocation implements AnalysisInputLocation`，可直接传给 SootUp 的 `JavaView`。

## 输入识别与处理

| 输入类型 | 识别条件 | 处理方式 |
| --- | --- | --- |
| 已编译字节码 | JAR 文件，或包含 `.class` 的 classpath 根目录 | `JavaClassPathAnalysisInputLocation`，不执行编译 |
| 普通 Java 源码 | 单个 `.java`、多个 `.java` 或源码目录 | 汇总源码文件后，一次性交给 `OTFCompileAnalysisInputLocation` 编译 |
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
其中 `JAVA_SOURCE` 强制使用 OTF 源码路径。输入不合法时抛出 `IllegalArgumentException`；
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
