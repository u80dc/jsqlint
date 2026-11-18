# 当前 JDBC 样例 SQL 提取验证

2026-11-11 14:19 完成重新运行。Analyzer 实现基线为 `9fb1831`，本轮只完善验证测试和报告，未修改分析器或目标样例。
使用 JDK 21.0.1、默认 JOINT 推断和默认预算，固定随机种子 `20261111`，样例离线自动编译。

**代码 SQL 调用点清单匹配 117/117；独立执行场景完整恢复 73/74（98.65%）；3152 个可核验执行模型的 SQL 与绑定全部正确。**
仍有一个 `arrayAlias` 执行上下文未恢复，产生两个不可完整核验的 UNKNOWN 模型。
Spring 的 `schema.sql` / `data.sql` 两条初始化语句也没有被提取，资源入口单独计量。

## 分项结果

| 项目 | 代码 SQL 调用位置清单匹配 | 独立 SQL/绑定上下文恢复 | 可核验模型正确 / 已核验 | 不可完整核验模型 |
|---|---:|---:|---:|---:|
| jdbc-user-query | 8/8 | main 固定调用 13/13 | 445/445 | 0 |
| springmvc-jdbc-user-query | 3/3 | 未独立枚举输入域；回放 27 个生成模型 | 27/27 | 0 |
| jdbc-usage-matrix | 25/25 | 13/13 | 129/129 | 0 |
| jdbc-standard-api | 81/81 | 47/48（97.92%） | 2551/2551 | 2 |

完整上下文恢复分母只合并原始 JDBC、usage matrix 和 standard API 的独立预期：
`(13 + 13 + 47) / (13 + 13 + 48) = 73/74`。
Spring 的回放输入来自 Analyzer 输出，能够检查输出正确性，不能作为独立输入域覆盖分母。
两个确定空批次没有 SQL 行，另验完整空结果 **2/2**，不计入 74 条预期 SQL/绑定上下文。

模型统计统一采用未去重的执行输出，standard API 还包含 nativeSQL 输入：

- 可核验模型：`445 + 27 + 129 + 2551 = 3152`；与运行时/手写基准一致 **3152/3152 = 100%**。
- 全部模型：`3152 + 2 = 3154`；完整可核验率 **3152/3154 = 99.94%**。
- standard API 单项完整可核验率为 **2551/2553 = 99.92%**。

此处 100% 是已恢复模型的条件正确率。它没有将两个 UNKNOWN 算作正确，也不表示全部 JDBC 用法、全部输入或全部路径已经覆盖。
模型包含重复历史，不能将 3154 当作数据库执行次数。原始 JDBC 的旧指标 `sql_and_bindings=13/13`
按调用键和 SQL/绑定行去重；本轮新增 `execution_model_sql_and_bindings=445/445`，用于一致的逐模型计量。

## 调用点发现的独立清单

[JdbcCallSiteInventory](src/test/java/org/u80dc/jsqlint/validation/JdbcCallSiteInventory.java)
从样例编译字节码用 ASM 枚举调用，JDK 反射提供标准 API 参数签名目录，未复用生产 JDBC matcher。
Analyzer 端仅取 JDBC 模块输出，按 `SqlCallSite` 去重；UNKNOWN 位置也计为已发现。

| 项目 | execute / nativeSQL | prepareStatement / prepareCall | addBatch | 合计 |
|---|---:|---:|---:|---:|
| jdbc-user-query | 6 | 2 | 0 | 8 |
| springmvc-jdbc-user-query | 2 | 1 | 0 | 3 |
| jdbc-usage-matrix | 9 | 6 | 10 | 25 |
| jdbc-standard-api | 47 | 17 | 17 | 81 |
| 合计 | 64 | 26 | 27 | 117 |

createStatement 本身不接收 SQL，setter、clear、事务操作、输出参数声明和 ResultSet 读取也不在此分母中。
上述调用按完整调用者类/方法描述符及目标方法描述符分组计数，所有组缺失和额外数量均为 0，证据展示各组源码行号和调用顺序。
ASM 与 Jimple 坐标不同，未验证字节码偏移与 Jimple 下标逐点相等；同组错误替代位置的风险不能仅由数量清单排除，执行 oracle 提供进一步约束。
当前四个项目的声明类型可解析，没有 JDBC 方法引用；此清单不代表 invokedynamic 方法引用或任意外部驱动子类型都已验证。

正确率的模型分母只包括 execute/nativeSQL，未包括 prepare/addBatch 的全部渲染模型。
准备点与加入点在调用清单中核对，原始/Spring 的 SQL 结构另验 13/13 和 5/5，执行批次的加入快照及顺序另验。
不能把 117/117 调用点发现升级为每个调用点全部输出均完整恢复。

## SQL、绑定和状态核验

[SampleSqlCoverageTest](src/test/java/org/u80dc/jsqlint/validation/SampleSqlCoverageTest.java)
将原样例执行至记录 JDBC 接口的边界，核对真正提交的 SQL、位置和值；
[JdbcStandardApiCoverageTest](src/test/java/org/u80dc/jsqlint/validation/JdbcStandardApiCoverageTest.java)
另以手写场景基准对照原方法回放，核验位置与命名绑定、批次项和状态转换。
最终绑定键要求正 Integer 或非空 String，不做有损下标截断；重复键包括先前值为 null 的情况都会失败。
SQL 对照仅归一化当前 ANSI 单/双引号之外的空白，保留字面量内容和转义；未将它用作跨方言通用 SQL 等价判定。

