# JSQLint

面向数据密集型应用的静态 SQL 分析项目，目前正在重构。

## 项目结构

| 模块 | 说明 |
|---|---|
| `jsqlint-analysis` | 旧 SQL 静态分析引擎，保留参考，不参与新 core 流程 |
| `jsqlint-commons` | 统一 SootUp 输入，支持字节码、Java 源码及 Maven/Gradle 项目自动编译（[使用说明](jsqlint-commons/README.md)） |
| `jsqlint-core` | 统一分析入口、资源与模块 SPI、SQL 值追踪及动态节点渲染（[分析器说明](jsqlint-core/ANALYSIS.md)） |
| `jsqlint-jdbc` | 可选 JDBC 调用点与 SQL 参数适配 |
| `jsqlint-mybatis` | 可选 MyBatis XML、include 和 SQL 注解适配 |
| `jsqlint-test` | 集中存放所有模块的测试代码与测试资源（[使用说明](jsqlint-test/README.md)） |

## 构建

需要 JDK 21 或更高版本。项目提供 Maven Wrapper，无需单独安装 Maven。

```bash
# Linux / macOS / Git-Bash
./build.sh

# Windows
build.bat
```

脚本构建父 POM 中声明的全部模块，JAR 产物保存在各模块的 `target/` 目录。
额外参数会传递给 Maven，例如 `build.bat -o` 使用离线模式。

也可以直接使用 Maven Wrapper：

```bash
./mvnw package
# Windows
.\mvnw.cmd package
```

## 测试

所有测试统一放在 `jsqlint-test/src/test/java`，测试资源放在该模块的 `src/test/resources`。
项目默认跳过测试；在仓库根目录显式启用，并通过 `-am` 构建被测模块：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Linux / macOS 使用相同参数，将命令替换为 `./mvnw`。
