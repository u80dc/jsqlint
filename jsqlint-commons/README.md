# jsqlint-commons

引入本模块后，`core.Analyzer` 可通过 ServiceLoader 自动发现
`AutoCompileInputResolver`，在 Path 入口按需编译源码及 Maven/Gradle 项目。
编译产物交回 core 分析，core 无需反向依赖 commons。用户可在调用前构造 InputLocationOptions，
通过 `AnalyzerConfig.withInputOptions(inputLocationOptions)` 交给 Analyzer，继续使用自动发现的解析器和 SQL 模块。
通过 `AnalysisOptions.withClassOutputId(...)` 为本次 Analyzer 分析指定输出标识。
Analyzer 生成 `${java.io.tmpdir}/.jsqlint/<指定标识>/`，未指定时生成
`${java.io.tmpdir}/.jsqlint/<随机标识>/`，并通过解析器将完整路径传给 `AutoCompileAnalysisInputLocation`。
直接构造自动编译输入时，仍可传入完整路径，未传入则使用 `${java.io.tmpdir}/<随机标识>/`。
Analyzer 默认在分析结束后保留目录；可通过 `AnalyzerConfig.withCleanupClassOutput(true)`
设置实例默认清理策略，也可通过 `AnalysisOptions.withCleanupClassOutput(...)` 覆盖单次策略。
复用指定标识时，无论是否开启结束清理，Analyzer 都会先清空对应输出目录。
统一入口、输入所有权及取消机制见 [分析器说明](../jsqlint-core/ANALYSIS.md)。

公共基础模块，包路径为 `org.u80dc.jsqlint.commons`。`location` 包提供统一输入入口
`AutoCompileAnalysisInputLocation implements AnalysisInputLocation`，可直接传给 SootUp 的 `JavaView`。

包职责划分：

- `compiler`：Maven、Gradle、普通 Java 源码的编译实现，以及构建方式识别、源码收集、
  编译分派和产物与依赖校验。普通源码使用 JDK 编译器写入统一输出目录，另提供 OTF 便捷入口。
- `location`：统一输入适配、输入选项与参数校验，将编译产物接入 SootUp。

## 输入识别与处理

| 输入类型 | 识别条件 | 处理方式 |
| --- | --- | --- |
| 已编译字节码 | JAR 文件，或包含 `.class` 的 classpath 根目录 | `JavaClassPathAnalysisInputLocation`，不执行编译 |
| 普通 Java 源码 | 单个 `.java`、多个 `.java` 或源码目录 | JDK 编译器输出到指定目录或随机临时目录，再交给 SootUp 字节码前端 |
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
输入扫描、构建或源码编译失败时抛出 `AnalysisInputException`，保留诊断信息，不回退到已有字节码。
Maven/Gradle 的 stdout 与 stderr 共用本次构建的有界日志，最多保留末尾 64 KiB；
超出时标记早期日志已截断，失败诊断继续保留末尾错误，避免超大输出导致内存或字符串长度溢出。
嵌入式 Maven 同时引入同版本的 `maven-compat`，向旧插件提供 `ArtifactCollector` 等兼容 API 及组件。
路径解析器分别交付 Application 与 Library 输入；core 枚举应用代码时不遍历依赖 JAR，
依赖类型仍可通过同一 JavaView 按需解析。直接使用自动编译输入位置时仍可枚举全部类。

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
`getClassOutputDirectory()` 返回实际使用的编译输出根目录；已有字节码输入返回 `null`。

`InputLocationOptions(inputMode, classpath, buildArguments, gradleInstallation)` 可设置额外分析依赖、
Maven/Gradle 构建参数及显式 Gradle 安装目录。`classpath` **仅用于分析**；Maven/Gradle 的编译依赖
由目标项目配置决定。普通源码沿用当前 JVM 的编译 classpath 和默认编译参数，
与原 OTF 方式保持一致，不提供自定义 `javacOptions`。
需要额外编译依赖或编译选项时，应使用 Maven/Gradle 项目，或传入已编译的字节码。

