# 共享样例补齐进度

日期：2026-11-11。以 [原始三项目的用法覆盖审计](SAMPLE_USAGE_COVERAGE.md) 为基线。
第一批后的 JDBC 专项复核见 [当前 JDBC 样例用法审计](JDBC_SAMPLE_USAGE_AUDIT.md)：
SQL 工厂/执行具体签名出现 19/34，位置 setter 7/50，命名 setter 0/47；这些是独立签名口径。
第二批加入 jdbc-standard-api 后的四项目最新结果见 [JDBC 第二批补齐与验证](JDBC_SECOND_BATCH_VALIDATION.md)：
SQL 签名 34/34、位置 setter 15/50、命名 setter 4/47；仍未穷尽全部用法。
保留已有三个项目，新增独立项目来补齐场景，避免覆盖清单的分母随意变化。
“完成”要求目标源码存在、入口实际调用、关键分支/状态有输入、独立 SQL/绑定断言成立。

## 第一批：绑定/批次状态和基础 XML 缺口

- [jdbc-usage-matrix](../shared-test-resources/jdbc-usage-matrix/README.md)：9 个业务方法，覆盖 Statement / PreparedStatement 的普通和 large 批处理、加入时快照、clearBatch、clearParameters、位置覆盖、执行后修改、setNull / setObject、CallableStatement 输入/输出及代表性标准重载。
- [mybatis-usage-matrix](../shared-test-resources/mybatis-usage-matrix/README.md)：10 个有效定义、14 个 Mapper 调用位置，覆盖 XML 四类 DML 标签、choose/when/otherwise、trim 前导/尾缀去除、实际参数名/paramN、单标量回退、重复绑定、javaType/jdbcType、跨文件嵌套 include/property、variables 和 databaseId。

JDBC 用手写的执行状态基准核验原程序传到接口的 SQL 和绑定，再与 Analyzer 输出比对。
四个批处理各有两项，单独检查 8 个加入位置与快照顺序；清除项、过期值不能混入。
MyBatis 用真实 ParamNameResolver / BoundSql 对照调用与显式定义渲染，分别运行 H2 和通用回退配置。
两个项目均内置数据库自检，验证 SQL 真正可执行及代表性查询结果；自动化测试也执行这些自检。

固定种子 `20261111` 的第一批对照结果：

| 指标 | JDBC | MyBatis h2 | MyBatis 通用回退 |
|---|---:|---:|---:|
| 业务 SQL 方法 / 有效定义 | 9 / 9 | 10 / 10 | 10 / 10 |
| 实际源码 SQL 调用位置 | 25 / 25 | 14 / 14 | 14 / 14 |
| 恢复的执行 SQL/绑定上下文 | 13 / 13 | 14 / 14 | 14 / 14 |
| 调用输出模型 SQL + 有序绑定正确 | 129 / 129 | 56 / 56 | 56 / 56 |
| 批次加入位置与顺序正确 | 8 / 8 | — | — |
| 显式定义输入案例正确 | — | 19 / 19 | 19 / 19 |
| 数据库自检 | 通过 | 通过 | 通过 |
| 求解证明可行的调用模型 | 2 / 129 | 0 / 56 | 0 / 56 |

重复历史保留为不同模型，不按模型数量解释数据库执行次数。
所有上述 SQL/绑定模型均可核验；已知 null 正常参与 SQL/绑定比较，但不进行 Java 实例类型比较。
SQL API 组合与实际源码调用位置分别计量，PreparedStatement 的无参 addBatch 也属于分析入口。

验证完成：两个独立 `compile exec:java` 均通过，统一 `SampleSqlCoverageTest` 的两个测试方法均通过。
全量运行 578 项，574 项首次通过，4 项因 Gradle 9.8.2 发行包下载连接超时而发生环境错误；
指定本机 `D:/gradle-8.4` 后，所属 `AutoCompileAnalysisInputLocationTest` 全部 16 项复跑通过，
最终 578 个不同用例均已验证通过，没有跳过。全量运行没有因这 4 项环境错误判为一次性成功。
日志分别为 `target/sample-expansion-full-test.log` 和 `target/sample-expansion-gradle-retry.log`。

跨文件片段的嵌套 include 使用完整 refid，符合真实 MyBatis 的跨命名空间解析规则。
测试模块显式添加 H2 test 依赖，供运行 oracle 使用；样例仍使用 runtime 驱动依赖。

