# jsqlint-test

项目统一测试模块。所有模块的测试代码放在 `src/test/java`，测试资源放在
`src/test/resources`；测试专用的辅助类也放在测试源码目录。

当前四个 JDBC 样例的 Analyzer 重跑结果见 [JDBC SQL 提取覆盖率与正确率](JDBC_CURRENT_SQL_VALIDATION.md)。
源码调用位置按独立字节码清单逐组核对，执行模型与运行时 SQL/绑定对照；
完整恢复、已恢复结果正确率、UNKNOWN 和 Spring 初始化脚本分别计量。

共享样例的 SQL 来源/调用点覆盖、运行时 SQL 与绑定正确性，以及联合路径证明边界，见
[JDBC / MyBatis SQL 提取验证报告](SQL_EXTRACTION_VALIDATION.md)。`SampleSqlCoverageTest` 使用原始 JDBC
构造代码和真实 MyBatis BoundSql 独立对照，并输出固定种子的指标与逐模型证据。
共享目标项目与回归夹具的用法覆盖差异、尚未验证的场景及实现缺口，见
[共享样例用法覆盖检查](SAMPLE_USAGE_COVERAGE.md)。
第一批三个 JDBC 项目的审计基线见 [JDBC 样例用法审计](JDBC_SAMPLE_USAGE_AUDIT.md)。
第二批增加 jdbc-standard-api 后的标准签名、状态边界与独立核验，见
[JDBC 第二批补齐与验证](JDBC_SECOND_BATCH_VALIDATION.md)。`JdbcStandardApiCoverageTest`
同时核对编译字节码的签名清单、位置/命名绑定和数据库自检，并单列数组别名的 UNKNOWN 边界。
分批新增的独立样例和验证结果见 [样例补齐进度](SAMPLE_EXPANSION_PROGRESS.md)。
`SampleSqlCoverageTest#comparesExpandedUsageMatricesWithRuntimeAndDatabaseOracles` 检查新增两个矩阵项目，
并运行 H2 数据库自检；原始三个项目继续由独立的测试方法验证。

测试沿用被测代码的 Java 包名，例如核心节点测试位于
`src/test/java/org/u80dc/jsqlint/core/node`。当前包含节点解析、渲染、表达式求值、
通用模板、节点结构相等性与不可变性、数值真假判断的测试，以及 commons 统一输入的
源码/字节码加载、Maven/Gradle 实际构建与 Jimple 转换测试。

JUnit 依赖由本模块声明，版本由父 POM 的 JUnit BOM 管理。
本模块以 `test` scope 依赖被测模块，目前为 `jsqlint-core`、`jsqlint-commons`；新增其他模块的测试时，
在本模块添加对应的测试范围依赖。业务模块无需反向依赖本模块。
当前还以 test scope 引入 jdbc、mybatis，验证统一分析入口、SPI、字节码与源码输入、
资源筛选、框架起点、值传播、取消、超时和输入关闭约定。

