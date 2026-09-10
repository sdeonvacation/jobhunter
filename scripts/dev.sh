#!/bin/zsh
# JobHunter dev stack — uses launchctl submit for proper process management
# Usage: ./scripts/dev.sh {dev|stop|status|restart}
set -e
source ~/.zshenv 2>/dev/null || true

PROJECT_ROOT="${0:A:h:h}"
LOG_DIR=/tmp/jobhunter
DB_PORT=5435
if [[ -d "$HOME/.gradle/jdks" ]]; then
  JAVA_HOME="${JAVA_HOME:-$(ls -d $HOME/.gradle/jdks/*/jdk-21*/Contents/Home 2>/dev/null | head -1)}"
fi
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"
API_JAR="$PROJECT_ROOT/api/build/libs/jobhunter-api-0.0.1-SNAPSHOT.jar"
DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"

mkdir -p "$LOG_DIR"

start_db() {
  if nc -z localhost $DB_PORT 2>/dev/null; then
    echo "DB already running"
  else
    echo "Starting PostgreSQL..."
    DOCKER_HOST=$DOCKER_HOST docker compose -f "$PROJECT_ROOT/docker-compose.yml" up -d db 2>&1 | grep -v "volume.*already exists" || true
    printf "Waiting for DB..."
    for i in {1..30}; do
      nc -z localhost $DB_PORT 2>/dev/null && break
      printf "."; sleep 1
    done
    nc -z localhost $DB_PORT 2>/dev/null && echo " ready" || { echo " FAILED"; exit 1; }
  fi
}

start_mcp() {
  if nc -z localhost 8000 2>/dev/null; then
    if curl -sS --max-time 2 http://localhost:8000/mcp >/dev/null 2>&1; then
      echo "MCP already running"
    else
      echo "ERROR: port 8000 is occupied by a non-responsive process; not stopping it"
    fi
    return
  fi
  local uvx_path=$(which uvx 2>/dev/null || echo "$HOME/.local/bin/uvx")
  if [[ ! -x "$uvx_path" ]]; then
    echo "WARN: uvx not found, skipping MCP"
    return
  fi
  echo "Starting LinkedIn MCP..."
  launchctl bootout "gui/$(id -u)/dev.jobhunter.mcp" 2>/dev/null || true
  launchctl submit -l dev.jobhunter.mcp \
    -o "$LOG_DIR/mcp.log" -e "$LOG_DIR/mcp.log" \
    -- "$uvx_path" mcp-server-linkedin@latest --user-data-dir "$HOME/.linkedin-mcp/profile" --transport streamable-http --host 0.0.0.0 --port 8000 --log-level INFO
  for i in {1..10}; do nc -z localhost 8000 2>/dev/null && break; sleep 1; done
  nc -z localhost 8000 2>/dev/null && echo "  MCP ready" || echo "  WARN: MCP not responding"
}

start_api() {
  # Check if already running (read port from file if available)
  local existing_port=""
  if [[ -f /tmp/jobhunter-api.port ]]; then
    existing_port=$(cat /tmp/jobhunter-api.port)
  fi
  if [[ -n "$existing_port" ]] && curl -sf "http://localhost:$existing_port/api/admin/health" >/dev/null 2>&1; then
    echo "API already running on port $existing_port"
    return
  fi

  if [[ ! -f "$API_JAR" ]]; then
    echo "API JAR not found, building..."
    JAVA_HOME=$JAVA_HOME "$PROJECT_ROOT/api/gradlew" -p "$PROJECT_ROOT/api" bootJar -x test
  fi

  echo "Starting API..."
  launchctl bootout "gui/$(id -u)/dev.jobhunter.api" 2>/dev/null || true
  for i in {1..20}; do
    launchctl print "gui/$(id -u)/dev.jobhunter.api" >/dev/null 2>&1 || break
    sleep 0.1
  done
  : > "$LOG_DIR/api.log"
  launchctl submit -l dev.jobhunter.api \
    -o "$LOG_DIR/api.log" -e "$LOG_DIR/api.log" \
    -- "$PROJECT_ROOT/scripts/start-api.sh"

  printf "Waiting for API..."
  local api_port=""
  for i in {1..90}; do
    # Extract port from log if not yet found
    if [[ -z "$api_port" ]]; then
      api_port=$(grep -o 'Tomcat started on port [0-9]*' "$LOG_DIR/api.log" 2>/dev/null | tail -n1 | grep -o '[0-9]*' || true)
    fi
    if [[ -n "$api_port" ]]; then
      break
    fi
    printf "."; sleep 2
  done

  if [[ -n "$api_port" ]]; then
    echo " ready"
    echo "$api_port" > /tmp/jobhunter-api.port
    echo "VITE_API_URL=http://localhost:$api_port" > "$PROJECT_ROOT/dashboard/.env"
  else
    echo " FAILED (check: tail $LOG_DIR/api.log)"
    exit 1
  fi
}