实际运行也发现一处待核验的既有语义差异：跨命名空间片段里的相对 `refid="name"`，
MyBatis 3.5.19 会在引用方 Mapper 的命名空间查找；现有 `MybatisSqlAdapter.copyContents` 会改用片段命名空间。
现有定义层夹具未与真实 MyBatis 对照这一组合。第一批正常样例使用完整 refid，并已执行通过；
相对 refid 的负向对照和适配器修正仍待后续处理，不能把本批结果泛化到该组合。

## 第二批：JDBC 标准重载与状态边界

新增 [jdbc-standard-api](../shared-test-resources/jdbc-standard-api/README.md)，补齐 15 个 SQL 工厂/执行重载，
增加 8 个位置 setter 和 4 个命名 setter、INOUT/纯输出声明、辅助方法计算下标、空/连续批次、
三种清除顺序、局部/字段/数组别名、接口包装工厂、事务/savepoint、生成键读取及失败批次显式清除后重试。
共有 37 个公共场景；H2 支持的用法在主入口实际调用，命名过程由独立接口驱动调用。

独立对照：47/47 SQL/提交位置、47/47 正常 SQL/绑定上下文、2551/2551 可核验模型、1378/1378 绑定类型、
12/12 批次快照/顺序、2/2 确定空批次完整性。数组别名默认预算下的一个上下文未恢复完整 SQL，
对应两个 UNKNOWN 模型以明确负向断言单列，未计作成功；异常后的重试也明确保留 UNKNOWN。
数据库自检包含事务最终状态聚合 `[17,0,2,1]`，没有声称完整多驱动或逐行结果覆盖。

新增测试及原有共享样例回归共 3 个集成测试方法通过，独立 H2 `compile exec:java` 通过。
本批未改分析器；此处签名计量与正常/保守结果的含义详见第二批报告。

## 后续批次清单

以下仍未完成，不能计入本次成功覆盖：

| 批次 | 待补内容 | 完成标准 |
|---|---|---|
| 更多已支持形式 | JDBC 更多命名 setter、Callable 查询/更新/批次、协变返回、可能别名/外部逃逸及数组别名完整恢复；MyBatis argN/数字名、特殊参数、Mapper/Provider 继承、更多 SQL 构造器形式 | 可运行目标项目与独立运行时 SQL/参数断言；驱动不支持的 API 单列接口 oracle |
| 第三批：对象与动态集合边界 | foreach 的 List/数组/Map 与空/null 集合；DTO/record/用户 Map/集合实参的 Java 传播；日期/Decimal/LOB/流绑定 | 定义渲染和 Java 调用追踪分别计量；未恢复的对象内容保留 Unknown，不伪报完整结果 |
| 第四批：显式缺口与 SQL 内容 | StringBuilder、SqlSession statementId、selectKey、自定义驱动/处理器、初始化脚本；JOIN/子查询/CTE/UNION/方言、非法输入/异常分支 | 已支持项用正常 oracle；当前实现缺口用明确漏提取/UNKNOWN/PARTIAL 的负向断言，不能改成成功指标 |

本批未修改分析器。SQL/绑定正确与路径可行性证明分开统计；UNKNOWN 不因数据库自检通过而升级。
H2 自检不能代表其他驱动、SQL 方言或任意 TypeHandler 的行为。

## 复现

从仓库根目录、JDK 21 环境执行：

```powershell
.\mvnw.cmd -f shared-test-resources/jdbc-usage-matrix/pom.xml compile exec:java
.\mvnw.cmd -f shared-test-resources/mybatis-usage-matrix/pom.xml compile exec:java
.\mvnw.cmd -f shared-test-resources/jdbc-standard-api/pom.xml compile exec:java
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=SampleSqlCoverageTest' test
```

完整 `SampleSqlCoverageTest` 同时回归基线三个项目与新增两项目。
运行全量测试时，可通过 `-Djsqlint.test.gradle.home` 指定已有安装，避免 Tooling API 重新下载发行包：

```powershell
.\mvnw.cmd -pl jsqlint-test -am '-Dmaven.test.skip=false' '-DskipTests=false' '-Djsqlint.test.gradle.home=D:/gradle-8.4' test
```

新增场景的指标和逐模型证据分别输出到：

- `target/sample-usage-matrix-metrics.tsv`
- `target/sample-usage-matrix-evidence.txt`

原始三项目仍输出 `sample-sql-coverage-*` 文件，保留原测量口径。