| 检查 | 本轮结果 |
|---|---:|
| usage matrix 批次快照与顺序 | 8/8 |
| standard API 批次快照与顺序 | 12/12 |
| standard API 确定空批次完整性 | 2/2 |
| Spring 非 null 已知绑定 Java 类型 | 66/66 |
| usage matrix 非 null 已知绑定 Java 类型 | 69/69 |
| standard API 非 null 已知绑定 Java 类型 | 1378/1378 |
| 四项目出现的标准 SQL/工厂具体签名 | 34/34，属于样例 API 形式覆盖 |

已知 null 正常参与 SQL/绑定比较，Java 类型检查排除 null；usage matrix 的类型指标中一项排除值是已知 null，不能描述为未知绑定。
34 个签名包含三种 createStatement 工厂形式，与 117 个 SQL 调用位置是不同单位。
位置 setter 仍只有 15/50、命名 setter 4/47，尚未补齐的 JDBC 用法见 [第二批报告](JDBC_SECOND_BATCH_VALIDATION.md)。

真实 H2 自检在本轮运行了三个入口：原始 `JdbcUserApplication.main` 的 CRUD/单引号输入，
`JdbcUsageApplication.run` 的批次和绑定状态，`JdbcApiApplication.run` 的重载、生成键、事务和失败重试。
三个入口均未触发自检失败；standard API 的最终状态聚合由程序断言为 `[17,0,2,1]`。
Spring 只回放 Controller → Service → Repository，没有启动 HTTP 服务或验证真实查询结果。
空 ResultSet 导致的业务 404 在 SQL 已提交后仍可用于 SQL 对照，不能解释为业务返回成功。
命名 Callable/INOUT 通过接口专用入口和记录器核验，未计作 H2 存储过程执行成功。
故障批次的缺失表 SQL 是提交尝试；H2 另验部分失败、显式 clearBatch 后重试和事务最终状态。
nativeSQL 对照调用输入，驱动转换与返回值由 H2 入口单独自检。

## 未恢复结果与路径边界

[arrayAlias](../shared-test-resources/jdbc-standard-api/src/main/java/org/u80dc/jsqlint/samples/jdbcapi/JdbcStateScenarios.java)
通过两个数组引用操作同一个 PreparedStatement。独立预期为：

```sql
INSERT INTO jdbc_state VALUES ('array_alias', ?)
-- 位置 1 的绑定值为 Integer 43
```

调用点已发现，但两个模型均为 `sql=Optional.empty`，来源、文本和值完整性均为 false。
本轮证据还显示绑定键和值为 UNKNOWN，并保留 arrayAlias 的 43 与 fieldAlias 的 42 候选来源。
诊断包括 receiver 来源歧义、NO_CALLER/LIMIT 和 Prepared SQL 来源不可用；这些候选不能作为完整正确提取结果。
该方法能在 H2 上通过，不会使静态结果自动变为完整。测试明确保留此负向边界，测试通过不表示该缺口已经修复。

路径可行性单独计量：

| 项目 | SATISFIABLE 执行模型 / 全部执行模型 |
|---|---:|
| jdbc-user-query | 4/445 |
| springmvc-jdbc-user-query | 27/27 |
| jdbc-usage-matrix | 2/129 |
| jdbc-standard-api（含 nativeSQL） | 6/2553 |
| 合计 | 39/3154 |

3115 个模型仍为路径 UNKNOWN，其中 3113 个虽已恢复正确 SQL/绑定，路径尚未得到完整证明。
异常批次后的重试明确要求 UNKNOWN。文本/绑定匹配、路径证明和数据库执行成功分别判断。
Spring 初始化资源 `schema.sql` / `data.sql` 提取 **0/2**，属于现存 SQL 资源入口缺口，不能混入代码 API 位置分母。

## 复现和证据

```powershell
$env:JAVA_HOME='D:/Jdk/hotspot/jdk-21.0.1+12'
.\mvnw.cmd -o -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=SampleSqlCoverageTest#comparesCurrentJdbcSamplesWithIndependentOracles,JdbcStandardApiCoverageTest' test *> jsqlint-test/target/jdbc-current-analyzer-validation.log
```

本轮两个集成测试方法均通过：2 tests，0 failures，0 errors，0 skipped，构建总耗时 3 分 15 秒。
本轮未重跑全量测试或 MyBatis 样例；原始全量及前批次报告保留各自历史范围。

- [三个 JDBC 项目分项指标](target/jdbc-current-sample-metrics.tsv)
- [三个 JDBC 项目逐调用位置/执行模型证据](target/jdbc-current-sample-evidence.txt)
- [standard API 分项指标](target/jdbc-standard-api-metrics.tsv)
- [standard API 逐调用位置/模型及 UNKNOWN 证据](target/jdbc-standard-api-evidence.txt)
- [本轮构建日志](target/jdbc-current-analyzer-validation.log)

上述 target 文件为本地生成证据，不纳入 Git；重新运行会覆盖同名文件。