start_dashboard() {
  echo "Starting Dashboard..."
  launchctl bootout "gui/$(id -u)/dev.jobhunter.dashboard" 2>/dev/null || true
  launchctl submit -l dev.jobhunter.dashboard \
    -o "$LOG_DIR/dashboard.log" -e "$LOG_DIR/dashboard.log" \
    -- "$PROJECT_ROOT/scripts/start-dashboard.sh"
  sleep 3
  curl -sf http://localhost:3000 >/dev/null 2>&1 && echo "  Dashboard ready" || echo "  WARN: Dashboard not responding yet"
}

stop_all() {
  echo "Stopping JobHunter..."
  launchctl bootout "gui/$(id -u)/dev.jobhunter.dashboard" 2>/dev/null && echo "  Dashboard stopped" || true
  launchctl bootout "gui/$(id -u)/dev.jobhunter.api" 2>/dev/null && echo "  API stopped" || true
  launchctl bootout "gui/$(id -u)/dev.jobhunter.mcp" 2>/dev/null && echo "  MCP stopped" || true
  echo "Done (DB left running)"
}

show_status() {
  echo "=== JobHunter Status ==="
  printf "  %-12s " "DB:"; nc -z localhost $DB_PORT 2>/dev/null && echo "✓ running" || echo "✗ stopped"
  printf "  %-12s " "API:"
  if [[ -f /tmp/jobhunter-api.port ]]; then
    local port=$(cat /tmp/jobhunter-api.port)
    curl -sf "http://localhost:$port/api/admin/health" >/dev/null 2>&1 && echo "✓ running (port $port)" || echo "✗ stopped"
  else
    echo "✗ stopped"
  fi
  printf "  %-12s " "Dashboard:"; curl -sf http://localhost:3000 >/dev/null 2>&1 && echo "✓ running" || echo "✗ stopped"
  printf "  %-12s " "MCP:"; nc -z localhost 8000 2>/dev/null && echo "✓ running" || echo "✗ stopped"
}

case "${1:-dev}" in
  dev)
    start_db
    start_mcp
    start_api
    start_dashboard
    echo ""
    echo "╭──────────────────────────────────────╮"
    echo "│  JobHunter dev stack running          │"
    echo "├──────────────────────────────────────┤"
    echo "│  API:       http://localhost:$(cat /tmp/jobhunter-api.port 2>/dev/null || echo '???')     │"
    echo "│  Dashboard: http://localhost:3000     │"
    echo "│  MCP:       http://localhost:8000     │"
    echo "├──────────────────────────────────────┤"
    echo "│  Logs:      make logs                 │"
    echo "│  Stop:      make stop                 │"
    echo "╰──────────────────────────────────────╯"
    ;;
  stop)
    stop_all
    ;;
  status)
    show_status
    ;;
  restart)
    stop_all
    sleep 2
    exec "$0" dev
    ;;
  *)
    echo "Usage: $0 {dev|stop|status|restart}"
    exit 1
    ;;
esac
