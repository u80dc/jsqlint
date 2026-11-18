# MyBatis 用法矩阵（第一批）

独立 Maven + MyBatis 3.5.19 + H2 项目，不依赖 jsqlint。
`main` 分别执行 `databaseId=h2` 和通用回退配置，实际调用全部 Mapper 方法并自动核验结果。

| 方法 | 用法及边界 |
|---|---|
| createTable / insertXml / deleteXml | XML update/insert/delete 与 select 一起覆盖四类标签；插入的 null 显式指定 jdbcType |
| chooseSearch | choose 两个 when 的优先级及 otherwise；trim 去除前导 AND/OR；null 分支 |
| trimUpdate | trim 去掉末尾逗号；同时更新两列、只更新一列；null 控制字段省略 |
| implicitNames | 不使用 @Param；`-parameters` 实际参数名与 param2 混用 |
| scalarFallback | 单个 long 参数允许任意占位符名称 |
| repeated | 同一值重复绑定两次，javaType/jdbcType 选项及顺序 |
| included | 跨文件和嵌套 include、property 别名；引用方先读取；全局 variables |
| databaseVariant | 同 id 的 H2 特定定义及通用回退定义 |

Mapper 有 10 个有效定义，`exercise` 包含 14 个调用位置。`chooseSearch` 走三种结构，
`trimUpdate` 走两种结构；独立验证另外补充空串、仅 email 更新和标量零值等输入。

跨命名空间的嵌套 include 使用完整 refid，避免 MyBatis 在引用方命名空间查找同名片段。
databaseId 与 variables 在运行程序中显式设置，Analyzer 也必须通过同值 `MybatisOptions` 配置；
不能将默认选项下的分析结果当作 H2 变体结果。

从仓库根目录运行：

```powershell
.\mvnw.cmd -f shared-test-resources/mybatis-usage-matrix/pom.xml compile exec:java
```

输出 `MyBatis usage matrix passed (h2 + default)` 表示两组数据库自检通过。
统一测试使用真实 `ParamNameResolver` / `MappedStatement.getBoundSql` 对照 Analyzer 保存的 SQL 和位置绑定。
动态 XML 和未建模的调用方条件仍可产生 UNKNOWN，不将文本正确等同于路径证明完整。

后续还需补齐 foreach、argN/数字参数名、特殊参数、Mapper/Provider 继承与复杂 SQL 构造器等，
详见 [补齐进度](../../jsqlint-test/SAMPLE_EXPANSION_PROGRESS.md)。
