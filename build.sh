#!/usr/bin/env bash
# ============================================================
# jsqlint 打包脚本（Linux/macOS/Git-Bash，位于项目根目录）
# 产物：dist/ 下生成后端 fat jar 与前端静态资源
# 用法：./build.sh [skipUi] [skipServer]
#   skipUi     — 跳过前端构建
#   skipServer — 跳过后端打包
# ============================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$ROOT_DIR/dist"
SERVER_MODULE="jsqlint-server"
JAR_NAME="jsqlint-server.jar"

cd "$ROOT_DIR"
SKIP_UI="${1:-}"
SKIP_SERVER="${2:-}"

mkdir -p "$DIST_DIR"

# ---------- 后端打包 ----------
if [ "$SKIP_SERVER" != "skipServer" ]; then
  echo "==> [1/2] 后端打包（跳过测试）..."
  if [ -x ./mvnw ]; then
    ./mvnw -q -pl "$SERVER_MODULE" -am -DskipTests package
  else
    mvn -q -pl "$SERVER_MODULE" -am -DskipTests package
  fi
  # fat jar 带 exec 分类器（主构件保持普通 jar 供 jsqlint-test 依赖）
  cp "$SERVER_MODULE/target/jsqlint-server-0.0.1-SNAPSHOT-exec.jar" "$DIST_DIR/$JAR_NAME"
  echo "    后端产物: $DIST_DIR/$JAR_NAME"
fi

# ---------- 前端打包 ----------
if [ "$SKIP_UI" != "skipUi" ]; then
  echo "==> [2/2] 前端打包..."
  cd jsqlint-ui
  if [ ! -d node_modules ]; then
    npm install --no-fund --no-audit
  fi
  npm run build
  cd "$ROOT_DIR"
  rm -rf "$DIST_DIR/web"
  cp -r jsqlint-ui/dist "$DIST_DIR/web"
  echo "    前端产物: $DIST_DIR/web/"
fi

echo "==> 打包完成，产物目录: $DIST_DIR"
ls -lh "$DIST_DIR" || true
