# 共享样例的 JDBC / MyBatis 用法覆盖检查

检查日期：2026-11-11。业务代码基线 `9fb1831`。
检查三个 `shared-test-resources` 项目的业务源码、JDBC/MyBatis 模块实现及统一测试模块的用例。
本次为源码与测试清单核对，没有再次运行全量测试，也没有修改样例程序或分析器。

此文保留原始三个项目的基线审计。后续新增的 `jdbc-usage-matrix` / `mybatis-usage-matrix`
及各批次的运行验证记录见 [样例补齐进度](SAMPLE_EXPANSION_PROGRESS.md)；下表“共享样例”仍指基线三个项目。

## 结论

**三个共享样例没有覆盖所有 JDBC/MyBatis 用法；即使只考虑当前分析器已支持的能力，也不完整。**

它们目前是基础 CRUD 与部分分层/动态 SQL 的端到端样例。
此前的 100% 指这些样例内的 SQL 入口、结构或已核验输出，不是框架用法覆盖率。
更多用法存在于测试内动态编译的小型 Java/XML 夹具；另一些属于当前实现缺口。
测试中仅验证定义渲染或入口发现的场景，也不能当作完整调用链和参数正确性的端到端覆盖。

不能为“所有用法”给出一个固定百分比：标准重载、驱动差异、表达式、动态分支和插件组合没有有限统一分母。
应建立版本化用法清单，对每项分别记录源码出现、实际调用、结构分支、输入边界和输出断言。

## JDBC 核对

“共享样例”指三个目标项目中的业务代码；“回归夹具”指 `jsqlint-test/src/test/java` 中为分析器编译的目标代码。

