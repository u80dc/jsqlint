# 第一批后的 JDBC 共享样例用法覆盖审计

此文保留第一批完成后的三个项目基线。第二批已补齐标准 SQL 签名 34/34，
最新四项目范围及剩余缺口见 [第二批补齐与验证](JDBC_SECOND_BATCH_VALIDATION.md)。

日期：2026-11-11。范围为 `jdbc-user-query`、`jdbc-usage-matrix`、`springmvc-jdbc-user-query`
三个共享项目的 Java 源码，包含第一批新增场景。对照 JDK 21.0.1 的标准接口声明、
`JdbcMethodMatcher` 与统一测试夹具。本次检查没有修改样例或分析器，也没有重跑测试。

## 结论与计量口径

**尚未覆盖所有 JDBC 用法，当前分析器已支持的 JDBC 用法也没有全部进入共享样例。**
已有样例覆盖基础 CRUD、字符串拼接与动态条件、代表性参数绑定、批次快照/清除、
位置型 CallableStatement 输入/输出和部分标准重载。

为避免把样例内正确率当作框架覆盖率，本次采用一个有限、可复查的签名口径：

- 分母为 JDK 21 声明的 SQL 工厂、执行及加入批次的具体重载；继承方法不重复计数。
- Connection 包含 createStatement、prepareStatement、prepareCall、nativeSQL。
- Statement 包含带 SQL 的 execute/executeQuery/executeUpdate/executeLargeUpdate/addBatch，以及无参 executeBatch/executeLargeBatch。
- PreparedStatement 包含无参 execute/executeQuery/executeUpdate/executeLargeUpdate/addBatch；CallableStatement 继承的 execute() 计入此组。
- clearBatch/clearParameters 和 setter 另外统计；包括 JDK 声明的 default 方法，setter 分母也保留已废弃标准方法。
- 签名“出现”不等于全部参数值、控制流、驱动行为均已正确验证。

接口目录用本机 JDK 21 的 `javap -s` 枚举，出现项与三个项目仓库类的源码及现有 `javap -c`
方法引用交叉核对。此处 19 个具体签名独立去重，不使用测试指标中的“方法名/API 名组合”作为分母。

| 签名类别 | 已出现 / 标准声明 | 出现覆盖率 |
|---|---:|---:|
| Connection SQL/Statement 工厂与 nativeSQL | 8 / 13 | 61.5% |
| Statement SQL 执行/加入批次及批次执行 | 6 / 16 | 37.5% |
| PreparedStatement 无参执行/加入批次 | 5 / 5 | 100% |
| 上述 SQL 工厂与执行签名合计 | **19 / 34** | **55.9%** |
| 加入 clearBatch / clearParameters 后 | 21 / 36 | 58.3% |
| PreparedStatement 位置 setter 的具体重载 | 7 / 50 | 14.0% |
| CallableStatement 自有命名 setter 的具体重载 | 0 / 47 | 0% |

这些百分比各有独立分母，不能合并解释成“所有 JDBC 用法覆盖率”。
JDBC 的事务、连接池、结果集操作、元数据和驱动扩展没有纳入上述 SQL 签名分母。
PreparedStatement 的 5/5 是继承去重后的 API 形式覆盖；CallableStatement 的查询、更新和批次本身尚无样例。

## 确定缺失的标准重载

| API 组 | 已有形式 | 尚缺的形式 |
|---|---|---|
| createStatement | `()`、`(int,int)` | `(int,int,int)` |
| prepareStatement | `(String)`、`(String,int)`、`(String,int[])`、`(String,String[])`、`(String,int,int,int)` | `(String,int,int)` |
| prepareCall | `(String,int,int)` | `(String)`、`(String,int,int,int)` |
| nativeSQL | 无 | `(String)` |
| Statement.execute | `(String)` | `(String,int)`、`(String,int[])`、`(String,String[])` |
| Statement.executeUpdate | `(String)` | `(String,int)`、`(String,int[])`、`(String,String[])` |
| Statement.executeQuery | `(String)` | 无标准 SQL 重载缺项 |
| Statement.executeLargeUpdate | 无 | `(String)`、`(String,int)`、`(String,int[])`、`(String,String[])` |
| 批次 API | Statement.addBatch(String)、PreparedStatement.addBatch()、executeBatch()、executeLargeBatch()、clearBatch() | 签名已有；下节的状态组合和失败边界仍不完整 |

位置 setter 实际只有以下 7 个具体签名：

```text
setInt(int,int)
setString(int,String)
setBoolean(int,boolean)
setNull(int,int)
setObject(int,Object,int)
setObject(int,Object,SQLType)
setObject(int,Object,SQLType,int)
```

例如 setObject(int,Object)、setObject(int,Object,int,int)、setNull(int,int,String) 都没有共享样例。
字符串拼接中的 long/boolean/数字值，不计作对应 JDBC setLong/setBoolean 等绑定调用。

## 用法与语义缺口

