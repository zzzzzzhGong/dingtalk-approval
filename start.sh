#!/usr/bin/env bash
#
# EWE 审批中心 · 一键启动脚本
#
# 用法：
#   ./start.sh                  构建并前台启动，自动打开浏览器，Ctrl+C 停止
#   ./start.sh --no-build       跳过构建，直接用已有 jar 启动（改完代码请勿加此参数）
#   ./start.sh --daemon         后台启动后立即返回（用 ./stop.sh 停止）
#   ./start.sh --port 8081      指定端口（默认 8080）
#   ./start.sh --no-open        不自动打开浏览器
#   ./start.sh --help           查看帮助
#
# 钉钉凭证按以下顺序加载，找到即用：
#   1. 已导出的环境变量 DINGTALK_CLIENT_ID / DINGTALK_CLIENT_SECRET
#   2. 项目根目录的 .env 文件（可参考 .env.example）
#   3. 项目根目录的 loginCredential.txt（回归开发期的便捷回退）
#
# 兼容 macOS 自带的 bash 3.2，请勿使用 bash 4+ 专有语法。

set -euo pipefail

# ---------------------------------------------------------------------------
# 基础变量
# ---------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

APP_NAME="dingtalk-approval"
JAR_PATH="$SCRIPT_DIR/target/${APP_NAME}-0.0.1-SNAPSHOT.jar"
RUN_DIR="$SCRIPT_DIR/.run"
LOG_DIR="$SCRIPT_DIR/logs"
PID_FILE="$RUN_DIR/app.pid"
LOG_FILE="$LOG_DIR/app.log"

PORT="${PORT:-8080}"
OPEN_PATH="${OPEN_PATH:-/}"
DO_BUILD=1
DO_OPEN=1
DAEMON=0
READY_TIMEOUT=120

# ---------------------------------------------------------------------------
# 输出样式
# ---------------------------------------------------------------------------

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
else
  C_RESET=''; C_BOLD=''; C_DIM=''; C_BLUE=''; C_GREEN=''; C_YELLOW=''; C_RED=''
fi

step()  { printf '%s\n' "${C_BLUE}${C_BOLD}▸ $*${C_RESET}"; }
ok()    { printf '%s\n' "  ${C_GREEN}✓${C_RESET} $*"; }
warn()  { printf '%s\n' "  ${C_YELLOW}!${C_RESET} $*"; }
fail()  { printf '%s\n' "  ${C_RED}✗${C_RESET} $*" >&2; }
die()   { fail "$*"; exit 1; }

usage() {
  cat <<'USAGE'
EWE 审批中心 · 一键启动脚本

用法：
  ./start.sh                  构建并前台启动，自动打开浏览器，Ctrl+C 停止
  ./start.sh --no-build       跳过构建，直接用已有 jar 启动（改完代码请勿加此参数）
  ./start.sh --daemon         后台启动后立即返回（用 ./stop.sh 停止）
  ./start.sh --port 8081      指定端口（默认 8080）
  ./start.sh --no-open        不自动打开浏览器
  ./start.sh --help           查看帮助

钉钉凭证按以下顺序加载，找到即用：
  1. 已导出的环境变量 DINGTALK_CLIENT_ID / DINGTALK_CLIENT_SECRET
  2. 项目根目录的 .env 文件（可参考 .env.example）
  3. 项目根目录的 loginCredential.txt（明文文件，仅作开发期便捷回退）

其他可用环境变量：
  PORT              服务端口，等价于 --port
  MAVEN_LOCAL_REPO  指定的 Maven 本地仓库目录（~/.m2 不可写时很有用）
  OPEN_PATH         启动后打开的页面路径，默认 /
USAGE
  exit 0
}

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) DO_BUILD=0 ;;
    --daemon)   DAEMON=1 ;;
    --no-open)  DO_OPEN=0 ;;
    --port)
      [ $# -ge 2 ] || die "--port 需要一个端口号"
      PORT="$2"; shift ;;
    --port=*)   PORT="${1#*=}" ;;
    -h|--help)  usage ;;
    *)          die "未知参数：$1（用 --help 查看用法）" ;;
  esac
  shift
done

case "$PORT" in
  ''|*[!0-9]*) die "端口号必须是数字，当前为：$PORT" ;;
esac

