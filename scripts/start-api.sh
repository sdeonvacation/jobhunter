#!/bin/zsh
source ~/.zshenv 2>/dev/null || true
PROJECT_ROOT="${0:A:h:h}"
JAVA_HOME="${JAVA_HOME:-$(ls -d $HOME/.gradle/jdks/*/jdk-21*/Contents/Home 2>/dev/null | head -1)}"
API_JAR="$PROJECT_ROOT/api/build/libs/jobhunter-api-0.0.1-SNAPSHOT.jar"
exec "$JAVA_HOME/bin/java" -jar "$API_JAR" \
  --spring.liquibase.enabled=false \
  "--profile.path=file:$PROJECT_ROOT/profile.yaml"