`SqlNodeBoundRendererTest` 验证参数化 SQL 的有序绑定、未知值、派生来源、作用域回滚和渲染预算。
`SqlDefinitionSnapshotTest` 验证 seed 参数快照、默认读取不求值、显式样例隔离、旧构造器兼容、
定义预算在分析返回前报告，以及渲染中的取消检查。
`MethodParameterMetadataTest` 验证 class/JAR 的参数名称、可见/不可见注解与完整重载签名。
`MybatisDefinitionTest`、`MybatisExtractionTest` 验证定义索引、databaseId、include、继承、重复定义、
@Param/argN/paramN、特殊参数跳过、标量跨方法追踪、混合 JDBC、会话和取消语义。
同时验证连续 bind 的转换证据、同名绑定遮蔽与无关参数来源隔离。
`MybatisSqlSemanticsTest` 与真实 MyBatis BoundSql/OGNL 对照绑定和纯表达式行为；参数命名规则在
`MybatisExtractionTest` 中与 ParamNameResolver 对照验证。
`MybatisProviderTest` 覆盖四类 Provider、默认/继承方法选择、Map/命名参数、ProviderContext、返回分支、
SQL 构造器、真实 ProviderSqlSource 对照、目标代码不执行，以及错误声明、对象逃逸、预算和取消。
`JdbcDataFlowTest` 验证 SQL/绑定变量追到 main 或顶层方法、跨方法工厂返回与绑定、接口调用、
局部及字段别名、覆盖/清空、分支、批次快照、命名参数和 setNull；递归及不确定来源应保留并标为不完整。
回归用例还覆盖数组元素及跨方法写入、覆盖顺序、默认值、不确定别名/下标、批处理的可能别名清理与加入、
确定空批次和未知批次的区别，以及项目与依赖库混合虚分派的返回值、Statement 来源和副作用。
依赖库测试将辅助类单独编译并作为依赖输入，避免误把库方法当作项目方法分析。
还验证依赖接口/抽象类的开放分派边界、String 构造参数与未知对象内容、嵌套容器/静态字段逃逸、
连续批次的正常与异常执行、参数绑定保留，以及每条 SQL 的来源完整性与模板恢复诊断隔离。
签名回归覆盖标准重载、工厂协变返回和 SQLType setter；扩展工厂、setter、清除和批次重载
不会误用标准语义，项目包装方法仍可追踪到内部标准调用。
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
.\mvnw.cmd -pl jsqlint-test -am `
  '-Djsqlint.plus.oracle.javaHome.3.3.2=D:/Jdk/hotspot/jdk8u392-b08' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

完整测试包含真实 3.3.2 的运行时 Oracle；上述 JDK8 路径按本机安装位置调整，分析器使用 Java21。
矩阵的逐版本 JDK 设置和原因见下方 MyBatis-Plus 版本矩阵说明。

本地 DPM Mapper 用例 `MySQLDetailMapperProjectAnalysisTest` 默认禁用，显式启用后分析
`D:/workspaces/SHSNC/snc-dpm-rebuild/snc-dpm-biz` 中的
`MySQLDetailMapper.findBusynessWithNodeId(Map)`，并将 SQL 与绑定写入本模块
`target/mysql-detail-mapper-find-busyness-sql.txt`。该用例通过 commons 从父 reactor 执行
`-pl snc-dpm-biz -am`，一并编译目标模块及其兄弟模块源码依赖，
需要访问项目的 Maven 私服。可以用 `jsqlint.dpm.project` 和 `jsqlint.dpm.repository` 覆盖项目及仓库地址。

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am `
  '-Dtest=MySQLDetailMapperProjectAnalysisTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Djsqlint.dpm.test=true' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

`SysParamServiceProjectAnalysisTest` 使用同一项目和私服配置，分析
`SysParamServiceImpl#getCurrentVersion(Long)` 到 `SysParamMapper#getCurrentVersion(Map)` 的调用，
校验 `SysParamsMapper.xml` 来源、`snc_node_attr` 查询和单个 `nodeid` 绑定。
报告写入 `target/sys-param-service-get-current-version-sql.txt`；EXTRACT 模式保留未知入口参数，
另外用手工 `nodeid=42L` 验证 XML 渲染，不将该样例视为 Service 参数追踪结果。
本次真实项目运行通过，分析状态为 PARTIAL：SQL 文本和 XML 来源正确，
`HashMap.put("nodeid", nodeid)` 的键值尚未恢复，绑定诊断为 Missing MyBatis parameter: nodeid；
另保留下游 `StringUtils.isNotBlank` 未解析的诊断。
该用例默认禁用，显式运行：

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am `
  '-Dtest=SysParamServiceProjectAnalysisTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Djsqlint.dpm.test=true' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

`NodeConnConfigServiceProjectAnalysisTest` 位于 MyBatis-Plus 测试包，使用同一 DPM 项目和私服配置，
显式启用 `MybatisPlusExtension` 并读取项目资源配置，分析
`NodeConnConfigServiceImpl#get(long)` 到 `NodeConnConfigMapper#selectList(Wrapper)` 的调用。
该用例校验精确入口、具体 Mapper、common 模块中的 `NodeConnConfig` 实体及表名
`snc_node_conn_config`，报告写入 `target/node-conn-config-service-get-long-sql.txt`。
目标项目使用 MyBatis-Plus 3.3.2 和旧 `id-type=id_worker`，3.x 模型读取并兼容该配置，
用例要求恢复 `node_id = ?` 的查询 SQL，同时在 EXTRACT 模式保留未知的入口 `nodeId` 绑定。
发现阶段保留 `lambdaQuery`、Lambda 引用及 `eq` 的三条 `METHOD_SCOPE_UNRESOLVED` 诊断；
用例逐条校验这些具体调用，SQL 来源和文本需完整。
该用例默认禁用，启用后默认采用 AUTO 构建缓存；最简命令无需指定 `build-policy`：

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am `
  '-Dtest=NodeConnConfigServiceProjectAnalysisTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Djsqlint.dpm.test=true' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

该用例还支持以下项目缓存参数，项目路径及私服继续通过 `jsqlint.dpm.project`、`jsqlint.dpm.repository` 配置：

| 系统属性 | 默认值及作用 |
| --- | --- |
| `jsqlint.dpm.build-policy` | `AUTO`；可选 `ALWAYS`、`AUTO`、`REUSE`，忽略大小写及前后空白。ALWAYS 每次构建，AUTO 校验并复用缓存，REUSE 读取配置匹配的历史快照 |
| `jsqlint.dpm.cache-directory` | `${java.io.tmpdir}/.jsqlint-project-cache`；未设置或为空白时使用默认值。建议给跨进程运行指定同一绝对缓存根目录 |

AUTO 是该测试的默认策略；commons 的 `InputLocationOptions.defaults()` 仍使用 `BuildPolicy.ALWAYS`。

用完全相同的 AUTO 参数连续运行两次，可以检查目标项目的冷构建及热缓存复用：

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am `
  '-Dtest=NodeConnConfigServiceProjectAnalysisTest' `
  '-Djsqlint.dpm.test=true' `
  '-Djsqlint.dpm.cache-directory=D:/JavaProjects/jsqlint/target/dpm-project-cache' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmaven.test.skip=false' '-DskipTests=false' test
