# MyBatis-Plus 3.x 兼容性

默认 CRUD、Wrapper 和已建模的 Service 调用接纳可识别的 MyBatis-Plus 3.x 发行版本，包括
`3.0-RELEASE`、多段版本号和发行后缀。3.x 输入不需要列入小版本白名单；具体方法、实体和配置
仍须满足相应模型的能力要求。下方实测版本是验证范围，不是版本接纳列表，也不表示所有 3.x API 都已建模。

生成模型读取目标项目的真实类、注解默认值和资源。版本优先取对应框架模块的 Maven
`pom.properties`，再取 Manifest；早期发行包缺少元数据时使用真实框架 JAR 的标准文件名。
这也避免聚合 JAR 的应用版本遮盖其中的框架版本。无法识别版本或方法能力时保留具体诊断。

版本范围和方法能力分别判断。实体泛型、实际参数签名、注解默认值、默认注入器及项目配置
共同决定 SQL；新 API、外部对象 mutation、未知注入器和未建模的插件行为仍保留具体诊断。
可见的 core、extension、service/spring 组件使用一致的框架版本，混用组件不自动视为兼容。

`mybatis-plus-jsqlparser:3.5.16` 是分析器内部使用的 SQL 解析/改写 SDK。它不限制目标项目的
3.x 版本，也不替换目标 Maven 依赖或实体。目标字节码通过独立分析输入读取；测试 Oracle
在隔离 classloader 或独立 JVM 中运行对应版本的受控框架代码。

## 从目标读取的差异

| 差异 | 处理方式 |
| --- | --- |
| `COUNT(1)`、`COUNT(*)`、计数别名、Wrapper 选列 | 读取目标 `SqlMethod.SELECT_COUNT` 与 `AbstractMethod.sqlCount` 的可达 helper |
| AUTO 主键插入 | 读取目标 `TableInfo.getKeyInsertSqlProperty` 是否构造条件插入 SQL |
| 默认逻辑删除 | 读取默认 `DeleteById` 是否处理逻辑删除；早期 3.0 默认注入器保持普通 SELECT/DELETE |
| 旧主键与字段策略 | 归一 `ID_WORKER`、`ID_WORKER_STR`、`UUID`、`IGNORED`，保留 `NONE` 的不生成主键语义 |
| `TableField.strategy` 与分离的插入/更新策略 | 读取实际注解默认值和目标 `DbConfig` 属性布局 |
| 反射字段收集和遮蔽 | 读取目标 `ReflectionKit` 的过滤列表、HashMap 或 LinkedHashMap 收集方式；数据库排除在反射收集后处理 |
| Wrapper 根条件分组 | 读取目标 `NormalSegmentList` 是否添加外层括号，保留目标 SQL 结构 |
| 旧分页语句与新版默认方法委托 | 按目标 BaseMapper 方法与默认注入语句匹配 |
| Service 类型结构 | 读取实际存在的 IService、ServiceImpl、IRepository 等类型，不要求旧版本具有新类型 |
| 框架模块拆分 | 分页/租户的 jsqlparser 与 Service 的 spring 模块 Maven 元数据优先于 Manifest，旧版本回落到 extension |
| 禁用版本字段 SET 的乐观锁 | `updateStrategy=NEVER` 时单独追踪原始字段并生成版本 WHERE；null 不加条件，未知来源保留诊断 |

早期显式 `LogicSqlInjector` 和任意自定义注入器需要调用方提供明确的注入器模型；
普通默认注入器不会因实体具有 `@TableLogic` 就被推定为另一种注入器。
显式 XML、SQL 注解及 Provider 继续通过 MyBatis 模块分析。

## 已完成的真实版本矩阵

统一测试模块使用实际发布的目标依赖编译受控实体、Mapper 和 Wrapper，分析输入只包含
对应版本的 class/JAR。运行时 oracle 在隔离 classloader/JVM 中生成同版本 BoundSql，
逐调用比较完整 SQL 文本和有序绑定值，不通过修改 JAR Manifest 模拟跨版本 SQL 正确性。

| 系列 | 实际验证的发行版本 |
| --- | --- |
| 3.0 | `3.0-RELEASE`、`3.0.7` |
| 3.1 | `3.1.2` |
| 3.2 | `3.2.0` |
| 3.3 | `3.3.2` |
| 3.4 | `3.4.3.4` |
| 3.5 | `3.5.0`、`3.5.3.2`、`3.5.6`、`3.5.9`、`3.5.16`、`3.5.18.1` |

`MybatisPlusVersionMatrixTest` 的 12 个版本全部通过，每版比较 22 个普通/逻辑实体场景，
共 **264 个 SQL 文本与有序绑定值对照**。场景包含 `selectById`、null/String/Lambda Wrapper 的
`selectList`、默认与显式选列的 `selectCount`、`selectObjs`、非 null/null AUTO ID 的 `insert`、
`updateById`、`deleteById`，并验证列别名、字段顺序和默认注入器的逻辑删除行为。

该矩阵使用默认配置和受控标量实体；以下独立测试补充对应能力，覆盖范围分别计量：

