#!/usr/bin/env bash
# Launcher for the Etsy Manager MCP server.
#   ./etsy-manager.sh          run the MCP server over stdio (use this as the MCP "command")
#   ./etsy-manager.sh login    run the one-off Etsy OAuth login
#   ./etsy-manager.sh build    (re)build the jar
#   ./etsy-manager.sh test     run the offline test suite
# The jar is rebuilt automatically when it is missing or older than the sources.
#
# `login` starts an ngrok tunnel for the host in ETSY_REDIRECT_URI when that host is an ngrok domain,
# pointed at ETSY_LOGIN_PORT (default 8080, as in BookFlow's dev.sh). The login command itself listens on
# that port and catches Etsy's redirect, so there is no copy-paste. Do not run BookFlow at the same time:
# it would hold both the port and the ngrok domain. ETSY_NGROK=0 skips the tunnel; ETSY_LOGIN_PORT=0
# disables the listener (the code is then pasted at the prompt).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$ROOT/target/etsy-manager-mcp-0.1.0.jar"
export ETSY_ENV_FILE="${ETSY_ENV_FILE:-$ROOT/.env}"
export ETSY_LOGIN_PORT="${ETSY_LOGIN_PORT:-8080}"

build() {
  # stdout is the MCP channel when serving, so all build output goes to stderr
  (cd "$ROOT" && mvn -q -DskipTests package) >&2
}

ngrok_pid=""
ngrok_log=""

stop_ngrok() {
  if [[ -n "$ngrok_pid" ]] && kill -0 "$ngrok_pid" 2>/dev/null; then
    kill -TERM -- "-$ngrok_pid" 2>/dev/null || kill -TERM "$ngrok_pid" 2>/dev/null || true
    wait "$ngrok_pid" 2>/dev/null || true
  fi
  [[ -n "$ngrok_log" ]] && rm -f "$ngrok_log"
  return 0
}

redirect_uri() {
  [[ -f "$ETSY_ENV_FILE" ]] || return 0
  sed -n 's/^ETSY_REDIRECT_URI=//p' "$ETSY_ENV_FILE" | tail -n1 | tr -d "\"'\r"
}

# Starts ngrok for the redirect URI's host when it is an ngrok domain; otherwise does nothing.
start_ngrok() {
  [[ "${ETSY_NGROK:-1}" == "0" ]] && return 0
  local uri host port
  uri="$(redirect_uri)"
  host="$(sed -E 's|^https://([^/:]+).*|\1|' <<<"$uri")"
  [[ "$uri" == https://* && "$host" =~ \.ngrok(-free)?\.(dev|app|io)$ ]] || return 0

  command -v ngrok >/dev/null 2>&1 || { echo "ngrok is required for $host. Install it or set ETSY_NGROK=0." >&2; exit 1; }
  command -v setsid >/dev/null 2>&1 || { echo "setsid is required to manage the ngrok process." >&2; exit 1; }

  port="$ETSY_LOGIN_PORT"
  [[ "$port" != "0" ]] || port=8080
  ngrok_log="$(mktemp)"
  trap stop_ngrok EXIT INT TERM
  setsid ngrok http "$port" --url="https://$host" --log=stdout >"$ngrok_log" 2>&1 &
  ngrok_pid=$!

  local i
  for i in $(seq 1 30); do
    if ! kill -0 "$ngrok_pid" 2>/dev/null; then
      echo "ngrok exited. Is another ngrok already using $host (e.g. BookFlow's dev.sh)? Stop it and retry." >&2
      tail -n 5 "$ngrok_log" >&2
      exit 1
    fi
    grep -q "started tunnel" "$ngrok_log" && { echo "ngrok tunnel up: https://$host -> localhost:$port" >&2; return 0; }
    sleep 0.5
  done
  echo "ngrok did not report a tunnel within 15s; continuing anyway. Log tail:" >&2
  tail -n 5 "$ngrok_log" >&2
}

needs_build() {
  [[ ! -f "$JAR" ]] || [[ -n "$(find "$ROOT/src" "$ROOT/pom.xml" -newer "$JAR" -print -quit)" ]]
}

case "${1:-serve}" in
  build) build ;;
  test)  (cd "$ROOT" && mvn test) ;;
  login) needs_build && build; start_ngrok; java -jar "$JAR" login ;;
  serve) needs_build && build; exec java -jar "$JAR" ;;
  *)     echo "Usage: $0 [serve|login|build|test]" >&2; exit 2 ;;
esac
