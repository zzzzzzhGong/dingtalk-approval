#!/usr/bin/env bash
#
# EWE 审批中心 · 停止脚本
#
# 用法：
#   ./stop.sh            停止由 start.sh 启动的服务
#   ./stop.sh --port 8081  端口被其他进程占用时，按端口兜底清理
#
# 兼容 macOS 自带的 bash 3.2。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$SCRIPT_DIR/.run/app.pid"
PORT="${PORT:-8080}"

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
else
  C_RESET=''; C_BOLD=''; C_GREEN=''; C_YELLOW=''; C_RED=''
fi

ok()   { printf '%s\n' "  ${C_GREEN}✓${C_RESET} $*"; }
warn() { printf '%s\n' "  ${C_YELLOW}!${C_RESET} $*"; }
fail() { printf '%s\n' "  ${C_RED}✗${C_RESET} $*" >&2; }
step() { printf '%s\n' "${C_BOLD}▸ $*${C_RESET}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --port)
      [ $# -ge 2 ] || { fail "--port 需要一个端口号"; exit 1; }
      PORT="$2"; shift ;;
    --port=*) PORT="${1#*=}" ;;
    -h|--help)
      cat <<'USAGE'
EWE 审批中心 · 停止脚本

用法：
  ./stop.sh               停止由 start.sh 启动的服务
  ./stop.sh --port 8081   端口被其他进程占用时，按端口兜底清理

可通过环境变量 PORT 指定端口，等价于 --port。
USAGE
      exit 0 ;;
    *) fail "未知参数：$1"; exit 1 ;;
  esac
  shift
done

# 先 TERM 让 Spring Boot 走完优雅关闭（释放 H2 文件锁），必要时再用 KILL。
stop_process() {
  local pid="$1" i=0
  kill "$pid" 2>/dev/null || true
  while [ $i -lt 20 ]; do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 0.5
    i=$((i + 1))
  done
  warn "进程 $pid 未在 10 秒内退出，强制结束。"
  kill -9 "$pid" 2>/dev/null || true
  sleep 1
}

step "停止 EWE 审批中心"
stopped=0

if [ -f "$PID_FILE" ]; then
  pid="$(cat "$PID_FILE" 2>/dev/null || echo '')"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    stop_process "$pid"
    ok "已停止 start.sh 启动的进程（PID ${pid}）"
    stopped=1
  else
    warn "PID 文件存在但进程已不在（PID ${pid:-空}），清理残留文件。"
  fi
  rm -f "$PID_FILE"
else
  warn "未找到 PID 文件，start.sh 可能没有在运行。"
fi

# 兜底：处理 PID 文件丢失但端口仍被占用的情况
port_pids="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)"
if [ -n "$port_pids" ]; then
  for pid in $port_pids; do
    warn "端口 $PORT 仍被进程 $pid 占用，正在停止…"
    stop_process "$pid"
    ok "已停止进程 $pid"
    stopped=1
  done
fi

if [ "$stopped" -eq 0 ]; then
  ok "没有需要停止的服务，端口 $PORT 空闲。"
fi
