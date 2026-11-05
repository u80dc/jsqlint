# JDBC 用户查询样例

独立的 Maven 项目，使用 JDK 21 和 H2 内存数据库。主程序实际执行用户增删改查，SQL 在 JDBC 调用前由 Java 字符串拼接生成。
无需安装数据库、配置账号或准备表数据；每次运行创建新表，关闭连接后数据销毁。

## 运行

在 jsqlint 仓库根目录使用现有 Maven Wrapper：

```powershell
.\mvnw.cmd -f shared-test-resources/jdbc-user-query/pom.xml compile exec:java
```

Linux/macOS 将 `.\mvnw.cmd` 替换为 `./mvnw`。也可在样例目录使用 `mvn compile exec:java`。
首次运行需要下载 H2 和 Maven 插件。仅编译时执行 `compile` 即可。

主程序依次新增 alice、bob、O'Neil，按 ID 和动态条件查询，修改 bob，删除 alice，并检查返回数据及影响行数。
成功时最后输出：

```text
CRUD demo completed: inserted=3, updated=1, deleted=1, remaining=2
```

## SQL 验证点

SQL 构造和执行集中在 `UserRepository.java`，调用实参集中在 `JdbcUserApplication.java`。

| 方法 | 类型 | 拼接方式 | JDBC SQL 入口 |
|---|---|---|---|
| `insert` | INSERT | 多行 `+`，包含字符串、数值及布尔参数 | `Statement.executeUpdate(sql)` |
| `findById` | SELECT | SQL 前缀与 ID 参数 `+` 拼接 | `Statement.executeQuery(sql)` |
| `findUsers` | SELECT | `if` 分支、`+=`、可选用户名/年龄/启用状态条件 | `Connection.prepareStatement(sql)` |
| `update` | UPDATE | `String.concat` 与 `+` 混用 | `Connection.prepareStatement(sql)` |
| `deleteById` | DELETE | 先拼条件片段，再与前缀拼接 | `Statement.executeUpdate(sql)` |

另外有一个 `createTable` DDL 执行点，因此源码共有 **5 个增删改查 SQL 入口和 1 个建表入口**。
同一个入口在主程序中可能被调用多次，分析输出数量不能直接等同于执行次数。

典型调用对应的实际 SQL：

```sql
INSERT INTO app_users (id, username, email, age, active) VALUES (1, 'alice', 'alice@example.test', 24, true)
SELECT id, username, email, age, active FROM app_users WHERE id = 1
SELECT id, username, email, age, active FROM app_users WHERE 1 = 1 AND username LIKE '%ali%' AND age >= 20 AND active = TRUE ORDER BY id
UPDATE app_users SET email = 'bob.updated@example.test', age = 33, active = true WHERE id = 2
DELETE FROM app_users WHERE id = 1
```

`quote` 辅助方法使用 `String.replace` 将文本中的单引号转义，例如 O'Neil 会生成 `'O''Neil'`。
该辅助调用和动态分支可用于后续验证参数溯源与未知值保留；当前分析器可能保留符号结果或报告 PARTIAL，
不能把样例运行成功理解为静态分析已能完整求值所有 SQL。纯常量的建表片段可能被 javac 折叠，CRUD 语句则包含运行时参数拼接。

## 作为 Analyzer 输入

在分析程序中引入 `jsqlint-core`、`jsqlint-commons` 和 `jsqlint-jdbc`，从仓库根目录传入样例路径：

```java
var analyzer = new Analyzer();
var options = AnalysisOptions.defaults()
        .withClassOutputId("jdbc-user-query")
        .withListener(listener); // 通过 onSql 接收 SQL 模板及渲染结果。
var result = analyzer.analyze(Path.of("shared-test-resources/jdbc-user-query"), options);
```

Analyzer 自动调用 Maven 编译，并分析生成的字节码；静态分析不会运行主程序或执行数据库 SQL。
需要私服或自定义本地仓库时，在创建 Analyzer 前设置 `AnalyzerConfig.withInputOptions(...)`。
样例 POM 不继承 jsqlint，不需要把它加入父 POM 的 modules，也不在样例内放置自动化测试代码。
