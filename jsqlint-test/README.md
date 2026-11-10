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

`SqlNodeBoundRendererTest` 验证参数化 SQL 的有序绑定、未知值、作用域回滚和渲染预算。
`MethodParameterMetadataTest` 验证 class/JAR 的参数名称、可见/不可见注解与完整重载签名。
`MybatisDefinitionTest`、`MybatisExtractionTest` 验证定义索引、databaseId、include、继承、重复定义、
@Param/argN/paramN、特殊参数跳过、标量跨方法追踪、混合 JDBC、会话和取消语义。
`MybatisSqlSemanticsTest` 与真实 MyBatis BoundSql/OGNL 对照绑定和纯表达式行为；参数命名规则在
`MybatisExtractionTest` 中与 ParamNameResolver 对照验证。
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
`JdbcParameterInferenceTest` 验证参数沿传播链重演：字面量和外部输入、随机样例复用、
跨方法字符串转换、算术/包装/拆箱/类型转换、Java 整数溢出与除法、入口数组与元素写入、
同值不同路径保留、独立分支组合和重复操作数选择一致性，以及候选数量限制、取消、
未知操作/异常和真实 null 的区别。验证使用真实编译后的字节码，不执行样例或连接数据库。
还验证 `AnalysisResult.rawSqls()` 同时关联拼接参数与预编译绑定值：相同 SQL 文本在不同调用上下文中
保留各自参数，批次保存 addBatch 时的绑定快照，clearParameters 后不混入先前的值；关联列表不可变。
分支一致性回归覆盖同一辅助方法的独立调用、同一值原样转发及局部变量重新赋值；
字符串转换回归覆盖数值/null 拼接后的替换、布尔/字符转换后的连续替换，验证转换结果和原始输入同时保留。
共享 JDBC 样例还验证 alice、bob、O'Neil 的 INSERT 参数候选，确认转义后的 O''Neil
及其初始字面量、传播链被保存在 AnalysisResult 中。
`JdbcJointPathInferenceTest` 验证 SQL 与多个绑定值的同分支配对、重复条件与整数范围冲突、
槽位和绑定值使用同一入口模型重演、字面量导致的不可达 SQL、独立 helper 调用、switch/default、
null、动态绑定下标和 long 边界溢出；未知条件、循环和组合预算必须显式标为 UNKNOWN。
还验证相同条件下的后续 setter 覆盖、clearParameters，以及不同 addBatch 入口的条件与绑定快照。
`JdbcJointPathOptimizationTest` 验证条件辅助方法的嵌套调用、多个返回位置、虚调用目标和 switch 条件，
以及开放分派、递归和循环的未知边界；无关分支和等价 JDBC 快照历史应合并，相关赋值、提前返回和
未完整覆盖的历史条件必须保留。十个参数共享同一布尔分支时应只生成两组相容组合。
`JdbcJointConsistencyTest` 验证同次虚调用在 SQL、多个参数和条件间共享目标，嵌套调用和外部分派保持一致，
不同调用仍独立选择；同时验证数组别名/跨方法传递/拼接下的空值模型，以及保存传播链再次重演时的
下标变化、实际数组值、越界、未知或相互冲突的数组模型。

`FingerprintUtilsTest` 覆盖字符串与注释边界、引号标识符、一元/二元运算符、数值与布尔常量、
Locale 独立性及长输入；同时验证 ANSI/MySQL/PostgreSQL 词法差异、MySQL 引号和转义模式、
PostgreSQL dollar 字符串/嵌套注释/JSON 运算符、参数名称保留、IN 列表折叠配置及未闭合输入报错。
`FingerprintUtilsOracleTest` 验证 Oracle Q/NQ 替代引号（包括成对、自定义及多字节分隔符）、
浮点后缀和特殊常量、位置/命名/引号绑定、含 `$`/`#` 的标识符、行提示、日期与间隔字面量，
以及 CONNECT BY、RETURNING、分页、旧式外连接和 PL/SQL 范围的结构保留。
`FingerprintUtilsContractTest` 按四项指纹契约跨方言验证格式差异、条件值变化、关键字大小写和
普通注释与提示忽略，同时验证不同字段、运算符、表名和引号内容仍生成不同指纹。
MySQL 回归覆盖 CASE/END、LIMIT/OFFSET、VALUE、锁定子句、窗口边界、时间类型和常用内置函数，
并检查与关键字同名的表名不会被盲目折叠大小写。
`FingerprintUtilsMysqlCommentTest` 验证可执行注释与等价展开 SQL 生成相同指纹，覆盖版本前缀、
片段内的值和格式变化、与外部 SQL 共享运算符和子句上下文、引号内的注释标记、多语句、
空片段、长输入及未闭合输入；版本前缀不会作为 SQL 数值参与归一化。

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
测试会逐条打印 raw SQL、所属方法/调用链、拼接参数和 JDBC 绑定参数的推断值，并保存 UTF-8 报告至
`target/jdbc-user-query-sql-report.txt`。样例使用字符串拼接，没有 setXxx 绑定，故 JDBC 绑定列表为空。
报告保留准备点、执行点及各调用路径的全部静态候选，不按文本去重；未知值和诊断也会保留。
同时按每组联合参数生成并打印模拟 SQL：拼接槽位使用转换后的文本，不重复转义或另加引号；
UNKNOWN 样例标明尚未证明可行，无解候选不生成模拟 SQL。未知参数保留渲染诊断，不强行替换成 null。
完整模拟 SQL 另存为按文本去重的 `target/jdbc-user-query-simulated.sql`，含联合状态与来源 SQL 编号。

Gradle 集成测试可以通过 `'-Djsqlint.test.gradle.home=C:\path\to\gradle'` 指定已有 Gradle 安装，
否则使用 Tooling API 的默认发行版，首次运行可能下载。其余基于临时项目的 Maven 输出目录测试使用离线模式，
依赖当前构建已解析的编译插件。详细输入行为见 [commons 使用说明](../jsqlint-commons/README.md)。

## MyBatis 共享样例分析

`MybatisUserQueryAnalysisTest` 将 `shared-test-resources/mybatis-user-query` Maven 项目直接交给 Analyzer，
验证 8 条 XML/注解定义、7 个 Mapper 调用、跨层标量实参、include、set、bind/if/where 和 `${}`/`#{}` 的联合样例。
测试不执行目标程序或访问数据库，并在编译目录清理后读取保存的来源和绑定快照。
XML 条件尚未加入联合求解，对应样例应保留 UNKNOWN、路径覆盖 false 和 PARTIAL 诊断。
报告保存到 `target/mybatis-user-query-sql-report.txt`。

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' '-Dtest=Mybatis*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```
