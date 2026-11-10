# jsqlint-jdbc

识别标准 JDBC SQL 调用，并适配 Statement 工厂、参数绑定和批次语义。
引入 `jsqlint-core` 和 `jsqlint-jdbc` 后，默认 `Analyzer` 自动发现本模块；
分析源码或 Maven/Gradle 项目时另引入 `jsqlint-commons`。
统一入口与样例契约见 [分析器使用说明](../jsqlint-core/ANALYSIS.md)。

## 签名与关联

`JdbcMethodMatcher` 为入口发现、SQL 实参起点和 Statement 状态追踪提供同一份规则。
规则读取运行 JDK 的 Connection、Statement、PreparedStatement、CallableStatement 接口元数据，
按方法名、全部参数类型和声明类型的继承关系匹配实例调用；返回类型允许协变。
目录还统一声明 SQL、绑定键和绑定值的实参位置。

| 调用 | 分析行为 |
|---|---|
| Connection.prepareStatement / prepareCall / nativeSQL | 从标准重载的 SQL 实参追踪 |
| Connection.createStatement | 保存 Statement 工厂来源 |
| Statement 带 SQL 的 execute / executeQuery / executeUpdate / executeLargeUpdate / addBatch | 追踪 SQL 实参 |
| PreparedStatement / CallableStatement 无参执行与 addBatch | 关联工厂 SQL 与到达该位置的绑定 |
| 标准 setXxx / clearParameters | 处理位置或命名绑定、覆盖与清除 |
| addBatch / clearBatch / executeBatch / executeLargeBatch | 保存加入时快照，查询有效批次历史 |

`setNull` 的输入值是真实 null，JDBC 类型参数不作为绑定值；输出参数声明不创建输入绑定。
标准接口、子接口和可解析的实现类使用相同规则，包括 SQLType setter、数组参数和工厂协变返回。

扩展重载不直接套用标准 JDBC 语义。例如 `prepareStatement(String label, String sql)`
不能把 label 当作 SQL；扩展 setter、清除和批次方法也不会按名称直接修改标准状态。
若项目内包装方法委托标准调用，core 的通用过程间追踪仍会进入其方法体并找到实际 SQL 或绑定。
无法恢复的来源保留诊断和不完整状态。

## 模块边界

JDBC 签名、工厂、参数位置及绑定/批次的状态转移位于本模块。
core 提供调用解析、对象来源、别名判断、控制流遍历、SQL 文本恢复、值传播和联合推断。
每个调用上下文使用独立状态；可能别名保留候选，确定别名才覆盖或清除历史。
分析不连接数据库，也不执行目标项目代码。