# ---------------------------------------------------------------------------
# 1. 检查运行环境：JDK 17+
# ---------------------------------------------------------------------------

# 取 Java 主版本号：Java 8 会输出 1.8.x，其余输出 17 / 21 等。
java_major_version() {
  "$1/bin/java" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/' 2>/dev/null || echo 0
}

resolve_java_home() {
  local candidate major

  # 候选顺序：已有 JAVA_HOME → macOS java_home 工具 → PATH 上的 java
  for candidate in \
    "${JAVA_HOME:-}" \
    "$( /usr/libexec/java_home -v 17 2>/dev/null || true )" \
    "$( /usr/libexec/java_home 2>/dev/null || true )" \
    "$( cd "$(dirname "$(command -v java 2>/dev/null || echo /nonexistent)")/.." 2>/dev/null && pwd )"
  do
    [ -n "$candidate" ] || continue
    [ -x "$candidate/bin/java" ] || continue
    major="$(java_major_version "$candidate")"
    [ "$major" = "1" ] && major=8
    if [ "${major:-0}" -ge 17 ] 2>/dev/null; then
      echo "$candidate"; return 0
    fi
  done
  return 1
}

check_environment() {
  step "检查运行环境"

  local java_home
  if ! java_home="$(resolve_java_home)"; then
    fail "未找到 JDK 17 或更高版本。"
    fail "请安装 JDK 17，或执行：export JAVA_HOME=\$(/usr/libexec/java_home -v 17)"
    exit 1
  fi
  export JAVA_HOME="$java_home"
  ok "JDK $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | sed -E 's/.*version "([^"]*)".*/\1/')  ($JAVA_HOME)"

  command -v curl >/dev/null 2>&1 || die "缺少 curl，无法进行健康检查。"
  ok "工作目录 $SCRIPT_DIR"
}

# ---------------------------------------------------------------------------
# 2. 加载钉钉凭证
# ---------------------------------------------------------------------------

# 从 loginCredential.txt 中解析凭证。
# 该文件同时包含 PowerShell 的 $env:XXX="..." 与 bash 的 export XXX="..." 两种写法，
# 统一用「第一个双引号到第二个双引号」的方式提取，两种格式都能命中。
extract_from_credential_file() {
  local key="$1" file="$2"
  grep -m1 "$key=" "$file" 2>/dev/null \
    | sed -E 's/^[^"]*"([^"]*)".*$/\1/' \
    || true
}

load_credentials() {
  step "加载钉钉凭证"

  local source_desc=""

  if [ -f "$SCRIPT_DIR/.env" ]; then
    # set -a 让 .env 里的变量自动导出给子进程
    set -a
    # shellcheck disable=SC1091
    . "$SCRIPT_DIR/.env"
    set +a
    source_desc=".env 文件"
  fi

  if [ -z "${DINGTALK_CLIENT_ID:-}" ] || [ -z "${DINGTALK_CLIENT_SECRET:-}" ]; then
    if [ -f "$SCRIPT_DIR/loginCredential.txt" ]; then
      DINGTALK_CLIENT_ID="${DINGTALK_CLIENT_ID:-$(extract_from_credential_file DINGTALK_CLIENT_ID "$SCRIPT_DIR/loginCredential.txt")}"
      DINGTALK_CLIENT_SECRET="${DINGTALK_CLIENT_SECRET:-$(extract_from_credential_file DINGTALK_CLIENT_SECRET "$SCRIPT_DIR/loginCredential.txt")}"
      source_desc="loginCredential.txt（明文文件，建议改用 .env）"
    fi
  else
    source_desc="${source_desc:-环境变量}"
  fi

  [ -n "${DINGTALK_CLIENT_ID:-}" ] || die "缺少 DINGTALK_CLIENT_ID，请设置环境变量或创建 .env 文件。"
  [ -n "${DINGTALK_CLIENT_SECRET:-}" ] || die "缺少 DINGTALK_CLIENT_SECRET，请设置环境变量或创建 .env 文件。"

  export DINGTALK_CLIENT_ID DINGTALK_CLIENT_SECRET
  ok "凭证来源：$source_desc"
  ok "Client ID：${DINGTALK_CLIENT_ID:0:6}****（已隐藏）"
}

# ---------------------------------------------------------------------------
# 3. 准备 Maven 本地仓库（~/.m2 不可写时自动降级到项目内目录）
# ---------------------------------------------------------------------------

MAVEN_REPO_ARGS=""

prepare_maven_repo() {
  if [ -n "${MAVEN_LOCAL_REPO:-}" ]; then
    mkdir -p "$MAVEN_LOCAL_REPO"
    MAVEN_REPO_ARGS="-Dmaven.repo.local=$MAVEN_LOCAL_REPO"
    warn "使用自定义 Maven 本地仓库：$MAVEN_LOCAL_REPO"
    return
  fi
  if [ -w "$HOME/.m2/repository" ] || { [ ! -e "$HOME/.m2/repository" ] && [ -w "$HOME/.m2" ]; }; then
    return
  fi
  # 受限环境（如只读 HOME）下退回到项目内目录，避免构建直接失败
  MAVEN_LOCAL_REPO="$SCRIPT_DIR/.m2repo"
  mkdir -p "$MAVEN_LOCAL_REPO"
  MAVEN_REPO_ARGS="-Dmaven.repo.local=$MAVEN_LOCAL_REPO"
  warn "~/.m2 不可写，已改用 ${MAVEN_LOCAL_REPO}（首次构建会重新下载依赖）"
}

# ---------------------------------------------------------------------------
# 4. 端口检查
# ---------------------------------------------------------------------------

ensure_port_free() {
  step "检查端口 $PORT"

  local owner
  owner="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -n 1 || true)"
  [ -z "$owner" ] && { ok "端口可用"; return 0; }

  # 如果占用者正是本项目上一次启动的进程，先停掉再继续
  if [ -f "$PID_FILE" ] && [ "$(cat "$PID_FILE" 2>/dev/null || echo '')" = "$owner" ]; then
    warn "端口被本项目上一次启动的进程占用（PID ${owner}），正在停止…"
    stop_process "$owner"
    return 0
  fi

  fail "端口 $PORT 已被进程 $owner 占用。"
  fail "可以先执行 ./stop.sh，或用 ./start.sh --port 8081 换一个端口。"
  exit 1
}

# ---------------------------------------------------------------------------
# 5. 构建与产物校验
# ---------------------------------------------------------------------------

# 打包后必须存在的资源文件。缺了 application.properties，应用启动时会报
# "Could not resolve placeholder 'dingtalk.process-code'"，且堆栈完全看不出真正原因。
REQUIRED_JAR_ENTRIES="BOOT-INF/classes/application.properties BOOT-INF/classes/schema.sql"

# 输出 jar 中缺失的必需条目；全部存在时输出为空字符串。
missing_jar_entries() {
  local listing="" entry missing=""
  if [ -x "$JAVA_HOME/bin/jar" ]; then
    listing="$("$JAVA_HOME/bin/jar" tf "$JAR_PATH" 2>/dev/null || true)"
  elif command -v unzip >/dev/null 2>&1; then
    listing="$(unzip -Z1 "$JAR_PATH" 2>/dev/null || true)"
  fi
  if [ -z "$listing" ]; then
    echo "无法读取 ${JAR_PATH}"
    return 0
  fi
  for entry in $REQUIRED_JAR_ENTRIES; do
    case "$listing" in
      *"$entry"*) ;;
      *) missing="${missing} ${entry}" ;;
    esac
  done
  echo "$missing"
}

