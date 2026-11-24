# jsqlint-mybatis-plus

MyBatis-Plus 的静态 SQL 适配模块，依赖 `jsqlint-mybatis`，通过其 `MybatisExtension` SPI 接入。
MyBatis 仍负责统一发现、显式定义选择、模板解析和参数提取；Plus 不重复注册 core 分析模块。

支持 MyBatis-Plus **3.x**，根据目标框架的注解默认值、SQL 模板和方法实现选择兼容规则。
这是版本接纳范围；具体 API、配置和插件仍按已实现能力处理。
真实验证覆盖 `3.0-RELEASE` 至 `3.5.18.1` 的 12 个发行版本、264 个 SQL/绑定对照。
版本列表、能力边界和 Oracle 的 JDK 前提见 [3.x 兼容性](VERSION_COMPATIBILITY.md)。

实体参数来源的职责划分、完整性处理和验证范围见 [第二阶段记录](STAGE2.md)。
基础写入模板、字段/主键策略及验证范围见 [第三阶段记录](STAGE3.md)。
字符串 Wrapper 的调用历史、条件、嵌套 Consumer 和验证范围见 [第四阶段记录](STAGE4.md)。
Lambda 与显式 XML 协作见 [第五阶段记录](STAGE5.md)，后续实施边界见 [迭代顺序](ITERATIONS.md)。
Service、链式与有限批量调用见 [第六阶段记录](STAGE6.md)。

## 使用

引入 `jsqlint-mybatis-plus` 后，默认 `Analyzer` 自动发现 MyBatis，MyBatis 自动发现 Plus 扩展。
源码及 Maven/Gradle 项目输入还需 `jsqlint-commons`。

```java
var bound = new Analyzer().bind(Path.of("target/classes"), Path.of("dependency-jars"));
var result = bound.analyze(AnalysisOptions.defaults(),
    "<com.example.UserMapper: java.lang.Object selectById(java.io.Serializable)>");
```

方法签名使用 JVM 擦除类型，并保留具体 Mapper 类型：`BaseMapper<T>.selectById` 返回值为
`java.lang.Object`；`deleteById(Serializable)` 返回 `int`。JVM 方法引用格式也可使用。
直接指定继承接口方法返回该方法的 SQL 定义；指定业务方法则提取它及下游调用的 SQL。

显式配置：

```java
var plus = new MybatisPlusExtension(MybatisPlusOptions.defaults()
    .withTablePrefix("app_")
    .withSchema("audit")
    .withLogicDelete("deleted", "0", "1"));
var mybatis = new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(plus));
var analyzer = new Analyzer(List.of(mybatis), List.of());
```

`MybatisAnalysisModule(options, List.of())` 关闭所有 MyBatis 扩展；显式列表替代自动发现。
每次分析建立新的实体映射和定义缓存，同一个 Analyzer 可以分析不同项目。

## 当前支持范围

针对 **MyBatis-Plus 3.x、默认 SQL 注入器、默认注解处理器及明确支持的显式配置** 建模。
目标框架版本从其 JAR 的 Maven/Manifest 元数据读取；3.x 不因小版本号不同而被拒绝。
SQL 差异从目标字节码读取，未建模的具体方法或配置返回相应诊断。
显式 XML、注解及 Provider 继续由 MyBatis 处理；显式定义冲突或解析失败不会回退到生成 CRUD。

