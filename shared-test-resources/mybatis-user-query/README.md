# MyBatis 用户查询样例

独立 Maven 项目，使用 MyBatis 3.5.19 与内存 H2。XML 和注解共定义 8 条 SQL，
其中 7 条在 `main → UserService → UserMapper` 链路上被调用，`unusedCount` 用于验证未调用定义仍被发现。

`find` 使用 include，`rename` 使用 set，`search` 同时使用 `${column}` 和 `#{value}`，
`optional` 使用 bind/if/where。参数均为标量并显式声明 `@Param`。
自动化分析只编译该项目，不执行 main 或访问数据库。

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
