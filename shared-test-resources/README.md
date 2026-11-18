# 共享分析样例

这里存放供手动验证和自动化测试复用的目标项目。各项目独立构建，可直接将项目路径传给 Analyzer。

| 项目 | 用途 |
|---|---|
| [jdbc-user-query](jdbc-user-query/README.md) | Maven + JDBC + H2 用户增删改查，SQL 由字符串拼接生成 |
| [springmvc-jdbc-user-query](springmvc-jdbc-user-query/README.md) | Spring MVC 分层应用，3 个 HTTP 接口、2 个 JDBC 查询方法，覆盖 SQL 拼接、参数绑定和跨层参数转换 |
| [mybatis-user-query](mybatis-user-query/README.md) | Maven + MyBatis + H2，验证 XML/注解定义、Mapper 标量实参、include、参数化 SQL 和未调用定义 |
| [jdbc-usage-matrix](jdbc-usage-matrix/README.md) | JDBC 批次与绑定状态、CallableStatement、setNull/setObject、标准重载；独立执行并核验 H2 数据 |
| [jdbc-standard-api](jdbc-standard-api/README.md) | 补全缺失 SQL 标准重载、基础数值/命名绑定、别名、事务、生成键及批次边界；H2 与接口 oracle 分别核验 |
| [mybatis-usage-matrix](mybatis-usage-matrix/README.md) | XML CRUD、choose/trim、参数命名、跨文件 include、databaseId；H2 与通用配置分别自检 |

这些样例不加入 jsqlint 的 Maven reactor，不依赖 jsqlint 模块。自动化测试代码仍统一放在 `jsqlint-test`。

样例分批扩充，以实际调用、分支输入和独立输出断言作为完成标准，见
[补齐进度](../jsqlint-test/SAMPLE_EXPANSION_PROGRESS.md)。

当前四个 JDBC 样例的 Analyzer 调用点发现、SQL/绑定正确率和未恢复边界见
[JDBC SQL 提取验证](../jsqlint-test/JDBC_CURRENT_SQL_VALIDATION.md)。
