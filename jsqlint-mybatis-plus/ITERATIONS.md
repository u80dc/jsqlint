# 第五至八阶段实施顺序

按依赖顺序交付，每阶段先形成可运行实现与真实框架对照，再进入下一阶段。
保持唯一 MyBatis 扩展入口、通用结果契约和未知来源诊断。

## 第五阶段：Lambda 与显式 XML

当前实现：LambdaQueryWrapper/LambdaUpdateWrapper、无参 lambda 工厂、QueryWrapper.lambda 转换；
读取 getter 方法句柄，映射继承字段与查询列别名，不执行 getter。
显式 XML 中 customSqlSegment/sqlSegment/sqlSet/sqlSelect 在原 SQL 树中替换，
保留参数来源、绑定顺序、控制节点、定义标识与元数据。
MyBatis 参数命名通过 MybatisSqlTemplates.parameters 复用标准 ParamNameResolver。
未知方法引用、捕获/任意 Lambda 表达式及未建模 Wrapper 属性保留不完整结果。

## 第六阶段：Service、链式及批量 API（首批已实施）

先实现具体 ServiceImpl 泛型到 Mapper/实体的关联及 save/getById/list/update/remove；
再恢复查询/更新链的终结调用，最后支持有限集合批量 CRUD。
复用 Mapper 定义与参数适配；逻辑 Mapper 调用关系需在来源中保留。
批次、参数、顺序和对象身份独立保存，未知集合长度不得编造批次。
目标项目自行覆盖的方法继续分析真实方法体，避免与 Service 模型重复提取。
已落地范围与有限集合快照边界见 STAGE6.md；复杂集合与条件批量 API 仍未支持。

## 第七阶段：插件改写（显式配置已实施）

基于显式、不可变的插件配置及顺序，依次加入分页、租户、动态表名和乐观锁。
区分生成 SQL 与插件改写 SQL，额外 count SQL 独立保存来源和绑定。
数据库方言和插件版本纳入对照矩阵，动态配置未恢复时保留未知改写状态。
不得用简单字符串替换冒充 SQL 语法树级插件行为。
已落地范围、钩子顺序、count 及未知值边界见 STAGE7.md。

## 第八阶段：配置与版本（资源配置与显式注入器模型已实施）

读取可静态恢复的常见命名、字段策略、逻辑删除、插件及版本配置；
记录默认值、项目配置和调用方覆盖的来源与优先级。
按真实依赖版本分别对照，不将 3.5.16 模板静默用于其他版本。
按实际注入器扩展协议接入明确支持的自定义方法，其余保留诊断。
配置读取不加载或执行目标项目配置类、静态初始化器和 Bean 方法。
已落地配置入口、来源优先级、版本限制及注入器模型边界见 STAGE8.md。

每阶段验证包括真实 BoundSql、既有 MyBatis/core 回归与独立项目样例。
Spring Bean 自动解析与其他版本对照仍是后续扩展，不代表当前已支持。
