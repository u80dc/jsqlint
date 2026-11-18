# JDBC 第二批样例补齐与验证

此文保留第二批补齐验证基线。当前四项目的独立调用位置清单、逐模型口径与本轮重跑结果见
[当前 JDBC SQL 提取验证](JDBC_CURRENT_SQL_VALIDATION.md)。

日期：2026-11-11。新增独立 Maven 项目 [jdbc-standard-api](../shared-test-resources/jdbc-standard-api/README.md)。
原有三个 JDBC 相关项目保持各自基线；以下签名统计对四个项目的编译字节码合并去重。
第二批新增 37 个公共场景，包含 47 个 SQL 执行/提交或 nativeSQL 位置。

## 已补齐的范围

| 内容 | 本批新增 |
|---|---|
| SQL 工厂/执行标准重载 | 原审计缺失的 15 个：5 个 Connection 重载/nativeSQL、10 个 Statement 执行重载 |
| 基础位置 setter | Byte/Short/Long/Float/Double、无类型 setObject、int SQL type + scale 的 setObject、带 typeName 的 setNull，共 8 个重载 |
| 命名 setter | Callable 的 String/Int/Null/Object(SQLType)，共 4 个重载 |
| 存储过程输入/输出 | 命名参数及命名输出、位置 INOUT 和两个纯输出；输出声明不创建输入绑定 |
| 动态位置 | 辅助方法计算为位置 2；没有改为固定字面量掩盖追踪过程 |
| 批次 | 两种确定空批次、连续批次、Prepared 参数保留、三种 clearParameters/clearBatch 顺序 |
| Statement 来源 | 局部/字段/数组别名、接口包装工厂；数组别名的默认预算边界单列 |
| 数据库行为 | 实际读取生成键、savepoint 回滚、完整 rollback、commit、H2 部分失败批次和显式 clearBatch 后重试 |

具体 SQL 工厂/执行重载与 JDK 21 的有限目录对照：

| 签名口径 | 第一批后 | 第二批后 |
|---|---:|---:|
| Connection createStatement/prepareStatement/prepareCall/nativeSQL | 8/13 | 13/13 |
| Statement SQL 执行/加入批次及批次执行 | 6/16 | 16/16 |
| PreparedStatement 无参执行/加入批次 | 5/5 | 5/5 |
| 上述去除继承重复的签名合计 | 19/34 | **34/34** |
| PreparedStatement 位置 setter | 7/50 | **15/50** |
| CallableStatement 自有命名 setter | 0/47 | **4/47** |

这只是标准声明类型的直接调用签名覆盖。编译字节码由 ASM 读取，JDK 目录由反射建立；
输入值、路径、复杂对象内容和驱动行为分别验证。所有 JDBC 用法没有由这 34 个签名穷尽。
接口/协变返回等项目声明的调用今后需单独扩展签名归一化，不能直接用当前四个标准 owner 的清单漏计。

## 独立核验方式

`JdbcStandardApiCoverageTest` 使用三方对照：

1. 手写每个场景的 SQL、位置/命名绑定、提交次序和批次项基准。
2. 运行原项目方法到记录 JDBC 接口的边界，核对实际调用；故障批次先记录提交尝试，再注入 BatchUpdateException。
3. 与 Analyzer 保存的每个调用位置、模型、批次快照和加入顺序比较。Integer/Byte/Short/Float 等保持 Java 类型严格比较；下标不做有损 Number.intValue 截断。

位置和命名绑定都由原始 `keyValue` 核验。setObject/setNull 的具体重载出现、输入值及 Java 类型分别核验；
本批没有把这些结果解释为 JDBC SQLType/scale/typeName 等全部类型选项的独立语义验证。
两个确定空批次必须同时保留完整来源，不能仅因为输出列表为空就计作通过。
依赖未建模异常边的重试位置明确要求 UNKNOWN；首批提交位置可达与每项执行成功分别判断。

H2 主程序另运行真实数据库自检：API 项目插入 10 行并核对查询/生成键；状态表聚合结果为 `[17,0,2,1]`，
分别表示总行数、回滚/移除项数量、Prepared 参数保留导致的重复行数和生成键记录数。
该聚合核验没有声称已逐项核对全部结果集内容。记录器不模拟事务持久化，回滚后的 SQL 仍保留为已执行语句。

## 已明确保留的边界

- `namedCallable` / `inOutCallable` 的过程与命名语义由接口记录器实际驱动，未计作 H2 存储过程成功。
- nativeSQL 的原始输入与 H2 翻译后的返回值分开核验；不声称静态分析器执行了驱动翻译。
- 缺失表语句是首次失败批次的提交尝试，不能计作数据库执行成功；明确清批次后才重试。
- 默认预算下 `arrayAlias` 的执行 SQL 未恢复，有两个 UNKNOWN 模型；独立基准有一条 SQL/绑定上下文未恢复。
  测试要求来源不完整、没有完整 SQL 和相应 LIMIT/receiver 诊断，作为保守边界单列，不能混入成功正确率。
- 其余 47 条 SQL/绑定上下文已恢复；最终对照的 2551 个可核验输出模型均与手写基准一致。
  模型数量包含重复历史，不等于数据库执行次数。路径可行性证明继续单列。

最终固定种子验证结果：

| 指标 | 结果 |
|---|---:|
| SQL 工厂/执行标准签名 | 34/34 |
| 本批新增 setter 具体签名 | 12/12 |
| SQL 执行/提交及 nativeSQL 位置 | 47/47 |
| 正常 SQL/绑定上下文恢复 | 47/47 |
| 明确保守的数组别名上下文 | 1/1；2 个 UNKNOWN 模型，SQL 未恢复 |
| 可核验模型 SQL 与绑定一致 | 2551/2551 |
| 非 null 绑定 Java 类型一致 | 1378/1378 |
| 确定空批次完整性 | 2/2 |
| 批次项快照及顺序 | 12/12 |
| 有 SATISFIABLE 路径证明的可核验模型 | 6/2551；其余 2545 为 UNKNOWN |

新增 `JdbcStandardApiCoverageTest` 与既有 `SampleSqlCoverageTest` 的两个测试方法共 3 项全部通过，
没有失败、错误或跳过。独立 H2 `compile exec:java` 也通过。本次验证范围为上述三个集成测试方法与数据库入口。

## 仍未补齐

位置 setter 尚余 35 个重载，命名 setter 尚余 43 个。日期时间/Calendar、Decimal、bytes、LOB/流、
Array/Ref/SQLXML/RowId/URL/NString 等对象值与内容仍需独立样例；本批未添加它们。
Callable 查询/更新/批次、更多 INOUT 类型、协变工厂、可能别名/外部逃逸、完整多结果集、
事务隔离/复杂失败恢复、跨驱动方言、StringBuilder 与初始化脚本入口也没有由本批穷尽。

## 复现与证据

```powershell
.\mvnw.cmd -f shared-test-resources/jdbc-standard-api/pom.xml compile exec:java
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=JdbcStandardApiCoverageTest,SampleSqlCoverageTest' test
```

固定随机种子沿用 `20261111`。证据位于测试模块的 target：

- `jdbc-standard-api-metrics.tsv`：签名、提交位置、正常/保守上下文、SQL/绑定、Java 类型、空批次、批次顺序与路径证明。
- `jdbc-standard-api-evidence.txt`：逐位置/模型匹配与 UNKNOWN 边界。
- `jdbc-standard-api-h2.log`：独立项目的数据库自检。
- `jdbc-standard-api-validation.log`：新增测试及原有共享样例回归。
