# jsqlint-test

项目统一测试模块。所有模块的测试代码放在 `src/test/java`，测试资源放在
`src/test/resources`；测试专用的辅助类也放在测试源码目录。

测试沿用被测代码的 Java 包名，例如核心节点测试位于
`src/test/java/org/u80dc/jsqlint/core/node`。当前包含节点解析、渲染、表达式求值、
通用模板、节点结构相等性与不可变性、数值真假判断的测试，以及 commons 统一输入的
源码/字节码加载、Maven/Gradle 实际构建与 Jimple 转换测试。

JUnit 依赖由本模块声明，版本由父 POM 的 JUnit BOM 管理。
本模块以 `test` scope 依赖被测模块，目前为 `jsqlint-core`、`jsqlint-commons`；新增其他模块的测试时，
在本模块添加对应的测试范围依赖。业务模块无需反向依赖本模块。
当前还以 test scope 引入 jdbc、mybatis，验证统一分析入口、SPI、字节码与源码输入、
资源筛选、框架起点、值传播、取消、超时和输入关闭约定。
`JdbcDataFlowTest` 验证 SQL/绑定变量追到 main 或顶层方法、跨方法工厂返回与绑定、接口调用、
局部及字段别名、覆盖/清空、分支、批次快照、命名参数和 setNull；递归及不确定来源应保留并标为不完整。
回归用例还覆盖数组元素及跨方法写入、覆盖顺序、默认值、不确定别名/下标、批处理的可能别名清理与加入、
确定空批次和未知批次的区别，以及项目与依赖库混合虚分派的返回值、Statement 来源和副作用。
依赖库测试将辅助类单独编译并作为依赖输入，避免误把库方法当作项目方法分析。
还验证依赖接口/抽象类的开放分派边界、String 构造参数与未知对象内容、嵌套容器/静态字段逃逸、
连续批次的正常与异常执行、参数绑定保留，以及每条 SQL 的来源完整性与模板恢复诊断隔离。
`JdbcRawSqlTest` 验证基于传播链的 raw SQL 恢复：变量命名和重复位置、原始文本保真、跨方法 SQL 参数转发、
String.concat 与 quote 辅助方法、分支/数组、JDBC `?` 独立绑定、未知操作、同名及转换后变量隔离、
null/字符/布尔转换、缺少调试信息时的参数名称，以及候选数量和字符串扩张的预算限制。
还验证分支调用分别关联各自的传播树、不同方法或不同 return 返回相同文本时保留多条结果、
多个拼接分支的组合来源，以及共用 SQL 执行方法时不同调用方的调用链隔离。
`AnalysisResult.rawSqls()` 的验证覆盖空结果、不完整候选保留、相同文本的不同调用路径保留，
以及共享 Maven 项目编译输出清理后仍可读取不可变的 raw SQL 列表。

## 运行

从仓库根目录执行，`-am` 会一并构建被测模块：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' test
```

全项目验证：

```powershell
.\mvnw.cmd '-Dmaven.test.skip=false' '-DskipTests=false' clean verify
```

Linux / macOS 将命令替换为 `./mvnw`，参数相同。
项目 `.mvn/maven.config` 默认设置 `maven.test.skip=true`，因此需显式覆盖。
测试报告位于 `jsqlint-test/target/surefire-reports`。

## JDBC 共享样例分析

`JdbcUserQueryAnalysisTest` 直接将 `shared-test-resources/jdbc-user-query` 项目目录交给 Analyzer，
由自动发现的 commons 编译器执行 Maven 构建，随后检查 5 个增删改查入口及 1 个建表入口。
测试验证关键 SQL 文本、未知参数和动态条件的保留、8 个调用点的 SQL 来源及通往 main 的调用链，
以及分析结束后编译输出的清理；不运行样例主程序。
还逐项校验 CRUD raw SQL 中的业务参数占位符，以及三个可选查询条件产生的八种文本候选。
尚未支持的表达式允许产生 PARTIAL，编译失败、解析异常、超时及追踪预算耗尽不能通过测试。

仅运行该用例：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' '-Dtest=JdbcUserQueryAnalysisTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

该测试无需预编译样例或启动数据库；首次运行可通过 Maven Central 下载样例的 H2 依赖。

Gradle 集成测试可以通过 `'-Djsqlint.test.gradle.home=C:\path\to\gradle'` 指定已有 Gradle 安装，
否则使用 Tooling API 的默认发行版，首次运行可能下载。其余基于临时项目的 Maven 输出目录测试使用离线模式，
依赖当前构建已解析的编译插件。详细输入行为见 [commons 使用说明](../jsqlint-commons/README.md)。
