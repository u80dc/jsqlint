# 第三阶段：基础 CRUD 写入

在已有 selectById/deleteById 和实体属性来源基础上实现 insert/updateById。
固定 MyBatis-Plus 3.5.16 默认 SQL 注入器；仅分析字节码与资源，不执行目标实体、Mapper、生成器或数据库。

## 实现划分

`PlusEntityMetadataReader` 解析主键策略、insert/update 字段策略、更新表达式与绑定选项。
`MybatisPlusOptions` 显式提供全局策略，注解 NONE/DEFAULT 回退到全局值。
`PlusCrudTemplates` 生成动态写入模板，复用 MyBatis 解析与参数化渲染。
`PlusEntityParameters` 继续复用 core 属性来源和 Map 快照；updateById 同时提供 et 别名。
core 没有引入 Plus 专属类型或写入规则。

插入主键先于普通字段；同一条件控制列和值，确保绑定位置一致。
ALWAYS、NOT_NULL、NOT_EMPTY、NEVER 与 primitive 规则按框架实现处理。
更新主键仅用于 WHERE；逻辑删除字段不进入 SET，WHERE 保留未删除条件。
字段更新表达式按框架 String.format 规则使用列名，表达式无需值绑定时不额外插入占位符。

## 完整性

AUTO 主键值为 null 时省略列；非 null 时保留输入。INPUT 不自动生成。
ASSIGN_ID/ASSIGN_UUID 在已有值时保留输入，null 或字符串空值触发生成时使用未知绑定。
自动填充字段保留未知来源和说明，不能把填充前的值认定为最终值。
序列生成器、乐观锁插件与自定义运行配置仍未建模，保留不完整说明。
先前 Mapper 调用对实体的堆效果尚未建模；再次使用该实体时保留未知，而不是假设调用没有修改属性。

动态字段条件尚未纳入 core 的 Java 联合路径约束，调用分析保留 PARTIAL/PATH_COVERAGE 诊断。
真实输入模型的 SQL 与绑定可读取；不据一个渲染样例声明所有模板分支已覆盖。

## 验证

`MybatisPlusCrudTest` 对照真实默认注入器的 BoundSql 和按位置读取的绑定值，覆盖字段策略、
null、空串、primitive、主键策略、无主键插入、更新表达式、逻辑删除、全局策略及注解覆盖。
另验证生成值和填充的不完整状态，以及独立样例的四个 CRUD 调用和绑定隔离。

独立项目位于 `shared-test-resources/mybatis-plus-crud`，已通过 Maven package；
仅依赖固定版本 Plus，不需要数据库。
2026-11-20 验证：119 项既有相关回归通过；样例对象复用假设修正后，11 项 CRUD 测试全部通过。
既有回归日志为 `target/plus-stage3-final.log`（其中记录修正前的样例测试异常），
最终 CRUD 日志为 `target/plus-stage3-final-crud.log`，Maven BUILD SUCCESS。

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am '-Dtest=MybatisPlusCrudTest,MybatisPlusEntityParameterTest,MybatisPlusAnalysisTest,MybatisExtractionTest,MybatisProviderTest,AnalyzerMethodTest,CorePackageArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```

Wrapper、Lambda Wrapper、Service/批量调用和插件改写继续按后续阶段推进。