build_app() {
  local missing=""

  if [ "$DO_BUILD" -eq 0 ]; then
    step "跳过构建（--no-build）"
    if [ -f "$JAR_PATH" ]; then
      missing="$(missing_jar_entries)"
      if [ -z "$missing" ]; then
        ok "使用已有 jar"
        return 0
      fi
      warn "已有 jar 缺少资源：${missing}"
      warn "自动改为执行一次完整构建"
    else
      warn "找不到 ${JAR_PATH}，自动改为执行一次完整构建"
    fi
  fi

  step "构建应用（clean package）"
  prepare_maven_repo

  # 这里坚持 clean：本项目由 VS Code / IDE 的 Java 插件共用同一个 target/ 目录，
  # 增量构建可能留下「有 class 但缺 resources」的产物。实测 clean 只比增量多约 4 秒，
  # 用这 4 秒换掉一整类难以排查的启动失败是值得的。
  # MAVEN_REPO_ARGS 可能为空；本项目路径不含空格，因此按单字符串拼接是安全的。
  # shellcheck disable=SC2086
  if ! "$SCRIPT_DIR/mvnw" -q -DskipTests $MAVEN_REPO_ARGS clean package; then
    fail "构建失败，请检查上方 Maven 输出。"
    fail "常见原因：网络不可达 Maven 中央仓库、~/.m2 不可写、JDK 版本低于 17。"
    exit 1
  fi
  [ -f "$JAR_PATH" ] || die "构建结束但未生成 ${JAR_PATH}。"

  missing="$(missing_jar_entries)"
  if [ -n "$missing" ]; then
    fail "打包结果缺少运行必需的资源：${missing}"
    fail "target/ 可能被其他构建进程（例如 VS Code 的 Java 插件）污染。请执行后重试："
    fail "    rm -rf target && ./start.sh"
    exit 1
  fi
  ok "构建完成并通过产物校验：$(basename "$JAR_PATH")"
}

