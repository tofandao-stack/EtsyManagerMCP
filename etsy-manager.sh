#!/usr/bin/env bash
# Launcher for the Etsy Manager MCP server.
#   ./etsy-manager.sh          run the MCP server over stdio (use this as the MCP "command")
#   ./etsy-manager.sh login    run the one-off Etsy OAuth login
#   ./etsy-manager.sh build    (re)build the jar
#   ./etsy-manager.sh test     run the offline test suite
# The jar is rebuilt automatically when it is missing or older than the sources.
# ETSY_ENV_FILE defaults to <repo>/.env so it works from any working directory.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$ROOT/target/etsy-manager-mcp-0.1.0.jar"
export ETSY_ENV_FILE="${ETSY_ENV_FILE:-$ROOT/.env}"

build() {
  # stdout is the MCP channel when serving, so all build output goes to stderr
  (cd "$ROOT" && mvn -q -DskipTests package) >&2
}

needs_build() {
  [[ ! -f "$JAR" ]] || [[ -n "$(find "$ROOT/src" "$ROOT/pom.xml" -newer "$JAR" -print -quit)" ]]
}

case "${1:-serve}" in
  build) build ;;
  test)  (cd "$ROOT" && mvn test) ;;
  login) needs_build && build; exec java -jar "$JAR" login ;;
  serve) needs_build && build; exec java -jar "$JAR" ;;
  *)     echo "Usage: $0 [serve|login|build|test]" >&2; exit 2 ;;
esac
