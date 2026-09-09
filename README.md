# JSQLint

面向数据密集型应用的静态SQL质量管理平台。

## 内置审核规则

规则数据**完全由数据库管理**，代码不内置任何规则。项目初始化时手动执行
`jsqlint-server/src/main/resources/db/init.sql` 完成规则资源初始化，预置 28 条全局规则
（MySQL 方言，`is_builtin=1` 标识），管理员可在"审核规则"页面调整或停用：

| 类别 | 规则 | 级别 |
|---|---|---|
| 删除/变更防护 | DELETE/UPDATE 缺少 WHERE、危险 DDL/DCL（DROP/TRUNCATE/ALTER/GRANT） | critical |
| 查询规范 | SELECT *、无 WHERE 查询、缺少 LIMIT、DISTINCT、嵌套子查询、COUNT(1) 风格 | warning / info |
| 性能 | 条件列函数、LIKE 前置通配符、NOT IN、笛卡尔连接、WHERE 中 OR、JOIN ON 中 OR、ORDER BY RAND、NOLOCK、复杂查询 EXPLAIN 提示 | warning / info / high |
| 安全 | SQL 注入关键词、字符串拼接、MyBatis ${}、ORDER BY 动态拼接、UPDATE 子查询 | critical / high |
| DDL 幂等 | CREATE 无 IF NOT EXISTS、DROP 无 IF EXISTS、加列无默认值 | info / warning |

脚本可重复执行（ON DUPLICATE KEY UPDATE 幂等）。规则判定基于 Drools（`SqlAuditFact` 的 sql 文本与语句类型）。

> **老库升级**：应用启动时自动检测并补充 `is_builtin` 列（`BuiltinRuleInitializer` 仅做表结构迁移，不写规则数据）；
> 规则内容升级时重新执行 init.sql 的规则段即可。

## 项目结构

| 模块 | 说明 |
|---|---|
| `jsqlint-analysis` | SQL 静态分析引擎（SootUp 字节码分析、参数追踪、值推断） |
| `jsqlint-audit` | Drools 规则审核引擎 |
| `jsqlint-common` | 公共工具（Git、Maven、文件） |
| `jsqlint-server` | Spring Boot 服务端（REST API、任务调度） |
| `jsqlint-ui` | Vue 3 前端 |
| `jsqlint-test` | 集成测试 |
| `shared-test-resources` | 测试 fixture 工程 |

## 打包与部署

前后端一键打包与部署。四个脚本都在项目根目录，产物输出到根目录 `dist/`。

```
jsqlint/（项目根）
├── build.sh           # 打包（Linux/macOS/Git-Bash）
├── build.bat          # 打包（Windows）
├── run.sh             # 部署：start/stop/restart/status（Linux/macOS）
├── run.bat            # 部署（Windows）
└── dist/              # 打包产物（build.sh 后生成，已 gitignore）
    ├── jsqlint-server.jar   # 后端 fat jar
    ├── jsqlint-server.pid   # 运行时进程记录
    ├── jsqlint-server.log   # 运行时日志
    └── web/                 # 前端静态资源
```

### 打包

```bash
# Linux / macOS / Git-Bash
./build.sh

# Windows
build.bat
```

- 后端：`mvn -pl jsqlint-server -am -DskipTests package`，产物复制为 `dist/jsqlint-server.jar`
- 前端：`npm install`（无 node_modules 时）+ `npm run build`，产物复制为 `dist/web/`
- 参数（两个平台一致，顺序无关）：
  - `skipUi` — 跳过前端构建（只出 jar）
  - `skipServer` — 跳过后端打包（只出前端）

### 部署

前置条件：JDK 17+（`JAVA_HOME` 或 PATH）、MySQL 可达、已完成打包。

```bash
# Linux / macOS
./run.sh start     # 启动（后台 nohup）
./run.sh status    # 查看运行状态
./run.sh stop      # 优雅停止（30s 超时后强杀）
./run.sh restart   # 重启

# Windows
run.bat start
run.bat status
run.bat stop
run.bat restart
```

#### 运行配置

所有配置支持环境变量覆盖，脚本内含开发默认值：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `JSQLINT_PORT` | `8080` | 服务端口 |
| `JSQLINT_DB_URL` | localhost:3306/jsqlint | MySQL JDBC URL |
| `JSQLINT_DB_USER` / `JSQLINT_DB_PASSWORD` | root / 123456 | 数据库账号 |
| `JSQLINT_JWT_SECRET` | 内置密钥 | **生产必须改** |
| `JAVA_OPTS` | `-Xms512m -Xmx1024m` | JVM 参数 |

示例：

```bash
export JSQLINT_PORT=9090
export JSQLINT_JWT_SECRET='生产专用密钥至少32字节长度...'
./run.sh start
```

### 前端访问方式

前后端分离部署，两种方式任选：

1. **独立静态服务（推荐生产）**：用 Nginx 等托管 `dist/web/`，`/api` 反代到后端端口；
2. **并入后端 jar**：把 `dist/web/` 内容复制到 `jsqlint-server/src/main/resources/static/` 后重新打包，Spring Boot 直接托管。

本地开发联调继续用 `npm run dev`（Vite 代理）。

## 开发

```bash
# 后端全量测试（jsqlint-test 模块默认不参与构建，需激活 with-tests profile）
mvn -Pwith-tests -DskipTests=false clean test

# 前端本地联调（Vite dev server，端口 3000）
cd jsqlint-ui && npm run dev
```

> **构建说明**：`.mvn/maven.config` 默认携带 `-DskipTests=true`（打包不跑测试），
> 且 `jsqlint-test` 模块仅在 `-Pwith-tests` profile 下参与构建。跑测试时两个开关都要带。