| 用法 | 共享样例 | 其他回归证据 | 当前判断 |
|---|---|---|---|
| Statement.execute / executeQuery / executeUpdate，完整 SQL 实参 | 有 | JDBC 项目测试、JdbcDataFlowTest | 基础执行入口已覆盖 |
| PreparedStatement 的 SQL 准备、无参查询/更新 | 有 | JdbcDataFlowTest | 已覆盖基础关联 |
| `+`、`+=`、concat、辅助方法返回、replace 转义 | 有 | JdbcRawSqlTest、JdbcParameterInferenceTest | 已覆盖这些构造形式 |
| SQL 条件与绑定共享分支、排序及 LIMIT 校正 | 有，Spring 原生 JDBC | SpringMvcJdbcUserQueryAnalysisTest、JdbcJointPathInferenceTest | 有业务调用和输出对照 |
| setString / setInt / setBoolean | 有，Spring 样例 | JdbcParameterInferenceTest | 三种基础绑定已覆盖 |
| setNull、setObject、SQLType 重载 | 无 | JdbcDataFlowTest、JdbcParameterInferenceTest | 实现支持，有专门夹具，应补共享样例 |
| 同一位置 setter 覆盖、clearParameters | 无 | JdbcDataFlowTest.overwriteClearAndBranchesKeepReachingBindings | 状态转移已有夹具，应补共享样例 |
| 动态绑定下标、CallableStatement 命名参数 | 无 | JdbcDataFlowTest.callableNamedBindingsSetNullAndDynamicIndexRetainCorrectValues | 支持/保守分析已有夹具 |
| prepareCall、输入与输出参数声明 | 无 | JdbcDataFlowTest、SqlCallSiteDiscoveryTest | 有存储过程夹具；输出声明不作为输入绑定 |
| Statement.addBatch(String) / clearBatch / executeBatch | 无 | JdbcDataFlowTest 的 plainStatementBatch 等 | 有批次语义夹具，应补业务项目 |
| PreparedStatement.addBatch() 与加入时参数快照 | 无 | JdbcDataFlowTest.preparedBatchUsesAddBatchSnapshotsAndClearBatch | 有快照夹具，应补业务项目 |
| executeLargeUpdate / executeLargeBatch | 无 | JdbcDataFlowTest、SqlCallSiteDiscoveryTest | 有专门夹具 |
| nativeSQL | 无 | SqlCallSiteDiscoveryTest.jdbcRecognizesStandardOverloadsPreparedExecutionsAndBatchButRejectsLookalikes | 有入口发现断言；未找到该 API 的专门运行时文本对照 |
| generated-keys、游标/并发模式、holdability 等标准工厂重载 | 无 | JdbcDataFlowTest.standardOverloadsCovariantFactoriesAndSqlTypeSettersRemainSupported | 有部分标准重载夹具，不等于每个重载均有业务样例 |
| 项目包装方法、接口实现、协变工厂返回 | 共享样例仅有一般分层调用 | JdbcDataFlowTest 的 factoryReturn、extensionWrapper、standardOverloads 用例 | 复杂工厂/调用分派未进入共享项目 |
| Statement 字段/数组别名、可能别名及外部逃逸 | 未专门构造 | JdbcDataFlowTest、JdbcJointConsistencyTest | 有值追踪与保守边界夹具 |
| 日期时间、Decimal、LOB、二进制/字符流绑定 | 无 | 未找到 setDate/setTimestamp/setBlob/setClob/setBinaryStream 等专门目标夹具 | 标准签名可识别，但对象/内容推断与完整值对照不能由基础 setter 测试保证 |
| StringBuilder / StringBuffer 可变 SQL 构造 | 无 | AnalyzerTest.unsupportedMutableBuilderRetainsSqlCandidateAndReportsPartial | 当前是不支持/保留 PARTIAL 的边界测试，不能算成功提取覆盖 |
| 事务、savepoint、失败后重试影响执行路径 | 无 | 批次异常与清除有部分夹具；未找到 commit/rollback/savepoint 专门目标用例 | 事务结果本身不属于 SQL 提取，但其路径影响缺少共享场景验证 |
| JdbcTemplate / NamedParameterJdbcTemplate | 无 | 未找到专门目标用例 | 当前尚未接入；Spring MVC 样例使用原生 JDBC，不代表覆盖 Spring JDBC API |
| Spring schema.sql / data.sql 初始化 | 文件存在 | 独立验证明确为 0/2 | 目标已有，但当前模块未提取；这是实现/资源入口缺口 |

不能将标准接口签名目录的完整枚举，当作全部 setter 值、全部驱动行为均已验证。
例如 `setBlob` 可以被识别为绑定调用，但 Blob 的构造和内容仍可能无法恢复。
生成键读取、ResultSet 数据映射和事务影响行数也不属于本次 SQL 文本正确率的验证内容。

## MyBatis 核对

