# 第七阶段：显式插件 SQL 改写

基于 MyBatis-Plus 3.5.16 的模型侧插件及 JSQLParser，在 MyBatis 完成模板渲染后改写 SQL。
配置由调用方显式提供，冻结插件顺序、租户值、表名映射及分页选项；不执行目标项目 Handler、Bean 或数据库查询。

```java
var plugins = new PlusPluginConfig(List.of(
    new PlusPluginConfig.Tenant("tenant_id", new SqlValue.Known(7L)),
    new PlusPluginConfig.DynamicTables(Map.of("users", new SqlValue.Known("users_2026"))),
    new PlusPluginConfig.Pagination(PlusPluginConfig.Dialect.MYSQL),
    new PlusPluginConfig.OptimisticLock()));
var plus = new MybatisPlusExtension(MybatisPlusOptions.defaults(), plugins);
var module = new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(plus));
```

## 已实现

- TenantLine：已知整数、字符串或 null 租户值，忽略表集合；调用实际插件解析 SQL，覆盖查询、JOIN、插入及更新/删除。
- DynamicTableName：按不可变表名映射调用实际插件改写并校验 SQL；未知映射值返回不完整结果。
- Pagination：MySQL/PostgreSQL；恢复 Page 构造器、Page.of 和已支持 setter 的静态值，支持 current/size、searchCount、count 优化及 maxLimit。
- count 独立输出，定义元数据 `statementRole=count/main`；保留 count 优化/回退后的实际绑定和新增分页绑定的 Java 来源。
- OptimisticLocker：默认注入器生成的 updateById，Integer/Long 版本增量及原版本 WHERE 绑定；保留 Java 整数溢出语义。
- `SqlSample.rewrites()` 按执行顺序保存每一步 SQL、绑定及诊断；`SqlDefinition` 保留原模板以供重演。

实际钩子阶段决定执行顺序：更新的乐观锁先于生成 SQL；租户和动态表名按 beforePrepare 顺序执行。
SELECT 按配置顺序执行，count 在分页插件位置生成，之后的插件只改写主查询。
分页 count 的数据库结果未知，主查询作为候选 SQL 保存不完整执行覆盖诊断；overflow 可能重置页码时主 SQL 保持未知。

## 边界

仅支持 3.5.16，目标版本未知或不同会保留插件诊断。
Page 参数需明确声明为 IPage，并能静态恢复；未知值、逃逸、复杂控制流、orders/countId 等保持不完整。
显式 XML/注解/Provider 仍由 MyBatis 提取，模板来源可以改写；值形式的 Provider SQL 暂只保留未支持说明。
显式 SQL/Wrapper 更新的乐观锁、时间或自定义版本类型、Spring 插件配置自动读取及自定义拦截器尚未支持。
直接访问 Wrapper.paramNameValuePairs 的框架内部懒生成编号尚未恢复，绑定保持未知；已支持 SQL 片段中的绑定保留来源。
第八阶段的配置自动读取和多版本矩阵尚未实施。

## 验证

MybatisPlusPluginTest 使用真实 InnerInterceptor 的 willDoQuery/beforeQuery/beforeUpdate/beforePrepare 钩子，
对照最终 SQL、全部绑定与独立 count；受控 Executor 返回 count，不访问数据库。
覆盖插件顺序、租户插入/JOIN/忽略表、动态表名、两种方言、maxLimit、count 投影参数、乐观锁和未知值。
SqlBoundTransformTest 校验原模板、参数、改写证据、定义适配及重演约束。

本阶段覆盖 166 项插件、既有 MyBatis/Plus 和 core 测试；发现并修正分页默认方法的 XML statement 转接后，
相关定义选择、Lambda/XML、改写契约及全部 10 项插件用例已复测通过。
独立 shared-test-resources/mybatis-plus-crud 项目 package 构建通过。