```

首次缓存缺失时构建；再次按原样执行，输入、依赖、构建配置未变化且未超过 TTL 时复用。
报告包含本次 Analyzer 请求的阶段统计和缓存计数：冷构建产生 `COMPILATION` 与 `project.cache.miss`，
热复用产生 `project.cache.hit`、`project.outputs.reused`，不执行目标项目的 `COMPILATION`。
外层运行测试的 jsqlint Maven 构建时间不计入该报告；复用后仍重新创建分析视图并执行 SQL 分析。

REUSE 跳过当前源码新鲜度和 TTL 校验，缺失或损坏的缓存明确失败。
上面的 AUTO 命令建立快照后，添加 `'-Djsqlint.dpm.build-policy=REUSE'` 即可读取同一配置下的历史产物。
测试控制属性在分析前已转换为输入选项，外层 Maven 启动命令不参与目标项目的构建身份。
AUTO 的默认 TTL 为 24 小时；库 API 可用 `withCacheMaxAge(Duration.ZERO)` 取消时间过期，内容校验仍执行。
插件的隐式项目外输入需通过 `withAdditionalBuildInputs(List<Path>)` 补充，该选项只扩展内容校验，
不改变 Maven/Gradle 的源目录或依赖配置。直接打开产物、清单 REUSE、AUTO 缓存的三阶段示例见
[commons 产物复用说明](../jsqlint-commons/README.md#复用已编译项目)。

MyBatis-Plus 第一阶段测试 `MybatisPlusAnalysisTest` 使用真实 3.5.16 框架生成的 BoundSql，
对照静态定义、继承 CRUD 调用、泛型实体、字段映射、逻辑删除、显式覆盖及会话隔离。
第二阶段 `MybatisPlusEntityParameterTest` 验证实体构造/赋值/别名/getter、跨方法调用、分支绑定关联、
外部逃逸、覆盖写入、真实 null 与预算耗尽，并用真实框架的实体参数绑定做对照。

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am '-Dtest=MybatisPlusAnalysisTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```

`BaseMapperSelectListAnalysisTest` 使用真实 3.5.16 依赖分析单参数 `BaseMapper#selectList(Wrapper)`，
覆盖原始 BaseMapper 泛型入口、具体 Mapper 的继承定义及实际 Wrapper 调用三个场景。
原始 BaseMapper 无法确定实体和表，返回带泛型诊断的 PARTIAL；具体 Mapper 可恢复完整动态定义，
未提供 Wrapper 时样例 SQL 仍为未知。受控 `NodeConnConfigMapper` 的 null、空 Wrapper 和 Lambda
`node_id=42L` 调用与真实框架 BoundSql 对照，验证列别名、SQL 文本及绑定。
测试不依赖本地 DPM 项目或私服，报告分别写入 `target/base-mapper-select-list-generic.txt`、
`target/base-mapper-select-list-definition.txt` 和 `target/base-mapper-select-list-sql.txt`。

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am '-Dtest=BaseMapperSelectListAnalysisTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```

`MybatisPlusVersionMatrixTest` 使用真实发布依赖分别编译相同受控样例，并与对应版本的
Mapper proxy/BoundSql 比较完整 SQL 和有序绑定。12 个实测版本为 `3.0-RELEASE`、`3.0.7`、
`3.1.2`、`3.2.0`、`3.3.2`、`3.4.3.4`、`3.5.0`、`3.5.3.2`、`3.5.6`、`3.5.9`、
`3.5.16`、`3.5.18.1`；每版 22 个普通/逻辑实体场景全部通过，共 264 个 SQL/绑定对照。
场景包括 CRUD、null/String/Lambda Wrapper、计数默认/显式选列、selectObjs、AUTO ID、列别名和字段顺序。
详细版本政策和独立 Service/插件验证范围见 [MyBatis-Plus 3.x 兼容性](../jsqlint-mybatis-plus/VERSION_COMPATIBILITY.md)。

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am '-Dtest=MybatisPlusVersionMatrixTest' `
  '-Djsqlint.plus.oracle.javaHome.3.3.2=D:/Jdk/hotspot/jdk8u392-b08' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```

