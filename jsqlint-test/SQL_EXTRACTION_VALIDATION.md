# JDBC / MyBatis SQL 提取覆盖率与正确性验证

验证日期：2026-11-11。范围为 `shared-test-resources` 中三个现有项目。使用当前 Analyzer、JOINT 模式、默认追踪与联合求解预算；仅将随机种子固定为 `20261111`，使参数模型可复核。
业务代码基线为 Git 提交 `9fb1831`；本轮仅新增验证测试和报告，没有修改 JDBC/MyBatis 实现或样例业务代码。

## 结论

在这三个样例范围内，代码 SQL 调用点覆盖 **22/22（100%）**，SQL 来源覆盖 **21/21（100%）**。
JDBC 的预期结构 **18/18** 全部保留；MyBatis 的 **13/13** 个定义被发现，包含两个未调用定义。
已核验的 SQL 文本、有序绑定位置和值全部与独立运行时对照一致，没有发现漏提取、多提取或错误参数配对。

若将 Spring 初始化脚本中的两个 SQL 来源也纳入项目全集，则来源覆盖为 **21/23（91.3%）**：
当前 JDBC/MyBatis 模块不提取普通 `.sql` 初始化脚本。代码入口覆盖与项目全部 SQL 覆盖须分别解释。

路径证明仍有明显边界。纯 JDBC 的 13 次 main 调用中仅 4 次有 SATISFIABLE 模型；
MyBatis 的 22 个调用样例全部为 UNKNOWN。它们的 SQL 与参数在本次实际输入对照中正确，但分析器没有完成所有相关路径的证明。
Spring MVC JDBC 的 27 个模型全部可重演且为 SATISFIABLE。

## 统计口径

- **SQL 来源覆盖率**：发现的 SQL 定义/构造方法数 ÷ 样例中的预期来源数。纯 JDBC 6 个方法、Spring JDBC 2 个方法、MyBatis 13 个映射定义；另计 Spring 两个初始化 SQL。
- **调用点覆盖率**：发现的代码 SQL API/Mapper 调用位置 ÷ 预期调用位置。JDBC 的准备点与执行点是不同代码位置，但不会被当作不同 SQL 结构。
- **结构覆盖率**：按 SQL 结构去重后的预期集合与 raw SQL 集合比较，同时核对遗漏与多余结构；原始记录数不作为分母。
- **SQL/绑定正确率**：已核验输出中，SQL 文本、绑定位置及值均与独立对照一致的比例。UNKNOWN 输出也参与正确性核验，路径证明另计。
- **类型正确性**：核验已知非 null 绑定值的实际 Java 类型与快照类型；原始类型与对应包装类型视为一致。null/未知值不用于推测运行时类型。

这些数字衡量已选样例及实际核验输入，不是整个 JDBC/MyBatis 语法或所有输入域的正确率。
现有共享样例没有覆盖当前全部支持能力；哪些用法仅在回归夹具出现、哪些仍缺实现，见
[共享样例用法覆盖检查](SAMPLE_USAGE_COVERAGE.md)。

## 覆盖结果

| 项目 | SQL 来源 | 代码调用点 | JDBC 结构 / MyBatis 定义 | 主流程输入覆盖 | 分析终态 |
|---|---:|---:|---:|---:|---|
| jdbc-user-query | 6/6 | 8/8 | 13/13 种结构 | 13/13 次 main 调用 | PARTIAL |
| springmvc-jdbc-user-query | 2/2 | 3/3 | 5/5 种结构 | 27 个模型全部可重演 | SUCCESS |
| mybatis-user-query | 13/13 | 11/11 | 13/13 个定义 | 11/11 次 Mapper 调用 | PARTIAL |

纯 JDBC 的 13 种结构包括 5 种 DDL/CRUD 固定结构，以及 `findUsers` 三个可选条件的 8 种组合。
Spring JDBC 的 5 种结构包括按 ID 查询，以及启用状态 × 排序的 4 种查询结构。
MyBatis 的定义覆盖 XML、include、set、bind/if/where、直接注解及四类 Provider；`unusedCount`、`unusedProviderCount` 均被发现。

Spring 的 `schema.sql` 为一条 CREATE TABLE，`data.sql` 为一条多行 INSERT，当前两条均未被 SQL 模块提取，单独统计为 **0/2**。

## 独立正确性对照

| 对照对象 | 一致 / 已核验 | 错误 | 无法核验 |
|---|---:|---:|---:|
| 纯 JDBC：按 main 调用上下文去重的 SQL | 13/13（100%） | 0 | 0 |
| Spring MVC JDBC：原 Controller/Service/Repository 重演模型 | 27/27（100%） | 0 | 0 |
| MyBatis：实际调用 SQL 和绑定快照 | 22/22（100%） | 0 | 0 |
| MyBatis：显式定义参数的正常输入 | 16/16（100%） | 0 | 0 |
| MyBatis：运行时失败输入的完整 SQL 拒绝 | 1/1 | 0 | 0 |
| Spring JDBC：已知绑定类型 | 66/66 | 0 | 0 |
| MyBatis 调用：已知绑定类型 | 28/28 | 0 | 0 |

