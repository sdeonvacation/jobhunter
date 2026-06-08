# JobHunter - Application Stack Management
# Usage: make up | down | restart | build | logs | status
#
# Prerequisites:
#   - Docker (via Colima or Docker Desktop)
#   - Java 21 (Temurin) — auto-detected via Gradle toolchain or JAVA_HOME
#   - Node.js 18+ (for dashboard)
#   - uvx (for LinkedIn MCP server)
#
# Environment variables:
#   JOBHUNTER_AI_API_KEY  — required for AI-powered aggregators (BerlinStartupJobs)
#   DOCKER_HOST           — override Docker socket (default: auto-detect Colima)
#   JAVA_HOME             — override Java location (default: Gradle-managed JDK)

# --- Auto-detect paths ---
PROJECT_ROOT := $(shell pwd)
# Auto-detect Docker socket (Colima default, then Docker Desktop)
DOCKER_HOST ?= $(or $(shell test -S $$HOME/.colima/default/docker.sock && echo "unix://$$HOME/.colima/default/docker.sock"), \
                    $(shell test -S /var/run/docker.sock && echo "unix:///var/run/docker.sock"))
# Project requires Java 21 — prefer Gradle-managed JDK, fall back to JAVA_HOME if version 21
JAVA_21_HOME := $(shell ls -d $$HOME/.gradle/jdks/*/jdk-21*/Contents/Home 2>/dev/null | head -1)
JAVA_HOME := $(or $(JAVA_21_HOME),$(JAVA_HOME))
GUI_DOMAIN := gui/$$(id -u)
API_JAR := $(PROJECT_ROOT)/api/build/libs/jobhunter-api-0.0.1-SNAPSHOT.jar
API_PLIST := /tmp/dev.jobhunter.api.plist
MCP_PLIST := /tmp/dev.jobhunter.linkedin-mcp.plist
DASHBOARD_PLIST := /tmp/dev.jobhunter.dashboard.plist
API_LOG := /tmp/jobhunter-api.log
DB_PORT := 5435

.PHONY: up down restart build logs status

up: _check-env _start-db _wait-db _generate-plists _start-services
	@echo ""
	@echo "JobHunter stack is up"
	@echo "  API:       http://localhost:8080"
	@echo "  Dashboard: http://localhost:3000"

down:
	@echo "Stopping services..."
	@launchctl bootout $(GUI_DOMAIN)/dev.jobhunter.dashboard 2>/dev/null || true
	@launchctl bootout $(GUI_DOMAIN)/dev.jobhunter.api 2>/dev/null || true
	@launchctl bootout $(GUI_DOMAIN)/dev.jobhunter.linkedin-mcp 2>/dev/null || true
	@echo "Services stopped (DB left running)"

restart: down up

build:
	@echo "Building API JAR..."
	@JAVA_HOME=$(JAVA_HOME) $(PROJECT_ROOT)/api/gradlew -p $(PROJECT_ROOT)/api bootJar -x test
	@echo "Build complete"

logs:
	@tail -f $(API_LOG)

status:
	@echo "=== JobHunter Service Status ==="
	@printf "  %-15s " "PostgreSQL:"; \
		nc -z localhost $(DB_PORT) 2>/dev/null && echo "running (port $(DB_PORT))" || echo "stopped"
	@printf "  %-15s " "LinkedIn MCP:"; \
		launchctl print $(GUI_DOMAIN)/dev.jobhunter.linkedin-mcp 2>/dev/null | grep -q "state = running" && echo "running" || echo "stopped"
	@printf "  %-15s " "API:"; \
		launchctl print $(GUI_DOMAIN)/dev.jobhunter.api 2>/dev/null | grep -q "state = running" && echo "running" || echo "stopped"
	@printf "  %-15s " "Dashboard:"; \
		launchctl print $(GUI_DOMAIN)/dev.jobhunter.dashboard 2>/dev/null | grep -q "state = running" && echo "running" || echo "stopped"

# --- Internal targets ---