### Maven 远程仓库与本地仓库

默认使用官方 Maven Central（`https://repo.maven.apache.org/maven2`），启用 release、禁用 snapshot；
本地仓库默认为 `${user.home}/.m2/repository`。Maven 专用配置位于 `compiler` 包，
先通过 `InputLocationOptions.withMavenRepositories(...)` 组装输入配置，再传给 Analyzer：

```java
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.u80dc.jsqlint.commons.compiler.MavenRemoteRepository;
import org.u80dc.jsqlint.commons.compiler.MavenRepositoryOptions;
import org.u80dc.jsqlint.commons.location.InputLocationOptions;
import org.u80dc.jsqlint.core.Analyzer;
import org.u80dc.jsqlint.core.AnalyzerConfig;

var privateRepository = new MavenRemoteRepository(
        "company", URI.create("https://repo.example.com/repository/maven-public/"))
        .withCredentials(System.getenv("MAVEN_REPO_USER"), System.getenv("MAVEN_REPO_PASSWORD"))
        .withSnapshotsEnabled(true);
var repositories = MavenRepositoryOptions.defaults()
        .withRemoteRepositories(List.of(privateRepository))
        .withLocalRepository(Path.of("D:/maven/repository"));
var inputOptions = InputLocationOptions.defaults().withMavenRepositories(repositories);
var config = AnalyzerConfig.defaults().withInputOptions(inputOptions);
var analyzer = new Analyzer(config);
var result = analyzer.analyze(Path.of("D:/projects/demo"));
```

- 私有仓库按传入顺序添加，同时用于父 POM、项目依赖和构建插件解析；保留 Central 作为后备，
  目标项目 POM 中声明的仓库仍参与 Maven 的标准解析流程。
- 如需全部通过公司私服代理，使用 `privateRepository.withMirrorOf("*")`；仅代理中央仓库可用
  `withMirrorOf("central")`。支持 Maven 的 mirrorOf 表达式，此时被匹配仓库通过私服访问。
- 仓库 ID 必须唯一，`central` 保留给官方仓库，`local` 为 Maven 保留 ID。
  账号密码按仓库 ID 关联，password 也可以是私服访问令牌；匿名仓库省略 `withCredentials`。
  支持 HTTP(S) 和 file URL，认证信息须通过独立字段提供。
- `withLocalRepository(null)` 恢复默认本地仓库。相对路径按调用进程工作目录解析，目录不存在时创建；
  本地仓库作为持久依赖缓存保留，与 class 输出目录分别配置，不随编译输出清理。
- 每次构建生成独立的临时 settings 文件，成功、失败时均删除；不会修改项目 POM 或宿主 settings。
  默认不读取用户或 Maven 安装目录的 settings.xml，以保证仓库选择不受宿主镜像配置影响。
  仓库认证信息暂存于本次 settings 文件中，配置对象的 toString 隐藏认证信息。
- 仓库配置统一通过上述选项传入，buildArguments 中的 `-s`、`-gs`、对应长参数以及
  `-Dmaven.repo.local` 会报错；其他构建参数（例如 `-o`、`-Pprofile`）仍可使用。
  Maven 专用仓库选项不影响 Gradle 或普通 Java 源码编译。

配置的实际传递顺序为：

`用户构造配置 → AnalyzerConfig → Analyzer → AutoCompileInputResolver → AutoCompileAnalysisInputLocation → MavenProjectCompiler`。

core 只依赖 `AnalysisInputOptions` 接口并传递配置，commons 的 InputLocationOptions 实现该接口，
MavenProjectCompiler 最终读取其中的 MavenRepositoryOptions；仓库类型和 Maven 依赖仍留在 commons。
Analyzer 传递本次 class 输出路径时保留仓库及构建参数；它生成的路径优先于 inputOptions 中的输出目录。

如需显式选择 SQL 模块或解析器，也可以传入配置：