JDK8 路径是验证机器的示例，可调整为本机路径。真实 3.3.2 的旧 Lambda 缓存在 JDK21 hidden lambda
上发生 NPE，因此仅该版本的运行时 Oracle 使用 JDK8，分析器仍在 Java21 上运行。
3.2.0 的字段顺序由 HashMap 迭代产生，本次 Oracle 与分析器使用同一 JDK21，不把全部 Oracle
统一切换为 JDK8。每版报告记录实际 Java 版本/来源，用于固定 SQL/绑定对照的执行环境。

Oracle JavaHome 依次取 `jsqlint.plus.oracle.javaHome.<version>`、全局
`jsqlint.plus.oracle.javaHome`；均未配置时使用当前 JVM 的隔离 classloader。
可用 `'-Djsqlint.plus.versions=3.0.7,3.3.2,3.5.16'` 分批运行，首次解析依赖需要访问 Maven Central。
报告位于 `target/mybatis-plus-version-matrix/<version>.txt`，独立 JVM 日志为同目录
`<version>-oracle.log`。单版本 JUnit 超时为 10 分钟、分析超时 3 分钟、独立 Oracle 超时 60 秒。

`MybatisPlusLegacyServiceTest` 单独验证真实 3.3.2 的常用 IService/ServiceImpl 调用与组件混用边界；
`MybatisPlusLegacyPluginTest` 对照真实 3.0.7/3.3.2 的 MySQL 分页和整数乐观锁协议，
现代显式租户、动态表名、MySQL/PostgreSQL 分页与整数乐观锁由 3.5.16 插件回归覆盖。

`MybatisPlusOptimisticNeverTest` 对照真实 3.3.2/3.5.16：版本字段的 `updateStrategy=NEVER`
不影响版本 WHERE 条件，原值直接取字段，覆盖计算 getter、null 和 Integer.MAX_VALUE。
未知值使用未建模的 native 返回来源；入口参数在 JOINT 中可能生成样例值，不用于代替未知来源。
`MybatisPlusExtractWrapperTest` 补充 foreach item/index 与 Wrapper 槽位名称冲突，覆盖
EXTRACT、PARAMETERS、JOINT；`PlusFrameworkArtifactMetadataTest` 验证拆分组件的 POM/Manifest
优先级及版本混用诊断，元数据副本不用于证明跨版本 SQL 正确性。

本次最终验证按测试类最新结果去重：45 类、429 个用例全部通过，无失败、错误或跳过。
逐类验证日志索引为 `target/mybatis-plus-3x-validation.md`。

全项目验证：

```powershell
.\mvnw.cmd '-Djsqlint.plus.oracle.javaHome.3.3.2=D:/Jdk/hotspot/jdk8u392-b08' `
  '-Dmaven.test.skip=false' '-DskipTests=false' clean verify
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

`ProjectReuseIntegrationTest` 验证 Maven/Gradle 的冷构建、缓存命中及输入变更重建；其中 Gradle 用例需要上述本地安装参数。
Maven 生成源码用例使用真实 `replacer:1.5.3` 插件，首次运行可能从 Maven Central 下载，
并验证项目外模板、源码目录中的生成文件及普通源码的缓存边界。

## MyBatis 共享样例分析

`MybatisUserQueryAnalysisTest` 将 `shared-test-resources/mybatis-user-query` Maven 项目直接交给 Analyzer，
验证 13 条 XML/直接 SQL 注解/Provider 定义、11 个 Mapper 调用、跨层实参、include、set、bind/if/where 和 `${}`/`#{}` 的联合样例。
四类 Provider 覆盖静态/实例方法、默认方法解析器、Map/命名参数、ProviderContext 和局部/链式/匿名 SQL 构造器。
测试不执行目标程序或访问数据库，并在编译目录清理后读取保存的来源和绑定快照。
XML 条件尚未加入联合求解，对应样例应保留 UNKNOWN、路径覆盖 false 和 PARTIAL 诊断。
报告保存到 `target/mybatis-user-query-sql-report.txt`。

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' '-Dtest=Mybatis*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```