| 用法 | 当前共享样例 | 仍缺什么 / 其他证据 |
|---|---|---|
| 常用 SQL 构造 | `+`、`+=`、concat、辅助 quote/replace、条件、排序/LIMIT | 可变 StringBuilder/StringBuffer 没有共享负向样例；StringBuilder 当前只有 PARTIAL 回归边界 |
| 基础位置绑定与状态 | int/String/boolean/null/Object，覆盖、clearParameters、执行后修改 | byte/short/long/float/double、bytes、BigDecimal、日期时间/Calendar、NString、LOB/流、Array/Ref/SQLXML/RowId/URL 的值和内容；不能因签名目录可识别就认为值恢复已验证 |
| 下标与参数名 | 固定位置参数 | 动态下标、CallableStatement 命名参数、INOUT、多输出及 SQLType 输出重载；动态下标/命名绑定只在专门夹具中 |
| 工厂/对象身份 | 直接标准工厂，普通业务分层 | Statement/PreparedStatement 包装工厂、接口分派、协变返回、字段/数组别名、可能别名/外部逃逸；存在回归夹具，但没有完整共享项目对照 |
| 批次 | Statement/Prepared 的普通和 large 批次、加入时快照、clearBatch、顺序 | 空批次、连续 executeBatch 后再加入、清批次与清参数组合、循环/条件加入、批次失败后重试；当前数据库自检仅覆盖成功、每项返回 1 的批次 |
| 事务与失败路径 | 连接关闭、try-with-resources，Spring 包装 SQLException | setAutoCommit(false)、commit/rollback、savepoint、事务隔离、异常回滚/重试；捕获 SQLException 不代表该失败路径已被实际驱动 |
| 生成键/多结果 | 准备语句用了生成键工厂参数；查询有基础 getter 和滚动结果集 | getGeneratedKeys、getMoreResults、多结果/更新计数、可更新 ResultSet、元数据、SQLWarning、取消/超时等；工厂重载覆盖不能代替键读取和结果行为验证 |
| 输入与 SQL 内容 | H2、单表 CRUD、建表、LIKE、基础排序分页、ABS 函数调用 | 空白/极值/非法输入的系统矩阵，JOIN/子查询/CTE/UNION/窗口/多行 INSERT/多语句、多数据库方言和驱动差异 |
| 连接管理与上层 API | DriverManager 和 Spring 注入 DataSource，使用原生 JDBC | 连接池/包装/unwrap、XA/分布式事务等完整 JDBC 生态用法；JdbcTemplate/NamedParameterJdbcTemplate 没有样例，当前模块尚未接入；它们是上层框架，不纳入标准 JDBC 签名分母 |
| SQL 初始化资源 | Spring 项目已有 schema.sql/data.sql | 当前未被代码 JDBC 入口提取，属于已存在目标的资源入口缺口 |

现有专门回归夹具已经覆盖部分缺失场景：

- `JdbcDataFlowTest`：命名参数/动态下标、包装工厂、协变返回、字段/数组别名、空/未知批次、连续批次与异常后批次等。
- `JdbcJointPathInferenceTest`：动态索引、绑定分支、参数与执行路径联合约束。
- `SqlCallSiteDiscoveryTest`：nativeSQL 和更多标准重载的入口发现。
- `AnalyzerTest.unsupportedMutableBuilderRetainsSqlCandidateAndReportsPartial`：StringBuilder 的保守结果。

这些测试不能等同于每项都已有“真实项目 + 运行时 SQL/绑定 + 数据库自检”的共享样例。

## 已有验证能证明什么

第一批 JDBC 矩阵的此前验证为：25/25 源码 SQL 调用位置、13/13 执行 SQL/绑定上下文、
129/129 输出模型 SQL 与绑定一致、8/8 批次加入位置及顺序一致，H2 自检通过。
129 个模型中仅 2 个有 SATISFIABLE 路径证明，其余 127 个为 UNKNOWN；
输出正确不能直接升级为所有路径已经证明完整。

原始 JDBC 项目的 main 只走三种不同 findUsers SQL 结构；独立验证额外驱动了全部八种结构。
Spring 样例的 Controller → Service → Repository 对照使用记录器，未执行真实 HTTP/数据库请求。
`requests.http` 已有单引号、`_%`、limit=0、404 和非法 sort 的手工请求；仍需补自动输入/结果断言。
当前独立 oracle 的 `SqlRow` / `savedRow` 使用整数位置，记录器 setter 也要求 Integer 下标；
新增 Callable 命名参数前，需扩展独立记录器和绑定视图的核验口径。
LOB/流应比较独立捕获的内容与长度，直接比较 setter 的对象引用不能证明内容恢复正确。
本次复核只读取此前证据，没有生成新的运行正确率。

## 下一批建议

1. 先补现有能力的明确缺项：nativeSQL、缺失工厂与执行重载、CallableStatement 命名参数、基础数值 setter；对应 API 要有实际调用及独立断言。
2. 补工厂/别名和批次边界：包装/协变返回、字段/数组别名、空批次、连续批次、失败后状态；正常与保守 UNKNOWN 用例分别核验。
3. 再补对象值和生态边界：日期/Decimal/bytes/LOB/流、事务/生成键/多结果、方言与非法输入。驱动不支持或分析器尚未恢复的内容应保留明确边界。

## 核对来源

- [JDBC 标准签名与模块边界](../jsqlint-jdbc/README.md)
- [原始 JDBC 仓库](../shared-test-resources/jdbc-user-query/src/main/java/org/u80dc/jsqlint/samples/jdbc/UserRepository.java)
- [新增 JDBC 场景](../shared-test-resources/jdbc-usage-matrix/src/main/java/org/u80dc/jsqlint/samples/jdbcmatrix/JdbcScenarios.java)
- [Spring 原生 JDBC 仓库](../shared-test-resources/springmvc-jdbc-user-query/src/main/java/org/u80dc/jsqlint/samples/springmvcjdbc/repository/JdbcUserRepository.java)
- [独立样例验证测试](src/test/java/org/u80dc/jsqlint/validation/SampleSqlCoverageTest.java)
- [原始用法覆盖审计](SAMPLE_USAGE_COVERAGE.md)
- [分批补齐进度与此前运行结果](SAMPLE_EXPANSION_PROGRESS.md)