```java
var analyzer = new Analyzer(config,
        List.of(new JdbcAnalysisModule(), new MybatisAnalysisModule()), List.of(new AutoCompileInputResolver()));
var result = analyzer.analyze(Path.of("D:/projects/demo"));
```

Analyzer 的 inputOptions 非空时完整替换解析器自身的选项，不逐字段合并；
`withInputOptions(null)` 恢复使用解析器自身选项。原有 `new AutoCompileInputResolver(inputOptions)`
及 `new AutoCompileAnalysisInputLocation(project, inputOptions)` 直接调用方式仍可使用。
编译配置仅用于 Path 入口，已有字节码、输入列表和 AnalysisRequest 不会因此重新编译。
原有 InputLocationOptions 四参数、五参数构造器继续可用，使用默认 Maven 仓库配置。

### 调用时指定 class 输出目录

通过 Analyzer 的 Path 入口指定输出标识：

```java
var config = AnalyzerConfig.defaults().withCleanupClassOutput(true);
var analyzer = new Analyzer(config);
var options = AnalysisOptions.defaults().withClassOutputId("my-project");
var result = analyzer.analyze(Path.of("/path/to/project"), options);
// 编译输出路径：${java.io.tmpdir}/.jsqlint/my-project/
// 继承实例配置，分析结束后清理；本次需要保留时使用 options.withCleanupClassOutput(false)。
```

直接使用自动编译输入时，通过其输入选项传入：

```java
var options = InputLocationOptions.defaults()
        .withClassOutputDirectory(Path.of("/path/to/output/classes"));
try (var input = new AutoCompileAnalysisInputLocation(Path.of("/path/to/project"), options)) {
    JavaView view = input.createView(); // 直接分析指定目录中本次编译的产物
}
```

也可以使用五参数构造器
`InputLocationOptions(inputMode, classpath, buildArguments, gradleInstallation, classOutputDirectory)`。
原四参数构造器继续可用。Analyzer 的输出标识必须是单个非空目录名，不能是路径、`.` 或 `..`；
同一标识复用同一路径，每次编译前先递归清空目录内容，避免保留已删除源码对应的旧 class。
未指定标识时每次调用生成随机标识。并发调用应使用不同标识或默认随机标识。
Analyzer 始终传入自己生成的完整路径，覆盖本次调用中解析器的输出配置，但不修改解析器实例。
`withCleanupClassOutput(true)` 在分析结束、关闭输入后删除本次输出目录，失败、取消及超时也执行；
系统默认值为 false。单次选项未设置（null）时继承 AnalyzerConfig；显式 false 覆盖实例的清理策略，
`withCleanupClassOutput(null)` 恢复继承。清理失败会报告诊断并返回 FAILED。
扩展解析器通过 `AnalysisInputResolver.requiresCompilation(source)` 声明是否需要编译，
只有返回 true 时 Analyzer 才管理编译输出的预清理及结束清理。
直接使用解析器或自动编译输入时，`InputLocationOptions.classOutputDirectory` 仍接受完整路径。

该路径是**编译输出目录**，不是单个 `.class` 文件路径，也不是额外的分析 classpath。
相对路径以调用进程的工作目录为基准，目录不存在时自动创建。
输出目录不能是已有文件、输入路径本身或输入路径的祖先目录；允许项目内的输出子目录。
直接构造 `AutoCompileAnalysisInputLocation` 时，字节码目录和 JAR 不执行编译，同时指定输出选项会报错。
Analyzer 的字节码入口由 core 解析器处理，会忽略编译输出选项。

- 普通源码：包路径直接位于输出目录下。OTF 3.0.1 未提供输出目录接口，因此使用
  JDK `JavaCompiler` 设置 `CLASS_OUTPUT`，编译 classpath 和默认参数与 OTF 保持一致。
- Maven：只有一个参与编译的非 `pom` 模块时直接输出到指定目录；多个模块分别输出到
  `group-<groupId>/module-<artifactId>` 子目录，标识使用 URL 编码。
  本次构建会同步调整 reactor 模型及标准 compiler/resources 插件参数，保留模块间依赖。
