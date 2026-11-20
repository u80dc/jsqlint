# 第四阶段：字符串列 Wrapper

固定 MyBatis-Plus 3.5.16，恢复 QueryWrapper/UpdateWrapper 的操作顺序、WHERE、SET、排序与绑定。
仍通过唯一的 MyBatis 扩展链路提取，不执行目标 Wrapper、Consumer、Mapper 或数据库。

## 支持范围

Mapper 方法：selectList、selectCount、selectMaps、selectObjs、delete(Wrapper)，以及 update(entity, Wrapper)。
支持明确分配的字符串 Wrapper、Wrappers.query/update 无参工厂、局部别名与应用辅助方法。

- eq/ne/gt/ge/lt/le、between/notBetween、isNull/isNotNull。
- like/notLike、likeLeft/likeRight、notLikeLeft/notLikeRight，保留通配符转换及值来源。
- in/notIn：有限长度数组、List.of、同方法可追踪的 Arrays.asList；按元素恢复绑定。
- select、groupBy、orderByAsc/orderByDesc/orderBy，多列及连续排序保持顺序。
- set 与显式绑定选项；entity SET 在 Wrapper SET 之前，WHERE 绑定在 SET 之后。
- boolean 条件重载、or、and/or/nested/not Consumer、clear。
- 静态 LambdaMetafactory Consumer 方法体及捕获实参；嵌套条件保留括号结构。
- 普通删除、逻辑删除与默认查询的未删除条件。

## 实现边界

`PlusWrapperParameters` 使用 core 的 origins/backward 查询具体对象的调用历史，
用冻结 ValueTrace 保存列、值、条件与数组元素来源。
Consumer 使用逻辑调用上下文和捕获值模型，不调用目标代码。
Plus 负责拼接动态模板，MyBatis 继续负责表达式、SQL 节点解析、渲染和绑定位置。
`PlusCrudTemplates` 的 updateSet 片段复用于 entity+Wrapper 更新。
core 的 HeapExposure 同时修正已知具体类的类型兼容判断：无继承关系的两个具体类
不能指向同一对象；Object、接口和缺失类型继续保守处理，堆容器的间接逃逸仍保留。

条件为 false 时不消费待应用的 OR；后续实际输出的谓词仍取得正确连接词。
生成的参数名避开实体属性名。显式 XML/注解不会被生成 CRUD 替换。

## 完整性与后续阶段

未知条件、外部对象输入、多个 Java 控制流历史、循环、预算耗尽和未支持操作保留不完整结果。
多个历史不合并成一个声称已知的 SQL；当前返回未知结构及诊断。
直接外部调用或堆存储逃逸保持未知；先前 Mapper 调用的堆效果仍保守处理。
无法枚举的集合、未知数组长度、带实体的 Wrapper 构造器/工厂和非静态 Consumer 尚未建模。

显式 SQL 中的 ew.customSqlSegment 等协作属于第五阶段，当前保留显式定义和未支持说明。
LambdaQueryWrapper/LambdaUpdateWrapper、apply/last/having/setSql、复杂集合 mutation、
Service/批量 API 和插件改写留待后续阶段。

core 尚未将模板条件纳入 Java 联合路径求解，路径覆盖保留 PARTIAL。
方法范围发现对未解析的库调用也保留诊断，恢复了一个具体 SQL 样例不代表整个方法分析完整。

## 验证

MybatisPlusWrapperTest 使用真实 Wrapper 和默认注入器的 BoundSql，
比较每个已知 SQL 样例及全部按位置绑定值。
覆盖嵌套捕获值、多列排序、条件连接词、IN/LIKE、SET 映射、数组别名、
外部未知条件、堆逃逸、循环和显式 XML 优先。

独立项目 shared-test-resources/mybatis-plus-crud 已增加
example.UserService.stringWrappers，包含查询、更新和逻辑删除，构建不需要数据库。

2026-11-20 最终相关回归：196 项测试通过，失败、错误和跳过均为 0，Maven BUILD SUCCESS。
其中 Wrapper 18 项；其余覆盖 JDBC、MyBatis、CRUD、实体逃逸、方法范围、分派和包依赖。
最终日志为仓库根目录 target/plus-stage4-verification.log；独立项目构建日志为 target/plus-stage4-sample.log。

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am '-Dtest=MybatisPlusWrapperTest,MybatisPlusCrudTest,MybatisPlusEntityParameterTest,MybatisPlusAnalysisTest,MybatisExtractionTest,MybatisProviderTest,AnalyzerMethodTest,CorePackageArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```