# ---------------------------------------------------------------------------
# 进程管理
# ---------------------------------------------------------------------------

# 先 TERM 后用 KILL 兜底，确保 Java 进程和它持有的 H2 文件锁被正确释放。
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
}

cleanup() {
  # 加锁避免 INT 与 TERM 同时到达时重复执行
  [ -n "${CLEANUP_DONE:-}" ] && return 0
  CLEANUP_DONE=1

  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || echo '')"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    printf '\n'
    step "正在停止服务（PID ${pid}）…"
    stop_process "$pid"
    ok "服务已停止"
  fi
  rm -f "$PID_FILE"
}

# ---------------------------------------------------------------------------
# 6. 启动并等待就绪
# ---------------------------------------------------------------------------

start_app() {
  step "启动服务（端口 ${PORT}）"
  mkdir -p "$RUN_DIR" "$LOG_DIR"
  : > "$LOG_FILE"

  nohup "$JAVA_HOME/bin/java" -jar "$JAR_PATH" \
    --server.port="$PORT" \
    --spring.output.ansi.enabled=always \
    > "$LOG_FILE" 2>&1 &

  local app_pid=$!
  echo "$app_pid" > "$PID_FILE"
  ok "进程已启动（PID ${app_pid}），日志：$LOG_FILE"

  wait_until_ready "$app_pid"
}

# 启动失败时给出可执行的结论，而不是只丢一大段 Spring 堆栈。
# 判定顺序很重要：数据库故障同样会抛 ConnectException，必须先于通用网络检查，
# 否则会把「本地 H2 连不上」误报成「钉钉网络不通」。
diagnose_startup_failure() {
  [ -f "$LOG_FILE" ] || return 0

  # 1. 打包产物缺资源 —— 本项目实际踩过的坑，症状极具迷惑性
  if grep -q "dingtalk.process-code" "$LOG_FILE" 2>/dev/null; then
    fail "原因：jar 里没有打包进 application.properties，配置完全读不到。"
    fail "处理：rm -rf target && ./start.sh"
    return 0
  fi
  # 2. 其他配置占位符缺失
  if grep -q "Could not resolve placeholder" "$LOG_FILE" 2>/dev/null; then
    fail "原因：有配置占位符无法解析，通常是对应的环境变量没有设置。"
    fail "处理：检查 .env 或环境变量，参考 .env.example。"
    return 0
  fi
  # 3. 端口占用
  if grep -qE "Port [0-9]+ was already in use|Address already in use|Web server failed to start" "$LOG_FILE" 2>/dev/null; then
    fail "原因：端口 ${PORT} 已被占用。"
    fail "处理：./stop.sh 停掉旧实例，或 ./start.sh --port 8081 换端口。"
    return 0
  fi
  # 4. 数据库问题（必须排在通用网络检查之前）
  if grep -qE "JdbcSQLNonTransientConnectionException|HikariPool|Failed to execute database script|dataSourceScriptDatabaseInitializer|Unable to obtain connection from database|Database may be already in use" "$LOG_FILE" 2>/dev/null; then
    fail "原因：本地 H2 数据库初始化或连接失败。"
    fail "处理：确认没有另一个实例占用数据库（./stop.sh），必要时删除 data/ 下的 .lock.db 后重试。"
    return 0
  fi
  # 5. 钉钉接口网络问题
  if grep -qE "UnknownHostException|oapi\.dingtalk\.com|api\.dingtalk\.com|login\.dingtalk\.com" "$LOG_FILE" 2>/dev/null; then
    fail "原因：无法访问钉钉开放平台接口，请检查网络与代理。"
    return 0
  fi
}

