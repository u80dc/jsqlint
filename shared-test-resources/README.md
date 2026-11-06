# 共享分析样例

这里存放供手动验证和自动化测试复用的目标项目。各项目独立构建，可直接将项目路径传给 Analyzer。

| 项目 | 用途 |
|---|---|
| [jdbc-user-query](jdbc-user-query/README.md) | Maven + JDBC + H2 用户增删改查，SQL 由字符串拼接生成 |
| [springmvc-jdbc-user-query](springmvc-jdbc-user-query/README.md) | Spring MVC 分层应用，3 个 HTTP 接口、2 个 JDBC 查询方法，覆盖 SQL 拼接、参数绑定和跨层参数转换 |

这些样例不加入 jsqlint 的 Maven reactor，不依赖 jsqlint 模块。自动化测试代码仍统一放在 `jsqlint-test`。
