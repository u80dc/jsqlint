# 第二阶段：实体参数来源

本阶段将 `deleteById(entity)` 的主键及模板使用的标量属性接入现有参数推断链路。
分析只读取目标字节码，不实例化目标实体或执行 getter。

## 职责与命名

`DataFlowAnalysis.traceProperty` 是通用属性追踪入口；`ObjectProperties` 是包级实现，
负责成员选择和到达写入查询。`PlusEntityParameters` 负责识别实体、选择模板需要的属性，
将属性来源组成 Map 快照；`MybatisPlusExtension` 负责框架方法与生成定义的适配。
这三个层次分别表达 Java 对象语义、实体参数和框架扩展，不需要在 core 引入 Plus 类型。

成员选择优先真实公开 getter，再读取字段；boolean `isX` 优先于同型 `getX`，
协变返回类型选择更具体的方法。歧义、非公开或 static getter 保留不完整来源。
已知唯一分配类型可用于选择覆盖方法，继承字段按真实声明归一化后匹配写入。

## 来源与完整性

字段值从属性使用位置沿对象历史逆向查找，复用调用上下文、对象身份、路径和堆逃逸模型。
构造器、setter、辅助方法及工厂返回对象继续分析真实方法体。
确定写入覆盖较早值；不同调用、对象和分支的属性来源保留关联。
新对象未赋值的字段使用 Java 默认值，未知入口对象不据此推断为 null。

外部 mutation、堆存储别名、未知身份、循环或追踪预算耗尽保留问题说明，
不将候选值冒充确定值，也不丢弃已发现 SQL。
只追踪模板可能读取的属性，未使用 getter 不污染绑定完整性。

## 验证与边界

`MybatisPlusEntityParameterTest` 覆盖构造器、初始化、覆盖写入、setter、别名、
计算及继承 getter、分支关联、真实 null、未知输入、外部调用、工厂、构造器委托、
堆存储别名、预算耗尽、歧义和非公开 getter。
受控样例同时通过真实 MyBatis-Plus 的 `MappedStatement`/`BoundSql` 对照 SQL 和 getter 绑定值。

2026-11-20 完成相关回归：173 项测试通过，失败、错误和跳过均为 0，Maven BUILD SUCCESS。
其中实体参数 13 项、Plus 定义 11 项；其余覆盖方法范围分析、程序索引与分派、
SQL 恢复预算、core 包依赖、JDBC、MyBatis XML/注解及 Provider。
回归日志为仓库根目录 `target/plus-stage2-verification.log`。

```powershell
.\mvnw.cmd -T 1 -pl jsqlint-test -am '-Dtest=MybatisPlusEntityParameterTest,MybatisPlusAnalysisTest,JdbcDataFlowTest,AnalyzerMethodTest,MybatisExtractionTest,MybatisProviderTest,ProgramIndexTest,ProgramDispatchSelectionTest,SqlRecoveryBudgetsTest,CorePackageArchitectureTest' '-DexcludedGroups=integration' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.skip=false' '-DskipTests=false' test
```

支持范围仍限于 README 中列出的 MyBatis-Plus 3.5.16 默认配置及方法。
嵌套对象、集合属性、Wrapper、insert/update、自动填充与拦截器等能力留待后续阶段。