- Gradle：只有一个 main source set、一个编译任务时直接输出到指定目录；多个项目按
  `project-<URL编码后的项目路径>` 隔离，例如 `project-%3Aapp`。同一 source set 有多个编译任务时，
  再按任务名分目录。自定义编译任务需要通过 `destinationDirectory` 暴露输出目录。

Maven/Gradle 的配置调整仅在本次构建内生效，不修改目标项目的构建文件。
此选项仅指定 class 产物位置，不迁移整个 `target`/`build` 目录或构建缓存。
直接使用解析器或 `AutoCompileAnalysisInputLocation` 时，输出目录不会自动清空，
`close()` 也不删除目录；调用方自行管理其生命周期。通过 Analyzer 使用时执行上述预清理和可选结束清理。
编译失败会抛出异常，不使用已有 class 继续分析；未开启结束清理时可能留下部分产物。

Maven、Gradle 后端支持自定义输出目录，分析范围为主代码。
Gradle 后端要求项目提供标准 main source set，Android 等不同构建模型需要额外适配。
Maven 调用会串行执行，并恢复宿主的 Maven 多模块目录属性、上下文类加载器与标准流。
`close()` 转发 SootUp 输入的关闭操作，不删除调用方或项目的字节码产物。
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

## SQL 指纹

`utils.FingerprintUtils` 按词法模板归一化 SQL，随后计算 UTF-8 SHA-256 指纹。
现有 `normalize(sql)`、`fingerprint(sql)`、`isSameStructure(a, b)` 入口继续可用，
默认采用 ANSI 规则：单引号为字符串，双引号为标识符。需要根据目标数据库显式选择方言，
不会从 SQL 文本猜测双引号含义。

同一方言及配置下，SQL 的换行、空格、关键字大小写、普通注释及优化器提示差异不影响指纹；查询结构相同而条件
字面值不同时也生成相同指纹。例如 `SELECT id FROM users WHERE age=18` 与
`select id FROM users /* note */ WHERE age = 65` 归一化为同一个 `select id from users where age = ?`。
表名、字段名、比较运算符及 AND/OR 组合仍属于结构；不会仅因字段集合相同就合并不同查询。

```java
import org.u80dc.jsqlint.commons.utils.FingerprintUtils;
import org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect;
import org.u80dc.jsqlint.commons.utils.FingerprintUtils.Options;

String template = FingerprintUtils.normalize("SELECT * FROM users WHERE id=-12");
// select * from users where id = ?
String fingerprint = FingerprintUtils.fingerprint("SELECT $$a'--b$$ FROM users", Dialect.POSTGRESQL);
String oracleTemplate = FingerprintUtils.normalize(
        "SELECT q'[O'Neil]' FROM dual WHERE id=:1", Dialect.ORACLE);
// select ? from dual where id = :1

var options = new Options(Dialect.MYSQL, false) // 保留 IN 列表长度
        .withMysqlAnsiQuotes(true)              // 对应 MySQL ANSI_QUOTES
        .withMysqlBackslashEscapes(false);      // 对应 MySQL NO_BACKSLASH_ESCAPES
boolean same = FingerprintUtils.isSameStructure(sql1, sql2, options);
```

- 字符串、十六进制/十进制/科学计数法数值及布尔常量替换为 `?`，保留二元运算符。
  值位置的 `NULL` 也替换为 `?`（例如 `age=NULL`），`IS NULL`、`IS NOT NULL`、列约束及
  常见 PL/SQL `NULL;` 语句保留。字符串或引号标识符内部的注释符不会被当作注释处理。
- ANSI/PostgreSQL/Oracle 的非引号单词使用 `Locale.ROOT` 折叠大小写；引号标识符原样保留。
  MySQL 折叠保留字，以及按语法位置识别的 `END`、`OFFSET`、`VALUE` 等非保留关键字、
  常用内置函数名、时间类型和单位；保守保留表名、限定名称与绑定名称，避免合并不同对象。