_check-env:
	@if [ -z "$$JOBHUNTER_AI_API_KEY" ]; then \
		echo "ERROR: JOBHUNTER_AI_API_KEY not set"; \
		echo "  export JOBHUNTER_AI_API_KEY=<your-key>"; \
		exit 1; \
	fi
	@if [ -z "$(JAVA_HOME)" ]; then \
		echo "ERROR: JAVA_HOME not found. Install Java 21 or set JAVA_HOME."; \
		exit 1; \
	fi
	@if [ -z "$(DOCKER_HOST)" ]; then \
		echo "ERROR: Docker not found. Install Docker Desktop or Colima."; \
		exit 1; \
	fi
	@if [ ! -f "$(API_JAR)" ]; then \
		echo "API JAR not found. Run 'make build' first."; \
		exit 1; \
	fi

_start-db:
	@echo "Starting PostgreSQL..."
	@DOCKER_HOST=$(DOCKER_HOST) docker compose up -d db

_wait-db:
	@printf "Waiting for DB..."
	@for i in $$(seq 1 30); do \
		nc -z localhost $(DB_PORT) 2>/dev/null && break; \
		printf "."; \
		sleep 1; \
	done
	@nc -z localhost $(DB_PORT) 2>/dev/null || (echo " FAILED (timeout)" && exit 1)
	@echo " ready"

_generate-plists:
	@echo "Generating service plists..."
	@UVX_PATH=$$(which uvx 2>/dev/null || echo "/usr/local/bin/uvx"); \
	NODE_BIN=$$(dirname $$(which node 2>/dev/null) || echo "/usr/local/bin"); \
	sed -e "s|__JAVA_HOME__|$(JAVA_HOME)|g" \
	    -e "s|__PROJECT_ROOT__|$(PROJECT_ROOT)|g" \
	    -e "s|__API_JAR__|$(API_JAR)|g" \
	    -e "s|__AI_API_KEY__|$$JOBHUNTER_AI_API_KEY|g" \
	    -e "s|__AI_PROVIDER__|$${JOBHUNTER_AI_PROVIDER:-anthropic}|g" \
	    -e "s|__AI_BASE_URL__|$$JOBHUNTER_AI_BASE_URL|g" \
	    -e "s|__AI_EXTRACTION_MODEL__|$${JOBHUNTER_AI_EXTRACTION_MODEL:-claude-haiku-4-5}|g" \
	    -e "s|__AI_TAILORING_MODEL__|$${JOBHUNTER_AI_TAILORING_MODEL:-claude-sonnet-4-5}|g" \
	    -e "s|__NODE_BIN__|$$NODE_BIN|g" \
	    $(PROJECT_ROOT)/infra/api.plist.template > $(API_PLIST)
	@sed -e "s|__UVX_PATH__|$$UVX_PATH|g" \
	    $(PROJECT_ROOT)/infra/linkedin-mcp.plist.template > $(MCP_PLIST)
	@sed -e "s|__PROJECT_ROOT__|$(PROJECT_ROOT)|g" \
	    -e "s|__NODE_BIN__|$$NODE_BIN|g" \
	    $(PROJECT_ROOT)/infra/dashboard.plist.template > $(DASHBOARD_PLIST)

_start-services:
	@echo "Checking LinkedIn session..."
	@UVX_PATH=$$(which uvx 2>/dev/null || echo "uvx"); \
	if ! $$UVX_PATH linkedin-scraper-mcp@latest --status 2>&1 | grep -q "Session is valid"; then \
		echo "LinkedIn session expired. Opening browser for login..."; \
		$$UVX_PATH linkedin-scraper-mcp@latest --login --no-headless; \
	else \
		echo "LinkedIn session valid"; \
	fi
	@echo "Starting LinkedIn MCP..."
	@launchctl bootstrap $(GUI_DOMAIN) $(MCP_PLIST) 2>/dev/null || true
	@echo "Starting API..."
	@launchctl bootstrap $(GUI_DOMAIN) $(API_PLIST) 2>/dev/null || true
	@printf "Waiting for API..."
	@for i in $$(seq 1 60); do \
		curl -sf http://localhost:8080/api/admin/health > /dev/null 2>&1 && break; \
		printf "."; \
		sleep 2; \
	done
	@curl -sf http://localhost:8080/api/admin/health > /dev/null 2>&1 || (echo " FAILED (timeout)" && exit 1)
	@echo " ready"
	@echo "Starting Dashboard..."
	@launchctl bootstrap $(GUI_DOMAIN) $(DASHBOARD_PLIST) 2>/dev/null || true