| 能力 | 实测与边界 |
| --- | --- |
| 实体和配置 | 真实注解默认值、旧 ID/字段策略、字段遮蔽和排除由配置/元数据测试验证；任意自定义 TypeHandler、填充器和配置代码不自动执行 |
| Service | `MybatisPlusLegacyServiceTest` 验证真实 `3.3.2` 的 save/getById/removeById/list/count 与 IService 别名；3.5.16 回归覆盖常用 Service、链及有限批量，未建模的方法保留诊断 |
| 插件 | 3.5.16 测试覆盖显式租户、动态表名、MySQL/PostgreSQL 分页和整数 updateById 乐观锁；旧 `3.0.7`、`3.3.2` 对照覆盖 MySQL 分页和整数乐观锁的协议差异 |
| 显式 SQL | XML、SQL 注解和 Provider 继续使用 MyBatis 的定义/参数模型；冲突或损坏的显式定义不回退成默认 CRUD |

补充边界测试已通过：`MybatisPlusOptimisticNeverTest` 对照真实 3.3.2/3.5.16 的
NEVER 版本字段、null、Integer.MAX_VALUE、计算 getter 与未知外部来源；
`MybatisPlusExtractWrapperTest` 验证 XML foreach 的 item/index 名称避让及三种推断模式；
`PlusFrameworkArtifactMetadataTest` 验证组件 Maven 元数据优先级和组件版本混用诊断。
改元数据的 JAR 副本仅用于来源分类，跨版本 SQL 对照使用真实发行包。

本次合并每个测试类的最终结果，共 **45 个测试类、429 个测试用例通过**，失败、错误和跳过均为 0；
重复执行不累计。验证范围包含 Core 模板、JDBC/MyBatis 和 Plus 相关回归，未计入其它项目测试。
逐类记录见 `jsqlint-test/target/mybatis-plus-3x-validation.md`，最新类报告在同模块 `target/surefire-reports/`。
版本矩阵原始日志为仓库根目录 `target/plus-three-x-matrix-and-plugins-final.log`。

## 矩阵运行方式与 JDK 前提

从仓库根目录运行：

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am '-Dtest=MybatisPlusVersionMatrixTest' `
  '-Djsqlint.plus.oracle.javaHome.3.3.2=D:/Jdk/hotspot/jdk8u392-b08' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```

上述 JDK8 路径是本次验证机器的示例，请替换为本机兼容 JDK 的安装目录。
可通过 `'-Djsqlint.plus.versions=3.0.7,3.3.2,3.5.16'` 缩减版本，首次运行需要从 Maven Central
解析真实依赖。版本报告保存至 `jsqlint-test/target/mybatis-plus-version-matrix/<version>.txt`，
独立 JVM 的日志保存至同目录 `<version>-oracle.log`。

Oracle 的选择顺序是 `jsqlint.plus.oracle.javaHome.<version>`、全局
`jsqlint.plus.oracle.javaHome`、当前 JVM 的隔离 classloader。独立 JVM 只加载目标 classes/JAR，
60 秒超时；每个版本报告记录实际 Java 版本、JavaHome 和框架来源。

真实 3.3.2 的旧 Lambda 缓存使用 lambda 类的 `getCanonicalName()` 作为 ConcurrentHashMap key。
JDK21 的 hidden lambda 返回 null，导致该 SDK 的运行时 Oracle 发生 NPE；本次仅该版本的
Oracle 使用 JDK8。分析器仍使用 Java21，目标 SDK 和输入字节码保持真实版本。

3.2.0 的字段收集使用 HashMap，迭代顺序与运行 JDK 有关。该版本的 Oracle 与分析器均在
同一 JDK21 上比较；不应把全矩阵 Oracle 统一切到 JDK8，再将由集合迭代产生的列顺序差异
归为框架版本模型错误。该 JDK 前提与每个版本的验证报告一起保留。

## 其他回归与保留的限制

元数据来源、3.x 发行版本识别、旧配置/策略、原始 BaseMapper 泛型边界及当前版本回归
分别由 `PlusFrameworkVersionTest`、`PlusClassMetadataTest`、`MybatisPlusOptionsTest`、
`PlusProjectConfigurationTest`、`BaseMapperSelectListAnalysisTest` 和各 MybatisPlus 测试覆盖。
真实 DPM 3.3.2 的 `NodeConnConfigServiceImpl#get(long)` 是可显式启用的项目回归用例。
它要求恢复具体 Mapper、实体、表和 `node_id = ?` 查询，EXTRACT 模式保留未知入口 nodeId。
原始 `BaseMapper<T>` 仍缺少具体实体映射；直接指定具体 Mapper 的 `selectList` 可得到动态定义，
完整样例还需要 Wrapper 输入。版本接纳不会消除这些输入约束。

复杂 Wrapper mutation、非 getter Lambda、未支持的 Service/批量 API、任意自定义 SQL 注入器
及未建模插件不会因属于 3.x 就被视为完整结果。模板条件尚未全部纳入 Java 联合路径求解，
路径覆盖诊断与已经恢复的 SQL/绑定分别保留。分页 count 的运行结果未知时，主查询执行和
overflow 分支也保留不确定性。