wait_until_ready() {
  local app_pid="$1" elapsed=0
  printf '  等待服务就绪'
  while [ "$elapsed" -lt "$READY_TIMEOUT" ]; do
    if curl -fsS -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/hello" 2>/dev/null; then
      printf '\r\033[K'
      ok "服务已就绪（用时 ${elapsed}s）"
      return 0
    fi
    if ! kill -0 "$app_pid" 2>/dev/null; then
      printf '\r\033[K'
      fail "进程已退出，启动失败。"
      diagnose_startup_failure
      printf '\n  %s\n' "${C_DIM}完整日志：${LOG_FILE}${C_RESET}"
      printf '  %s\n' "${C_DIM}日志末尾 15 行：${C_RESET}"
      tail -n 15 "$LOG_FILE" | sed 's/^/    /' >&2
      rm -f "$PID_FILE"
      exit 1
    fi
    printf '.'
    sleep 1
    elapsed=$((elapsed + 1))
  done
  printf '\r\033[K'
  fail "等待 ${READY_TIMEOUT}s 后服务仍未就绪。"
  diagnose_startup_failure
  printf '  %s\n' "${C_DIM}日志末尾 15 行：${C_RESET}"
  tail -n 15 "$LOG_FILE" | sed 's/^/    /' >&2
  exit 1
}

open_browser() {
  [ "$DO_OPEN" -eq 1 ] || return 0
  local url="http://localhost:$PORT$OPEN_PATH"
  if command -v open >/dev/null 2>&1; then
    open "$url" 2>/dev/null || true
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$url" >/dev/null 2>&1 || true
  fi
}

print_urls() {
  printf '\n'
  step "可以开始测试了"
  # printf 的 %-Ns 按字节补位，中文标签会错位，这里按显示宽度（中文占 2 列）手工补空格到 12 列。
  printf '  %s        %s\n' "首页"        "http://localhost:${PORT}/"
  printf '  %s    %s\n'     "发起审批"    "http://localhost:${PORT}/approval-test.html"
  printf '  %s    %s\n'     "我的待办"    "http://localhost:${PORT}/approval-todo.html"
  printf '  %s    %s\n'     "审批概览"    "http://localhost:${PORT}/approval-status.html"
  printf '  %s %s\n'        "Stream 状态" "http://localhost:${PORT}/api/dingtalk/stream/status"
  printf '\n'
  printf '  %s\n' "${C_DIM}尚未登录时页面会引导你扫码；登录回调地址必须与访问域名一致（默认 localhost:${PORT}）。${C_RESET}"
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

printf '%s\n' "${C_BOLD}════════════════════════════════════════════${C_RESET}"
printf '%s\n' "${C_BOLD}  EWE 审批中心 · 一键启动${C_RESET}"
printf '%s\n' "${C_BOLD}════════════════════════════════════════════${C_RESET}"

check_environment
load_credentials
ensure_port_free
build_app

# 尽早安装信号处理：即使在「等待服务就绪」阶段被 Ctrl+C 打断，也必须把 Java 子进程一起带走，
# 否则会留下一个占着端口的孤儿进程。后台模式由 ./stop.sh 负责停止，不在此处安装。
if [ "$DAEMON" -eq 0 ]; then
  trap 'cleanup; exit 0' INT TERM HUP
fi

start_app

if [ "$DAEMON" -eq 1 ]; then
  open_browser
  print_urls
  printf '  %s\n\n' "${C_DIM}已后台运行。停止服务请执行：./stop.sh${C_RESET}"
  exit 0
fi

print_urls
open_browser

printf '\n'
printf '%s\n' "${C_BOLD}──────────────── 实时日志（Ctrl+C 停止服务）────────────────${C_RESET}"
# 前台跟随日志。INT/TERM 来自 Ctrl+C 或 kill，HUP 来自关闭终端窗口，三者都已在上方接管。
tail -n +1 -f "$LOG_FILE" &
TAIL_PID=$!
wait "$TAIL_PID" 2>/dev/null || true
cleanup
