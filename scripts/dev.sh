#!/bin/zsh
# JobHunter dev stack — uses launchctl submit for proper process management
# Usage: ./scripts/dev.sh {dev|stop|status|restart}
set -e
source ~/.zshenv 2>/dev/null || true

PROJECT_ROOT="${0:A:h:h}"
LOG_DIR=/tmp/jobhunter
DB_PORT=5435
JAVA_HOME="${JAVA_HOME:-$(ls -d $HOME/.gradle/jdks/*/jdk-21*/Contents/Home 2>/dev/null | head -1)}"
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
    echo "MCP already running"
    return
  fi
  local uvx_path=$(which uvx 2>/dev/null || echo "$HOME/.local/bin/uvx")
  if [[ ! -x "$uvx_path" ]]; then
    echo "WARN: uvx not found, skipping MCP"
    return
  fi
  echo "Starting LinkedIn MCP..."
  launchctl remove dev.jobhunter.mcp 2>/dev/null || true
  launchctl submit -l dev.jobhunter.mcp \
    -o "$LOG_DIR/mcp.log" -e "$LOG_DIR/mcp.log" \
    -- "$uvx_path" linkedin-scraper-mcp@latest --transport streamable-http --host 0.0.0.0 --port 8000 --log-level INFO
  for i in {1..10}; do nc -z localhost 8000 2>/dev/null && break; sleep 1; done
  nc -z localhost 8000 2>/dev/null && echo "  MCP ready" || echo "  WARN: MCP not responding"
}

start_api() {
  if curl -sf http://localhost:8080/api/admin/health >/dev/null 2>&1; then
    echo "API already running"
    return
  fi

  if [[ ! -f "$API_JAR" ]]; then
    echo "API JAR not found, building..."
    JAVA_HOME=$JAVA_HOME "$PROJECT_ROOT/api/gradlew" -p "$PROJECT_ROOT/api" bootJar -x test
  fi

  echo "Starting API..."
  launchctl remove dev.jobhunter.api 2>/dev/null || true
  # Write env wrapper script so launchctl inherits .zshenv vars
  cat > /tmp/jobhunter-start-api.sh <<EOF
#!/bin/zsh
source ~/.zshenv 2>/dev/null
exec "$JAVA_HOME/bin/java" -jar "$API_JAR" \\
  --spring.liquibase.enabled=false \\
  --spring.quartz.auto-startup=true \\
  "--profile.path=file:$PROJECT_ROOT/profile.yaml"
EOF
  chmod +x /tmp/jobhunter-start-api.sh
  launchctl submit -l dev.jobhunter.api \
    -o "$LOG_DIR/api.log" -e "$LOG_DIR/api.log" \
    -- /tmp/jobhunter-start-api.sh

  printf "Waiting for API..."
  for i in {1..40}; do
    curl -sf http://localhost:8080/api/admin/health >/dev/null 2>&1 && break
    printf "."; sleep 2
  done
  curl -sf http://localhost:8080/api/admin/health >/dev/null 2>&1 && echo " ready" || { echo " FAILED (check: tail $LOG_DIR/api.log)"; exit 1; }
}

start_dashboard() {
  if curl -sf http://localhost:3000 >/dev/null 2>&1; then
    echo "Dashboard already running"
    return
  fi
  echo "Starting Dashboard..."
  launchctl remove dev.jobhunter.dashboard 2>/dev/null || true
  local node_path=$(dirname $(which node))
  cat > /tmp/jobhunter-start-dashboard.sh <<EOF
#!/bin/zsh
export PATH="$node_path:\$PATH"
cd "$PROJECT_ROOT/dashboard"
exec npm run dev
EOF
  chmod +x /tmp/jobhunter-start-dashboard.sh
  launchctl submit -l dev.jobhunter.dashboard \
    -o "$LOG_DIR/dashboard.log" -e "$LOG_DIR/dashboard.log" \
    -- /tmp/jobhunter-start-dashboard.sh
  sleep 3
  curl -sf http://localhost:3000 >/dev/null 2>&1 && echo "  Dashboard ready" || echo "  WARN: Dashboard not responding yet"
}

stop_all() {
  echo "Stopping JobHunter..."
  launchctl remove dev.jobhunter.dashboard 2>/dev/null && echo "  Dashboard stopped" || true
  launchctl remove dev.jobhunter.api 2>/dev/null && echo "  API stopped" || true
  launchctl remove dev.jobhunter.mcp 2>/dev/null && echo "  MCP stopped" || true
  echo "Done (DB left running)"
}

show_status() {
  echo "=== JobHunter Status ==="
  printf "  %-12s " "DB:"; nc -z localhost $DB_PORT 2>/dev/null && echo "✓ running" || echo "✗ stopped"
  printf "  %-12s " "API:"; curl -sf http://localhost:8080/api/admin/health >/dev/null 2>&1 && echo "✓ running" || echo "✗ stopped"
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
    echo "│  API:       http://localhost:8080     │"
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
