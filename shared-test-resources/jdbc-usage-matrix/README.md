# JDBC 用法矩阵（第一批）

独立 Maven + H2 项目，不依赖 jsqlint。`JdbcUsageApplication.main` 实际执行所有场景并核验结果；
失败会抛异常，无需开启 Java `-ea`。

| 方法 | 用法及边界 |
|---|---|
| createTable | Statement.execute |
| overwriteAndClear | generated-keys 工厂重载、clearParameters、同位置覆盖、setObject(SQLType)、setNull、执行后修改不影响快照 |
| preparedBatch | addBatch 时的参数快照、clearBatch 移除前项、执行时当前值不能替代已加入的值 |
| statementBatch | addBatch(String)、clearBatch、executeBatch 顺序 |
| preparedLargeBatch | 指定生成键列名、setObject(int SQL type)、executeLargeBatch |
| statementLargeBatch | createStatement 游标重载、executeLargeBatch |
| largeUpdate | 指定生成键列位置、setObject(SQLType, scale)、executeLargeUpdate |
| callable | prepareCall 游标重载、位置输入参数、输出参数声明、ABS 返回值 |
| scrollingQuery | prepareStatement 游标/并发/holdability 重载、executeQuery、资源关闭 |

最终数据库有 9 行：被 clearBatch 移除的记录、未加入批次的当前值均不能出现。
H2 内存库在连接关闭时销毁，可重复运行。

从仓库根目录运行：

```powershell
.\mvnw.cmd -f shared-test-resources/jdbc-usage-matrix/pom.xml compile exec:java
```

输出 `JDBC usage matrix passed` 表示数据库自检通过。
`SampleSqlCoverageTest#comparesExpandedUsageMatricesWithRuntimeAndDatabaseOracles` 还对每个执行点核验
SQL、位置绑定和值，并逐个核对 8 个批次项的加入顺序。UNKNOWN 路径证明与 SQL/绑定正确性分别统计。

本批没有覆盖 CallableStatement 命名参数、全部 JDBC 重载、日期/LOB/流值、nativeSQL 和复杂别名/工厂。
后续计划见 [补齐进度](../../jsqlint-test/SAMPLE_EXPANSION_PROGRESS.md)。