- 多层 `BaseMapper<T>` 泛型继承，解析具体实体类型。
- `TableName`、`TableId`、`TableField`、`TableLogic`，继承字段、同名遮蔽、排除字段、查询列选择及列别名。
- 默认主键 `id`、下划线命名、表前缀、schema、大写命名、逻辑删除字段与取值配置；按目标默认注入器决定逻辑删除是否生效。
- `selectById(Serializable)`、`deleteById(Serializable)`、`deleteById(Object)` 和 `deleteById(Object, boolean)` 的定义恢复。
- 标量 ID 的调用参数追踪；逻辑删除生成 UPDATE，SELECT/DELETE 保留未删除条件。
- 实体主键及模板实际读取的标量属性：构造器、字段初始化/赋值、setter/helper 的真实方法体和对象别名。
- 公开 getter 优先于字段，boolean `isX` 优先；支持计算型 getter、继承字段及实际分配类型上的覆盖。
- `deleteById(entity)` 将属性值及来源冻结成普通 Map，显式 XML 可以读取 `id`、`name`、`_parameter.name` 等。
- 属性绑定保留调用发生前的有效写入；不同调用和分支保持关联，未赋值的新对象引用字段保留真实 null。
- `insert(entity)`、`updateById(entity)`：按字段顺序生成列、SET 与绑定，支持无主键插入。
- 全局及注解字段策略 `ALWAYS`、`NOT_NULL`、`NOT_EMPTY`、`NEVER`；primitive 除 NEVER 外始终输出。
- `AUTO` 主键按目标实现选择始终忽略或非 null 时插入；`INPUT`/`NONE` 保留输入；`ASSIGN_ID`/`ASSIGN_UUID` 已有值保留，自动生成值保持未知。
- 兼容旧 `ID_WORKER`、`ID_WORKER_STR`、`UUID` 和 `IGNORED` 策略，以及旧 `TableField.strategy`/全局 `field-strategy`。
- `TableField.update` 更新表达式、jdbcType/numericScale 提示；更新排除主键和逻辑删除字段，保留未删除条件。
- 字符串 QueryWrapper/UpdateWrapper：比较、LIKE、有限 IN、排序、SET、条件重载及嵌套 AND/OR。
- selectList/selectCount/selectMaps/selectObjs、delete(Wrapper)、update(entity, Wrapper)，复用统一 SQL 与绑定结果。
- `selectCount` 按目标 SQL 模板和方法选择默认计数表达式、别名及显式选列；`selectObjs` 保留框架实际查询的全部选择列。
- 字段收集和 Wrapper 根条件括号按目标实现处理；旧 HashMap 字段顺序的运行 JDK 前提见兼容性说明。
- LambdaQueryWrapper/LambdaUpdateWrapper 的 getter 列引用、查询列别名，以及显式 XML 的 Wrapper SQL 片段和命名参数协作。
- 具体 ServiceImpl 的 Mapper 泛型关联、常用 CRUD、字符串/Lambda 链及有限 List.of 的 saveBatch/updateBatchById，保存逻辑 Mapper 关系及逐项绑定。
- 显式 PlusPluginConfig 的租户、动态表名、MySQL/PostgreSQL 分页及 updateById 整数乐观锁，独立 count 和逐步 SQL/绑定证据；配置示例与边界见 [STAGE7.md](STAGE7.md)。
- 无参扩展读取 application/bootstrap properties/YAML 的全局命名、逻辑删除和字段策略，保存配置来源和覆盖策略；显式版本绑定的自定义注入器模型见 [STAGE8.md](STAGE8.md)。

直接分析没有调用方的定义，可通过 `result.definitions().getFirst().sample(Map.of("id", value))`
显式渲染参数。调用参数推断遵循 core 的模式配置；EXTRACT 模式输出未知绑定。

未建模的外部 mutation/堆容器逃逸、未知入口对象属性和预算耗尽保留不完整来源；
没有使用的 getter 不参与追踪。非公开/static/歧义 getter、嵌套对象与集合属性的完整恢复尚未支持。
当前属性追踪不运行目标 getter，未支持 getter 不会静默回退到字段读取。

通过 `withIdType("AUTO")`、`withFieldStrategies("NOT_EMPTY", "NOT_NULL")` 设置全局策略，
注解的 NONE/DEFAULT 继承全局值，明确注解覆盖全局值。旧八参数 Options 构造器继续使用默认策略。
动态模板条件目前未纳入 Java 联合路径求解，调用分析保留 PATH_COVERAGE 不完整诊断，
不影响已恢复样例的 SQL 和绑定。自动填充、序列主键及未支持的乐观锁场景保留说明；不会执行生成器或填充器。

非 getter Lambda、复杂集合/Wrapper mutation、复杂 Wrapper 属性表达式、更新填充、其余 Service/批量 API、任意自定义 SQL 注入器的自动恢复、
自定义 TypeHandler、其余拦截器及 Spring 配置自动读取尚未实现。
已识别的未支持内置方法、缺失实体/主键等返回不完整结果及问题说明。
项目资源按上述范围静态读取；其余运行配置由调用方提供实际快照，不执行目标配置代码。

分析器仅读取字节码和资源，不加载目标实体，不执行 Mapper 或数据库 SQL。
统一测试模块通过受控实体的真实 MyBatis-Plus `MappedStatement`/`BoundSql` 校验 SQL、占位符及绑定。
模块依赖的 `mybatis-plus-jsqlparser:3.5.16` 提供分析器内部的 SQL 改写工具，目标项目的 3.x 版本通过独立分析输入读取。
统一测试模块的真实版本矩阵覆盖默认 CRUD/Wrapper；Service 与插件的验证版本和具体场景单独记录，
不将该矩阵视为所有 3.x API 或任意插件组合的覆盖证明。
