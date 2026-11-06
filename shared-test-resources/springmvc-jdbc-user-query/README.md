# Spring MVC + JDBC 用户查询样例

独立 Maven 项目，使用 JDK 21、Spring Boot 3.5、Spring MVC 和原生 JDBC。Spring Boot 配置嵌入式 HTTP 服务及数据源，H2 内存数据库在启动时自动建表并初始化数据，无需安装数据库。

## 分层结构

```text
org.u80dc.jsqlint.samples.springmvcjdbc
├── SpringMvcJdbcApplication       应用启动入口
├── controller
│   ├── UserController            HTTP 参数接收与服务调用
│   └── ApiExceptionHandler       将业务异常转换为 HTTP 400/404
├── service
│   ├── UserService               参数校验、搜索词转换及固定业务条件
│   └── UserNotFoundException      用户不存在异常
├── repository
│   └── JdbcUserRepository        两个 JDBC 查询方法及结果映射
└── model
    └── User                      用户数据
```

依赖通过构造器注入，调用方向为 `Controller → Service → Repository`。Repository 直接使用 `Statement`、`PreparedStatement`，便于验证 JDBC 调用点识别。

## 运行和请求

在 jsqlint 仓库根目录运行：

```powershell
.\mvnw.cmd -f shared-test-resources/springmvc-jdbc-user-query/pom.xml spring-boot:run
```

也可以先打包，再启动可执行 JAR：

```powershell
.\mvnw.cmd -f shared-test-resources/springmvc-jdbc-user-query/pom.xml package
java -jar shared-test-resources/springmvc-jdbc-user-query/target/springmvc-jdbc-user-query-1.0-SNAPSHOT.jar
```

Linux/macOS 将 `.\mvnw.cmd` 替换为 `./mvnw`；已安装 Maven 时可使用 `mvn`。首次构建需要下载依赖。默认监听 `127.0.0.1:8080`，运行 JAR 时可追加 `--server.port=8081` 修改端口。

可直接执行 [requests.http](requests.http) 中的请求：

| 接口 | 用途 | 默认结果 |
|---|---|---|
| `GET /api/users/{id}` | 按 ID 查询 | `/1` 返回 alice；不存在返回 404 |
| `GET /api/users` | 按搜索词、年龄、状态查询，支持排序及条数限制 | 返回全部 5 个用户，按 ID 排序 |
| `GET /api/users/active-adults` | 查询启用的成年用户，可传入 `keyword` | 返回 alice、O'Neil、team_100%，按年龄排序 |

通用查询支持以下请求参数：

| 参数 | 默认值 | 处理方式 |
|---|---|---|
| `keyword` | 空字符串 | 去除首尾空白，转义 LIKE 的 `%`、`_` 和转义字符 `!`，再添加前后 `%` |
| `minimumAge` | `0` | 只接受 0～120，否则返回 400 |
| `activeOnly` | `false` | 为 true 时添加启用状态条件及第 3 个绑定参数 |
| `sort` | `id` | 只接受 `id` 或 `age`，由内部常量生成排序片段，否则返回 400 |
| `limit` | `20` | 小于 1 调整为 1，大于 100 调整为 100，之后参与 SQL 拼接 |

ID 必须大于 0，否则返回 400。初始数据包含普通用户名、单引号和 LIKE 通配符，便于检查字符串处理：

| ID | 用户名 | 年龄 | 启用 |
|---|---|---|---|
| 1 | alice | 24 | true |
| 2 | bob | 31 | false |
| 3 | O'Neil | 36 | true |
| 4 | carol | 17 | true |
| 5 | team_100% | 42 | true |

## SQL 与传播链验证点

Java 业务代码只有两个 JDBC 查询方法：

| Repository 方法 | SQL 构造 | 执行方式 |
|---|---|---|
| `findById(long id)` | SQL 前缀与数值 ID 通过 `+` 拼接 | `Statement.executeQuery(sql)` |
| `findUsers(...)` | 固定条件、可选状态条件、排序片段和数值 LIMIT 拼接；同时绑定字符串、整数和布尔值 | `Connection.prepareStatement(sql)`，随后 `PreparedStatement.executeQuery()` |

按 ID 查询的示例：

```sql
SELECT id, username, email, age, active FROM app_users WHERE id = 1
```

通用查询在启用状态条件时生成的示例模板：

```sql
SELECT id, username, email, age, active FROM app_users
WHERE username LIKE ? ESCAPE '!' AND age >= ? AND active = ?
ORDER BY age ASC, id ASC LIMIT 2
```

绑定位置固定为 `1 = namePattern`、`2 = minimumAge`，仅当 `activeOnly=true` 时存在 `3 = activeOnly`。SQL 条件和第 3 个绑定参数受同一变量控制，可用于验证联合路径约束，避免把不同分支的模板与绑定列表组合在一起。排序分支又形成两种 SQL 结构。

`UserService.searchUsers` 转换搜索词、排序和 LIMIT，便于验证跨层参数传播与值推断。`findActiveAdults` 再调用同一个搜索方法，传入 `18`、`true`、`"age"`、`10` 等固定参数，形成另一条包含字面量和 HTTP 外部输入的调用链。

例如 `keyword=O'Neil` 通过参数绑定匹配 ID 3；`keyword=_100%` 经 LIKE 转义后匹配 ID 5。排序文本来自内部固定片段，直接拼接的 ID 和 LIMIT 均为数值类型。

建表和初始化数据位于 `src/main/resources/schema.sql`、`data.sql`，由 Spring 启动流程执行。因此“两个查询方法”指 Java 业务查询方法，初始化脚本另计。编译保留参数名、局部变量和行号信息。

## 作为 Analyzer 输入

在分析程序中引入 `jsqlint-core`、`jsqlint-commons` 和 `jsqlint-jdbc`，从仓库根目录传入项目路径：

```java
var analyzer = new Analyzer();
var result = analyzer.analyze(Path.of("shared-test-resources/springmvc-jdbc-user-query"));
```

Analyzer 自动编译项目并分析字节码。HTTP 接口由 Spring 框架调用，`main` 不直接调用 Controller；框架入口、构造器注入及请求参数均属于该样例的验证场景。样例能够正常运行不代表当前分析器已经完整建模这些框架行为或能够求值所有字符串转换。

该项目不加入根 Maven reactor，不依赖 jsqlint 模块。后续针对它的自动化分析测试统一放在 `jsqlint-test`。

在仓库根目录执行 Analyzer 集成测试并打印所有 raw SQL：

```powershell
.\mvnw.cmd -pl jsqlint-test -am test "-Dtest=SpringMvcJdbcUserQueryAnalysisTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.test.skip=false" "-DskipTests=false"
```

测试将完整候选、调用位置、调用链和分析诊断写入 `jsqlint-test/target/springmvc-jdbc-user-query-sql-report.txt`，并将按文本去重的 SQL 清单写入 `jsqlint-test/target/springmvc-jdbc-user-query-raw.sql`。`${name}` 表示拼接变量，`?` 表示 JDBC 绑定参数；分析不启动 HTTP 服务或执行数据库查询。