| 用法 | 共享样例 | 其他回归证据 | 当前判断 |
|---|---|---|---|
| XML select / update | 有，find/rename/search/optional | MybatisDefinitionTest、MybatisUserQueryAnalysisTest | 已覆盖基础定义与调用 |
| XML insert / delete | 无；对应操作使用注解 | 本轮未找到这两种 XML 标签的专门目标夹具 | 定义索引支持四类标签，但共享项目及专门夹具仍需补齐 |
| 四类直接 SQL 注解 | 有，包含 Update 建表、Insert、Delete、Select 计数 | MybatisDefinitionTest、AnalyzerTest | 基础声明已覆盖 |
| `#{}` 与 `${}` 同时使用 | 有，search | MybatisExtractionTest、MybatisSqlSemanticsTest | 有文本与绑定对照 |
| if / where / set / bind | 有 | MybatisSqlSemanticsTest、MybatisExtractionTest | 可渲染；XML 条件的联合路径覆盖仍未完成 |
| choose / when / otherwise、trim | 无 | MybatisSqlSemanticsTest 的脚本对照 | 有模板渲染夹具，缺共享调用场景 |
| foreach、List/数组/Map 迭代及空集合边界 | 无 | MybatisSqlSemanticsTest、core 节点渲染测试 | 模板渲染有覆盖；不能据此声称 Java 调用实参的集合构造/元素追踪已完整支持 |
| 同文件 include | 有，username 片段 | MybatisDefinitionTest | 基础引用已覆盖 |
| 跨文件、嵌套 include、property 替换 | 无 | MybatisDefinitionTest.databaseSpecificStatementsAndFragmentsAreSelectedAfterAllResourcesAreRead / includePropertiesExpandWithoutMutatingOrLosingNestedNamespace | 有定义层夹具，缺共享项目 |
| databaseId 变体、全局 variables | 无 | MybatisDefinitionTest、MybatisProviderTest | 显式配置支持，缺业务项目矩阵 |
| 显式 @Param 标量、框架 ParamMap | 有 | MybatisExtractionTest | 已覆盖基础别名关联 |
| 无 @Param、实际参数名/argN/数字名、paramN、单标量回退 | 无 | MybatisExtractionTest.parameterNamesMatchRuntimeResolverWithAndWithoutMethodParametersAndSpecialArguments / singleScalarAllowsFallbackNamesAndNullRemainsAnActualBindingValue | 有参数规则夹具，缺共享项目 |
| RowBounds / ResultHandler 等特殊参数 | 无 | MybatisExtractionTest 的参数命名对照 | 已有排除特殊参数的夹具 |
| 重复参数绑定、javaType/jdbcType 选项 | 共享代码未专门覆盖这些组合 | MybatisDefinitionTest、MybatisExtractionTest | 有绑定顺序和选项夹具；类型处理器执行不属于当前模型 |
| bind 连续转换、遮蔽、_parameter 与局部作用域 | 仅一个简单 pattern bind | MybatisExtractionTest、MybatisProviderTest | 基础用法有样例，复杂来源关联只在夹具中 |
| 四类 Provider、静态/实例、ParamMap、ProviderContext、辅助返回 | 有 | MybatisProviderTest | 核心形式已覆盖 |
| SQL 构造器局部、链式、匿名初始化 | 有 | MybatisProviderTest | 三种构造形式有共享代码 |
| SQL 构造器 JOIN/GROUP/HAVING、LIMIT/OFFSET、多行 VALUES | 无 | MybatisProviderTest.modelsSqlBuilderVarargsJoinsAndMultipleValueRows / extractsLocalFluentAndAnonymousMybatisSqlBuilders | 有夹具，缺共享项目 |
| SQL 构造器 FETCH、lambda API、子类覆写/外部逃逸 | 无 | 覆写/逃逸的诊断夹具有；未找到 FETCH 专门目标夹具 | FETCH 支持声明尚缺专门示例；lambda API/复杂逃逸不是完整支持能力 |
| 默认 Provider 类型、provideSql、继承 Provider、依赖接口/Provider | 无或仅默认同名解析 | MybatisProviderTest、MybatisDefinitionTest | 更多选择/分派形式在夹具中 |
| Mapper 继承、重声明、同名重载、重复注解与定义冲突 | 无 | MybatisDefinitionTest、SqlCallSiteDiscoveryTest | 有发现/诊断夹具，缺共享项目 |
| 未调用定义 | 有，两个 count | MybatisDefinitionTest、MybatisUserQueryAnalysisTest | 已覆盖发现与部分默认样例 |
| DTO/record/用户 Map/集合作为真实 Mapper 实参 | 无 | 模板中的 Map/List 渲染不能代替此项 | 对象构造与完整属性/元素传播尚未完成 |
| SqlSession.selectOne/selectList/update 等字符串 statementId | 无；仅 session.getMapper | 当前模块边界明确尚未适配 | 实现缺口；使用 SqlSessionFactory 不等于覆盖字符串调用入口 |
| selectKey、动态 include 无法确定的 refid | 无 | 不属于当前完整支持范围 | 实现缺口，应设置预期诊断样例 |
| 自定义 LanguageDriver、TypeHandler、插件改写 SQL、自动读取配置 | 无 | 常规 XMLLanguageDriver 对照不代表自定义驱动 | 当前不执行自定义语义，配置需显式传入；缺少完整端到端支持 |

