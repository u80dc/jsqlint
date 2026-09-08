# JSQLint

面向数据密集型应用的静态SQL质量管理平台。

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
# 后端全量测试
mvn -pl jsqlint-test -am test

# 前端本地联调（Vite dev server，端口 3000）
cd jsqlint-ui && npm run dev
```
