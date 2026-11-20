# MyBatis-Plus 基础 CRUD 样例

独立 Maven 项目，JDK 21，固定 MyBatis-Plus 3.5.16。
在本目录运行 `mvn package`；不需要数据库或 Spring。
`example.UserService.createAndUpdate` 包含 insert、updateById、selectById 和 deleteById。
覆盖显式主键、NOT_EMPTY、ALWAYS、字段更新表达式和逻辑删除。
插入和更新使用独立实体；先前 Mapper 调用可能修改传入对象，复用该对象时当前追踪会保留未知堆效果。

向 Analyzer 提供 `target/classes`、Plus 依赖和 JDK 运行库后，分析方法：
`<example.UserService: void createAndUpdate(example.UserMapper)>`。
字段条件保留动态模板；联合模板分支覆盖尚未证明时返回 PARTIAL，SQL 样例及绑定仍可读取。

`example.UserService.stringWrappers` 包含独立 QueryWrapper 查询、UpdateWrapper SET 更新和逻辑删除。
覆盖 IN、LIKE、嵌套 AND/OR、排序及参数绑定；分析签名：
`<example.UserService: void stringWrappers(example.UserMapper)>`。

`example.UserService.lambdaAndXml` 覆盖 Lambda 查询列/getter 条件及自定义 Mapper XML 的
`${ew.customSqlSegment}` 与 `@Param("marker")` 绑定。分析时同时提供 `src/main/resources`，
签名为 `<example.UserService: void lambdaAndXml(example.UserMapper)>`。

`example.UserCrudService` 绑定 UserMapper/User，`example.ServiceUsage.run` 包含 Service CRUD、
字符串/Lambda 链和有限批量插入。签名为 `<example.ServiceUsage: void run(example.UserCrudService)>`。
分析输入需包含 core、extension、spring 的 3.5.16 JAR 与 JDK；不启动 Spring 或数据库。