纯 JDBC 没有 setXxx 绑定，绑定类型项不适用。MyBatis 主流程 11 次调用各保留两个路径模型，故对应 22 个样例；这不表示实际执行 22 次。

### 对照如何产生

1. 用现有 Maven 项目编译原始样例，再交给 Analyzer 分析；没有修改业务源码。
2. JDBC：反射调用原 `UserRepository`，使用与 main 相同的 13 组有序实参；另外执行 `findUsers` 的 8 种结构组合。
   代理 JDBC 在实际 execute 边界捕获构造出的 SQL 及 setter 值，空 ResultSet 只用于让构造方法完成返回。
3. Spring MVC JDBC：从一个联合模型中取回 Controller 入口的真实初始值，直接调用原 Controller → Service → Repository，捕获 SQL/绑定后逐项比较。
   没有自行重写 LIKE 转义、年龄校验、LIMIT 校正或排序逻辑，也没有任意组合不同模型的参数。
4. MyBatis：通过真实 `XMLMapperBuilder`、`Configuration`、`ParamNameResolver` 和 `MappedStatement.getBoundSql` 产生对照。
   对参数映射按顺序读取实际值，并处理 bind 的 additionalParameters；Provider 方法由真实 MyBatis 求值。
5. SQL 仅规范化字面量之外的空白；字符串内容、单引号转义、LIKE 模式、参数值及顺序保持原样。

此验证检查 SQL 生成与提取一致性，不启动 HTTP 服务、不连接数据库，也不验证查询结果或影响行数。

### 边界输入

MyBatis 显式定义对照共 17 组：13 个定义各一组，加 `optional(null)`、`optional("")`、
`providerFind("archive_users",99)`、`search("id","7")`。
16 组正常输入的 SQL/绑定全部一致。

`optional(null)` 在真实 MyBatis 中执行 `bind pattern = '%' + name + '%'` 时抛 NullPointerException；
后面的 if 不能保护先执行的 bind。分析器也没有给出完整 SQL，因此未产生假成功输出。
该结果保留 UNVERIFIED 渲染状态；这表示没有进行调用路径求解，不是有效 SQL 的证明。

## 路径证明与保守边界

| 项目 | 已证明模型 | 说明 |
|---|---:|---|
| 纯 JDBC | 4/13 个主流程调用；4/445 个原始执行样例模型 | 三次 insert 与一次 createTable 被证明；其余调用仍有 UNKNOWN。445 包含同一调用的重复控制历史/模型，不能用作实际执行次数。 |
| Spring MVC JDBC | 27/27 个执行模型 | 参数范围、LIMIT 校正、排序、SQL 条件与第 3 个绑定的同分支配对均通过重演。 |
| MyBatis | 0/22 个调用样例模型 | 主流程 try-with-resources 等相关谓词没有找到已验证模型；optional 另有 XML 条件尚未联合求解。不限于动态 XML，普通注解和 Provider 调用也为 UNKNOWN。 |

纯 JDBC 的诊断包括异常控制流未建模、循环需要展开、控制流/条件依赖预算，以及不支持谓词或搜索预算内未验证模型。
MyBatis 不能因为所有实际样例的 SQL 已对照正确而整体升级为 SATISFIABLE；当前保守状态应继续保留。

后续可优先完善调用者异常路径与循环模型、MyBatis 模板条件的联合约束，以及普通 SQL 初始化资源的提取入口。

## 复核入口与证据

新增 `SampleSqlCoverageTest`，固定种子后已重跑通过；原有三组项目集成测试也分别通过。
该测试先写逐条证据，再断言无漏提取、多提取、错误配对、错误类型或运行时失败假成功，便于以后复核回归。

```powershell
$env:JAVA_HOME = 'D:/Jdk/hotspot/jdk-21.0.1+12'
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=SampleSqlCoverageTest' test
```

- 测试源码：[SampleSqlCoverageTest.java](src/test/java/org/u80dc/jsqlint/validation/SampleSqlCoverageTest.java)
- 机器可读指标：[sample-sql-coverage-metrics.tsv](target/sample-sql-coverage-metrics.tsv)
- 逐模型 SQL、绑定和诊断：[sample-sql-coverage-evidence.txt](target/sample-sql-coverage-evidence.txt)
- 对照测试执行日志：[sample-coverage-oracle.log](target/sample-coverage-oracle.log)
- 原有样例测试执行日志：[sample-coverage-validation.log](target/sample-coverage-validation.log)

最后一个日志同时包含首版对照测试对 `optional(null)` 未分类造成的测试错误；原有三项样例测试均通过。
随后对照测试将运行时失败分开计量，并重跑通过，最终指标与证据以 `sample-coverage-oracle.log` 对应文件为准。
