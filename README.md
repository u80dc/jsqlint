# JSQLint

面向数据密集型应用的静态 SQL 分析项目，目前正在重构。

## 项目结构

| 模块 | 说明 |
|---|---|
| `jsqlint-analysis` | SQL 静态分析引擎（SootUp 字节码分析、参数追踪、值推断） |
| `jsqlint-core` | 核心节点模型、脚本解析及支持未知值的 SQL 渲染（[使用说明](jsqlint-core/README.md)） |
| `jsqlint-jdbc` | JDBC SQL 分析模块（已建立骨架，待迁移逻辑） |
| `jsqlint-mybatis` | MyBatis SQL 分析模块（已建立骨架，待迁移逻辑） |

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
