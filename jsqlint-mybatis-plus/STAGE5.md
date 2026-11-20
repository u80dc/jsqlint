# 第五阶段：Lambda Wrapper 与 XML 协作

支持 LambdaQueryWrapper、LambdaUpdateWrapper，复用第四阶段的比较、LIKE、IN、SET、排序和嵌套逻辑。
列引用读取 LambdaMetafactory 的方法句柄，核对实体类型、继承关系和属性映射。
WHERE 使用真实列名；Lambda select 使用实体查询列映射及必要的 AS 别名。
保留方法引用的位置和 getter 签名，不调用目标 getter。
无参 Wrappers.lambdaQuery/lambdaUpdate 与字符串 Wrapper.lambda 转换共用对象历史。

显式 XML/注解保持原定义和 SQL 树，通过 PlusTemplateSegments 替换 Wrapper 原样值节点：
customSqlSegment、sqlSegment、sqlSet、sqlSelect。customSqlSegment 仅在存在条件时输出 WHERE；
仅有排序时不生成空 WHERE。不额外注入生成 CRUD 的逻辑删除条件到显式 SQL。
替换保留其他控制节点、文本、参数表达式和绑定顺序。

MybatisSqlTemplates.parameters 接受按实参下标提供的建模来源，复用现有参数命名、
@Param、通用 paramN 名称和 _parameter 规则；不重新公开 MyBatis 的内部索引。
Wrapper 根冻结为只读 Map 语义，动态 SQL 片段自己的列、值和条件来源进入 renderParameters，
不混入业务参数 Map。自定义 Mapper 接口的 Wrapper 方法仍优先使用显式定义。

尚未恢复的非 getter Lambda、捕获的列引用、匿名 SFunction、复杂 Wrapper 属性表达式、
实体条件构造器及未知路径继续返回不完整来源。
core 的联合模板路径覆盖限制继续保留；一个已恢复 SQL 样例不代表完整方法覆盖。

MybatisPlusLambdaXmlTest 对照真实框架的查询、更新、查询列别名、嵌套条件、
方法引用数组、自定义 XML、排序-only 片段、@Param 标量与位置绑定，并验证未知 Lambda。
独立样例新增 example.UserService.lambdaAndXml 及 example/UserMapper.xml。
后续阶段边界见 ITERATIONS.md；第六阶段首批实现见 STAGE6.md，第七、八阶段尚未实施。

2026-11-20 验证：165 项相关回归通过，随后补充的继承/boolean getter 与工厂转换边界全部通过，
最终 Lambda/XML 测试共 8 项，失败、错误和跳过均为 0。
日志为 target/plus-stage5-verification.log 和 target/plus-stage5-final.log。
独立项目 Maven package 通过，构建日志为 target/plus-stage5-sample.log。
