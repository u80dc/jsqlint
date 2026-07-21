# jsqlint-ui

J-SQLint 前端模块，基于 Vue 3、Vite、TypeScript 和 Element Plus 构建。

## 技术栈

- 前端框架：Vue 3
- 开发语言：TypeScript
- 构建工具：Vite 6
- Vue 编译插件：@vitejs/plugin-vue
- 路由：vue-router 4
- 状态管理：Pinia
- UI 组件库：Element Plus
- 图标库：@element-plus/icons-vue
- HTTP 请求：axios
- 图表：ECharts
- 国际化：vue-i18n
- 样式预处理：Sass
- 类型检查：vue-tsc

## 环境要求

- Node.js ≥ 18（推荐 20 LTS 及以上）
- 包管理器：npm（随 Node 自带）；也可使用 pnpm / yarn

> 首次运行前请先在本目录执行 `npm install` 安装依赖。

## 后端地址配置

前端默认通过 `src/config/app.ts` 读取后端地址，优先取环境变量 `VITE_API_BASE_URL`，缺省回退到 `http://localhost:8080`。

- **本地开发联调**：启动后端 `jsqlint-server`（默认端口 8080）即可，无需额外配置。
- **需要更换地址**：在 `jsqlint-ui` 根目录新建环境文件并指定：

  ```bash
  # .env.development（开发环境）
  VITE_API_BASE_URL=http://localhost:8080
  ```

  ```bash
  # .env.production（生产环境）
  VITE_API_BASE_URL=https://your-server-host
  ```

  Vite 会按当前模式自动加载对应文件（`vite dev` 读 `.env.development`，`vite build` 读 `.env.production`）。

## 启动（开发模式）

```bash
npm install      # 仅首次需要
npm run dev
```

启动后访问开发服务器：**http://localhost:3000**（端口与 `vite.config.ts` 中 `server.port` 一致，`host: true` 允许局域网访问）。

默认登录账号（对应后端 `init2.sql` 种子数据）：

- 管理员：`admin` / `admin123`
- 测试用户：`test` / `test`

## 打包（生产构建）

```bash
npm run build
```

构建流程为 `vue-tsc && vite build`：

1. `vue-tsc` 先做全量 TypeScript 类型检查；
2. `vite build` 产出生产产物到 `dist/` 目录。

构建产物默认位于 `dist/`，可直接由 Nginx 等 Web 服务器托管（静态资源 + 单页应用，需配置 history 模式回退到 `index.html`）。

### 本地预览构建产物

```bash
npm run preview
```

以本地静态服务预览 `dist/` 产物，用于发布前验证。

## 部署提示

- `dist/` 部署到 Nginx 时，需对前端路由（vue-router history 模式）配置 `try_files` 回退：

  ```nginx
  location / {
    root   /path/to/dist;
    index  index.html;
    try_files $uri $uri/ /index.html;
  }
  ```

- 前端请求统一以 `VITE_API_BASE_URL` 为前缀；若前后端不同域，可在 Nginx 配置反向代理（如 `location /api/ { proxy_pass http://backend:8080; }`），并将 `VITE_API_BASE_URL` 设为同源相对路径。

## 常用命令一览

| 命令 | 说明 |
|------|------|
| `npm install` | 安装依赖（首次 / 依赖变更后） |
| `npm run dev` | 启动开发服务器（端口 3000，热更新） |
| `npm run build` | 类型检查 + 生产构建，产物输出到 `dist/` |
| `npm run preview` | 本地预览构建产物 |

## 目录结构

- `src/api`：后端接口封装（axios 请求层，统一在 `request.ts` 注入 JWT、处理 401/403）
- `src/views`：页面级 Vue 组件
- `src/stores`：Pinia 状态管理
- `src/router`：前端路由配置
- `src/config`：应用级配置（如后端地址）
- `src/locales`：国际化资源
- `src/utils`：工具函数
- `src/assets`：静态资源和样式资源
