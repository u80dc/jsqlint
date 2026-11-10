# MyBatis 用户查询样例

独立 Maven 项目，使用 MyBatis 3.5.19 与内存 H2。XML、直接 SQL 注解和 Provider 共定义 13 条 SQL，
其中 11 条在 `main → UserService → UserMapper` 链路上被调用。
`unusedCount` 和 `unusedProviderCount` 验证未调用定义仍被发现。

`find` 使用 include，`rename` 使用 set，`search` 同时使用 `${column}` 和 `#{value}`，
`optional` 使用 bind/if/where。参数均为标量并显式声明 `@Param`。
自动化分析只编译该项目，不执行 main 或访问数据库。

`UserSqlProvider` 覆盖四类 Provider：

| Mapper 方法 | Provider 形式 |
|---|---|
| `providerFind` | 默认 ProviderMethodResolver、Map 参数、ProviderContext 与局部 SQL 构造器 |
| `providerInsert` | `value` 属性、静态方法与辅助方法返回 SQL |
| `providerRename` | 实例方法、ProviderContext 和命名参数、链式 SQL 构造器 |
| `providerDelete` | 静态方法与匿名 SQL 初始化器 |
| `unusedProviderCount` | 未调用的常量 Provider 定义 |

在引入 core、commons、mybatis 的应用中直接调用：

```java
var result = new Analyzer().analyze(Path.of("shared-test-resources/mybatis-user-query"));
for (var sample : result.samples()) {
    System.out.println(sample.definition().map(SqlDefinition::id));
    System.out.println(sample.sql());
    System.out.println(sample.bindings());
}
```

`optional` 的具体样例可生成，但首轮实现尚未联合求解 XML 条件，路径覆盖为 false，分析结果为 PARTIAL。
其他 SQL 的绑定参数保留为有序列表，字符串中的单引号不会被拼回 SQL。

手动运行样例可在本目录执行 `mvn compile exec:java`。
