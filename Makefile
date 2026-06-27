# JobHunter - Dev Stack
# Usage: make dev | make stop | make status | make logs
#
# Inherits environment from ~/.zshenv (JOBHUNTER_AI_* vars)
# Services run as background processes, survive terminal exit.

SHELL := /bin/zsh
PROJECT_ROOT := $(shell pwd)
JAVA_21_HOME := $(shell ls -d $$HOME/.gradle/jdks/*/jdk-21*/Contents/Home 2>/dev/null | head -1)
JAVA_HOME := $(or $(JAVA_21_HOME),$(JAVA_HOME))

.PHONY: dev stop restart status logs logs-all build build-api build-dashboard test clean

# === Primary targets ===

dev:
	@$(PROJECT_ROOT)/scripts/dev.sh dev

stop:
	@$(PROJECT_ROOT)/scripts/dev.sh stop

restart:
	@$(PROJECT_ROOT)/scripts/dev.sh restart

status:
	@$(PROJECT_ROOT)/scripts/dev.sh status

logs:
	@tail -f /tmp/jobhunter/api.log

logs-all:
	@tail -f /tmp/jobhunter/api.log /tmp/jobhunter/dashboard.log /tmp/jobhunter/mcp.log

# === Build targets ===

build: build-api
	@echo "Build complete"

build-api:
	@echo "Building API JAR..."
	@source ~/.zshenv 2>/dev/null; JAVA_HOME=$(JAVA_HOME) $(PROJECT_ROOT)/api/gradlew -p $(PROJECT_ROOT)/api bootJar -x test
	@echo "API JAR ready"

build-dashboard:
	@echo "Building Dashboard..."
	@cd $(PROJECT_ROOT)/dashboard && npm run build
	@echo "Dashboard build ready"

test:
	@source ~/.zshenv 2>/dev/null; JAVA_HOME=$(JAVA_HOME) $(PROJECT_ROOT)/api/gradlew -p $(PROJECT_ROOT)/api test

clean:
	@JAVA_HOME=$(JAVA_HOME) $(PROJECT_ROOT)/api/gradlew -p $(PROJECT_ROOT)/api clean