- PostgreSQL 支持 dollar 字符串、`E'...'`、嵌套注释及常见 JSON 运算符；普通字符串假定
  `standard_conforming_strings=on`。MySQL 默认双引号为字符串，启用反斜杠转义，
  支持反引号标识符、`#` 注释及要求后继空白的 `--` 注释。
- Oracle 支持 `q'[...]'`、`q'{...}'`、`q'(...)'`、`q'<...>'` 及自定义字符分隔符，
  同时支持 `N'...'`、`NQ'...'` 和前缀大小写变体。替代引号内部的单引号、注释符按字符串内容处理；
  无效分隔符或未闭合的替代引号会报告位置。普通 Oracle 字符串不启用反斜杠转义。
  `f/F/d/D` 数值后缀以及 `BINARY_FLOAT/DOUBLE_NAN/INFINITY` 常量整体替换为 `?`。
  标识符中的 `$`、`#` 及数字保留，数据库链接名、序列名和双引号标识符不会被当作常量屏蔽。
  `DATE`、`TIMESTAMP`、`INTERVAL` 的类型关键字和单位保留，仅屏蔽字面值。
- 默认将仅含常量或 JDBC `?` 的平面 `IN` 列表折叠为 `IN (?)`，可通过
  `Options.defaults().withCollapseInLists(false)` 禁用。表达式、行值、子查询不折叠；
  命名参数和 PostgreSQL `$1`、Oracle `:1` 等位置参数保留名称/编号，也不折叠其所在的列表。
  Oracle 还支持 `:user_id`、`:user#1` 及 `:"Case Name"` 等绑定形式；绑定名称保守保留原始大小写。
- 普通行/块注释、优化器提示 `/*+ ... */` 及 Oracle `--+ ...` 行提示不参与指纹；
  注释按所选方言识别。
  MySQL 的 `--` 后必须有空白或控制字符，否则保留为运算符序列。
  统一 token 之间的空格、换行、制表符及 Unicode 空白，保持引号标识符内部内容不变；
  统一标点周围空白，忽略末尾分号，保留多个语句之间的分号。
- MySQL 可执行注释 `/*! ... */` 中的 SQL 与外部 SQL 共用词法上下文，参与关键字、常量和
  IN 列表归一化。例如 `SELECT /*! DISTINCT */ name FROM users` 与
  `SELECT DISTINCT name FROM users` 生成相同指纹，与没有 DISTINCT 的查询区分。
  紧随 `/*!` 的五/六位版本前缀被去掉，例如 `/*!80000 DISTINCT */`；空白之后的数字仍属于
  SQL 字面值，例如 `/*! 80000 */`。目前没有目标 MySQL 版本配置，因此保留全部可执行片段，
  不判断版本条件在实际服务器上是否成立。其他方言仍将 `/*! ... */` 当作普通注释。
- null、空白及仅含被忽略注释或空可执行片段的输入返回空字符串；null 与空字符串视为相同空模板。
  未闭合引号、dollar 字符串或块注释抛出带位置的 `IllegalArgumentException`。

这是轻量词法归一化工具，不是完整 SQL 解析器或语义等价判定器，也不覆盖所有方言扩展。
Oracle 的 `CONNECT BY PRIOR`、`START WITH`、`RETURNING INTO`、分页、旧式外连接 `(+)`、
命名实参 `=>` 及 PL/SQL 范围 `..` 等结构可保留；不解析 SQL*Plus 命令或执行 PL/SQL 语义分析。
Oracle 字面量规则参考 [Oracle SQL Language Reference](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Literals.html)。
指纹适合对模板分组；各条原始 SQL、参数候选及传播链仍应保留，不能仅凭指纹删除分析结果。
比较或持久化指纹时应使用同一配置。此次归一化规则已修正，旧版本保存的指纹需要重新生成。

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
