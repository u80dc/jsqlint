#!/usr/bin/env bash
# ============================================================
# jsqlint 部署脚本（Linux/macOS，位于项目根目录）
# 用法：./run.sh {start|stop|restart|status}
#
# 前置条件：
#   1. 已执行 build.sh，dist/ 下存在 jsqlint-server.jar
#   2. MySQL 可达（连接配置经环境变量覆盖，见下方"运行配置"）
#
# 运行配置（按需修改或经环境变量注入）：
#   JSQLINT_PORT   服务端口，默认 8080
#   JSQLINT_DB_URL MySQL JDBC URL
#   JSQLINT_DB_USER / JSQLINT_DB_PASSWORD  数据库账号
#   JSQLINT_JWT_SECRET  生产环境必须修改默认密钥
#   JAVA_OPTS      额外 JVM 参数（堆大小等）
# ============================================================
set -euo pipefail

APP_NAME="jsqlint-server"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$ROOT_DIR/dist"
JAR_PATH="$DIST_DIR/jsqlint-server.jar"
PID_FILE="$DIST_DIR/$APP_NAME.pid"
LOG_FILE="$DIST_DIR/$APP_NAME.log"

# ---------- 运行配置（环境变量优先，可按需改默认值） ----------
JSQLINT_PORT="${JSQLINT_PORT:-8080}"
JSQLINT_DB_URL="${JSQLINT_DB_URL:-jdbc:mysql://localhost:3306/jsqlint?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}"
JSQLINT_DB_USER="${JSQLINT_DB_USER:-root}"
JSQLINT_DB_PASSWORD="${JSQLINT_DB_PASSWORD:-123456}"
JSQLINT_JWT_SECRET="${JSQLINT_JWT_SECRET:-JSqlintSecretKey2026VeryLongAndSecureKeyForProduction}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"

run_java() {
  if command -v java >/dev/null 2>&1; then
    echo java
  elif [ -x "$JAVA_HOME/bin/java" ]; then
    echo "$JAVA_HOME/bin/java"
  else
    echo ""
  fi
}

check_jar() {
  if [ ! -f "$JAR_PATH" ]; then
    echo "[ERROR] 未找到 $JAR_PATH，请先执行 build.sh 打包" >&2
    exit 1
  fi
}

running_pid() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid="$(cat "$PID_FILE")"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      echo "$pid"
      return
    fi
    rm -f "$PID_FILE"
  fi
  echo ""
}

start() {
  local pid
  pid="$(running_pid)"
  if [ -n "$pid" ]; then
    echo "[WARN] $APP_NAME 已在运行 (pid=$pid)"
    return
  fi
  check_jar
  local java_cmd
  java_cmd="$(run_java)"
  if [ -z "$java_cmd" ]; then
    echo "[ERROR] 未找到 java，请安装 JDK 17+ 或设置 JAVA_HOME" >&2
    exit 1
  fi

  echo "==> 启动 $APP_NAME（端口 $JSQLINT_PORT，日志 $LOG_FILE）"
  nohup "$java_cmd" $JAVA_OPTS \
    -jar "$JAR_PATH" \
    --server.port="$JSQLINT_PORT" \
    --spring.datasource.url="$JSQLINT_DB_URL" \
    --spring.datasource.username="$JSQLINT_DB_USER" \
    --spring.datasource.password="$JSQLINT_DB_PASSWORD" \
    --jwt.secret="$JSQLINT_JWT_SECRET" \
    > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  sleep 2

  pid="$(running_pid)"
  if [ -n "$pid" ]; then
    echo "    已启动 pid=$pid"
  else
    echo "[ERROR] 启动失败，请查看 $LOG_FILE" >&2
    exit 1
  fi
}

stop() {
  local pid
  pid="$(running_pid)"
  if [ -z "$pid" ]; then
    echo "    $APP_NAME 未在运行"
    return
  fi
  echo "==> 停止 $APP_NAME (pid=$pid)"
  kill "$pid"
  # 优雅等待最多 30 秒
  for _ in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "    优雅停止超时，强制结束"
    kill -9 "$pid" || true
  fi
  rm -f "$PID_FILE"
  echo "    已停止"
}

status() {
  local pid
  pid="$(running_pid)"
  if [ -n "$pid" ]; then
    echo "$APP_NAME 运行中 (pid=$pid, 端口 $JSQLINT_PORT)"
  else
    echo "$APP_NAME 未在运行"
  fi
}

case "${1:-}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  status ;;
  *) echo "用法: $0 {start|stop|restart|status}"; exit 1 ;;
esac