共享 Provider 接收的 Map 是由 `@Param` 生成的框架 ParamMap，不是用户自行构造的业务 Map。
同样，显式向定义渲染器提供 `List.of(...)` 验证 foreach，不等于能从 Java 字节码中的集合创建/修改恢复真实 Mapper 实参。

## SQL 内容与输入域也没有穷尽

三个项目均使用 H2，主要是单表 CRUD 与建表。共享程序没有系统覆盖 JOIN/子查询/CTE/UNION、
窗口函数、复杂 INSERT/UPDATE、存储过程、多语句、多数据库方言等业务 SQL 内容。
指纹模块的跨方言测试不等于 JDBC/MyBatis SQL 提取链路已经有这些业务样例。

main 中的固定输入，也没有穷尽每个函数允许的输入：

- JDBC 业务 main 实际只走 `findUsers` 的三种不同结构；上一轮独立对照额外用测试驱动了全部八种结构。
- Spring 通用搜索有两种排序与启用状态组合，但 HTTP 的非法年龄/排序、空值、数值边界、特殊 LIKE 输入、事务异常等尚未组成完整可运行回归矩阵。
- MyBatis main 的 optional 只接收 `Ali`；null、空串和替代表名/列名来自上一轮显式定义对照，不是 main 自带的调用覆盖。

## 建议的样例补齐顺序

1. **先补当前已支持、可形成真实项目对照的功能**：JDBC Statement/Prepared 批处理、覆盖与清空绑定、CallableStatement、setNull/setObject 和重载；MyBatis XML insert/delete、choose/trim、参数命名变体、databaseId/跨文件 include、Mapper/Provider 继承。
2. **为支持不完整的功能建立明确预期的负向样例**：DTO/用户 Map/集合实参、StringBuilder、SqlSession 字符串入口、selectKey、自定义驱动、初始化脚本。先验证“不伪造完整结果”，不能直接要求成功提取。
3. **扩展输入与 SQL 内容矩阵**：用相同独立 SQL/绑定对照方式覆盖边界值、非法分支、批次先后关系和方言。模板、调用、输入模型、异常诊断分开统计。

增加 shared-test-resources 中的目标源码时，仍需让 main/测试驱动实际调用，并给出 SQL/绑定断言；
仅在文件里写出一个方法或标签，不能算端到端用法覆盖。

## 主要核对来源

- [JDBC 模块支持与签名规则](../jsqlint-jdbc/README.md)
- [MyBatis 模块支持与边界](../jsqlint-mybatis/README.md)
- [共享样例清单](../shared-test-resources/README.md)
- [JdbcDataFlowTest](src/test/java/org/u80dc/jsqlint/jdbc/JdbcDataFlowTest.java)
- [JdbcParameterInferenceTest](src/test/java/org/u80dc/jsqlint/jdbc/JdbcParameterInferenceTest.java)
- [SqlCallSiteDiscoveryTest](src/test/java/org/u80dc/jsqlint/core/SqlCallSiteDiscoveryTest.java)
- [MybatisDefinitionTest](src/test/java/org/u80dc/jsqlint/mybatis/MybatisDefinitionTest.java)
- [MybatisExtractionTest](src/test/java/org/u80dc/jsqlint/mybatis/MybatisExtractionTest.java)
- [MybatisSqlSemanticsTest](src/test/java/org/u80dc/jsqlint/mybatis/MybatisSqlSemanticsTest.java)
- [MybatisProviderTest](src/test/java/org/u80dc/jsqlint/mybatis/MybatisProviderTest.java)
- [之前的 SQL 覆盖与正确性验证](SQL_EXTRACTION_VALIDATION.md)
