# JDBC 标准 API 补充样例

独立 Maven + H2 项目，不依赖 jsqlint。与已有 JDBC 共享项目合并审计时，补齐 SQL 工厂、
执行及加入批次有限目录中原先缺失的 15 个具体重载。该目录按 JDK 21 标准声明去掉继承重复，
不代表所有 JDBC 对象、值、控制流或驱动用法。

`JdbcApiScenarios` 和 `JdbcStateScenarios` 共提供 37 个 public 场景方法，包含建表与结果汇总方法。
H2 场景由 `JdbcApiApplication.run()` 实际驱动；命名存储过程另有接口专用入口。

## 标准 API 与输入绑定

| 方法 | 补充用法 |
|---|---|
| createStatementHoldability | createStatement(int,int,int) |
| scrollingPrepared | prepareStatement(String,int,int) |
| plainCallable / holdableCallable | prepareCall(String)、prepareCall(String,int,int,int) |
| nativeSql | nativeSQL(String)，输入 SQL 与驱动返回文本分别验证 |
| executeGeneratedFlag / Indexes / Names | Statement.execute(String,int/int[]/String[])，实际读取生成键 |
| updateGeneratedFlag / Indexes / Names | Statement.executeUpdate(String,int/int[]/String[]) |
| largeUpdatePlain / GeneratedFlag / GeneratedIndexes / GeneratedNames | Statement.executeLargeUpdate(String) 及三个生成键重载 |
| numericBindings | setByte/Short/Long/Float/Double、setObject(Object)、setObject(int SQL type,scale)、setNull(typeName) |
| dynamicIndex | 项目辅助方法计算的位置下标，实际绑定位置 2 |
| namedCallable | 命名 String/Int/Null/Object(SQLType) 输入与命名输出声明 |
| inOutCallable | INOUT 与两个纯输出参数；输出声明不能被当作输入绑定 |

## 批次、别名和事务状态

| 方法 | 状态与核验要求 |
|---|---|
| emptyStatementBatch / emptyPreparedBatch | clearBatch 后提交确定空批次；工厂 SQL 或已移除项不能成为执行 SQL |
| statementConsecutiveBatches | 首次正常执行后，仅后来加入的项进入第二批 |
| preparedConsecutiveBatches | 正常执行清除队列，当前位置绑定 21 保留；第二批仍使用该值 |
| clearParametersAfterAdd | 清除当前绑定不改写已加入快照；有效值为 31、32 |
| clearParametersThenBatchClear | 两个加入项经 clearBatch 移除；重新绑定后只执行 33 |
| clearBatchThenParameters | clearBatch 保留当前值；905 重新 addBatch 后有效，另执行新快照 34 |
| localAlias / fieldAlias / arrayAlias | 局部、字段、数组中的同一个 PreparedStatement；代码实际调用，提取能力另行计量 |
| wrappedFactory | 接口工厂实现委托标准 prepareStatement，返回对象用于绑定与执行 |
| transactionSavepoint | setAutoCommit(false)、savepoint、rollback(savepoint)、releaseSavepoint、commit；保留两项修改并回滚中间项 |
| transactionRollback | 完整 rollback；该 SQL 已执行，最终数据不保留其修改 |
| generatedKeys | PreparedStatement 生成键重载与实际读取生成键 |
| exceptionalBatchRetry | H2 首批一项成功、一项失败；明确 clearBatch 后重试，不假设驱动异常后自动清队列 |
| verifyRows | 最终状态聚合为 `[17,0,2,1]`：状态表 17 行、回滚/空批次项 0 行、值 21 保留项 2 行、生成键记录 1 行 |

事务执行的 SQL 与最终数据库修改分别核验。被 savepoint 或完整 rollback 撤销的 SQL，
仍属于实际执行 SQL，不能从提取结果中删除。`verifyRows` 是聚合核验，不能代替逐行完整内容检查。

`exceptionalBatchRetry` 故意引用不存在的 `jdbc_missing_retry` 表，H2 的首批 updateCounts 为
`[1, Statement.EXECUTE_FAILED]`，清空后重试返回 `[1]`。故障行计为提交尝试，不能计为数据库成功执行。
接口 oracle 对该首批注入 BatchUpdateException，保留提交记录，再核验 clearBatch 与重试快照；
事务持久化由真实 H2 自检核验。异常之后的重试路径需要保留 UNKNOWN。

## 运行与当前验证边界

从仓库根目录运行：

```powershell
.\mvnw.cmd -f shared-test-resources/jdbc-standard-api/pom.xml compile exec:java
```

`JdbcApiApplication.run()` 在 H2 上实际运行支持的重载，核验 API 表 10 行插入、生成键、数值/空值、
游标/存储函数结果，再调用 `exerciseStates(Connection)` 核验批次、别名、事务、故障重试与最终状态聚合。
`main` 的数据库通过消息只适用于此 H2 入口实际执行的场景。

`namedCallable` / `inOutCallable` 的存储过程及命名参数不是 H2 完整支持的用法，
通过公共 `JdbcApiApplication.exerciseInterfaceOnly(Connection)` 接口驱动入口实际调用。
它们需要支持该用法的驱动或记录 JDBC 接口的独立 oracle；不得把主程序中未执行的两项计作数据库成功。

当前默认分析预算下，`arrayAlias` 的执行 SQL 未恢复，产生 2 个 UNKNOWN 模型。
该方法虽能在 H2 实际运行，仍是明确的提取负向边界，不计入成功 SQL 提取。
最终独立对照中，其余 47 个 SQL/批次提交行恢复正确，2551 个已知输出模型全部与手写基准匹配，
1378 个非 null 绑定 Java 类型一致，12 个批次快照及顺序一致，两个确定空批次保留完整来源。
新增测试与原有样例回归共 3 项通过，独立 H2 入口也通过。模型数量包含重复历史，不能解释为数据库执行次数。
SQL/绑定文本匹配与路径证明分别统计，UNKNOWN 不因数据库运行通过而升级。

完整计量口径、复现命令和证据见 [JDBC 第二批补齐与验证](../../jsqlint-test/JDBC_SECOND_BATCH_VALIDATION.md)。

日期、Decimal、LOB、流及其他对象内容、更多 named setter、协变工厂、复杂别名/外部逃逸和跨驱动差异
仍缺共享样例或独立内容级验证。基础异常、事务和重试场景已经加入，仍未穷尽这些操作的全部驱动行为。
