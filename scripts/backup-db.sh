#!/bin/bash
# Daily PostgreSQL backup to iCloud Drive
# Keeps last 7 days of backups, deletes older ones.

set -euo pipefail

BACKUP_DIR="$HOME/Library/Mobile Documents/com~apple~CloudDocs/jobhunter-backups"
TIMESTAMP=$(date +%Y-%m-%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/jobhunter_${TIMESTAMP}.sql.gz"
RETENTION_DAYS=7

DB_HOST=localhost
DB_PORT=5435
DB_USER=jobhunter
DB_NAME=jobhunter

export PGPASSWORD=jobhunter

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# Ensure backup directory exists
mkdir -p "$BACKUP_DIR"

# Run pg_dump with gzip compression
log "Starting backup: $BACKUP_FILE"

if command -v pg_dump &>/dev/null; then
    pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" "$DB_NAME" | gzip > "$BACKUP_FILE"
else
    # Fallback: use docker exec if pg_dump not installed on host
    log "pg_dump not found on host, using docker exec fallback"
    export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
    CONTAINER=$(docker ps --filter "publish=5435" --format '{{.Names}}' | head -1)
    if [ -z "$CONTAINER" ]; then
        log "ERROR: No container found exposing port 5435"
        exit 1
    fi
    docker exec -e PGPASSWORD="$PGPASSWORD" "$CONTAINER" \
        pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "$BACKUP_FILE"
fi

# Verify backup was created and is non-empty
if [ ! -s "$BACKUP_FILE" ]; then
    log "ERROR: Backup file is empty or missing: $BACKUP_FILE"
    rm -f "$BACKUP_FILE"
    exit 1
fi

BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
log "Backup complete: $BACKUP_FILE ($BACKUP_SIZE)"

# Delete backups older than retention period
DELETED=0
find "$BACKUP_DIR" -name "jobhunter_*.sql.gz" -mtime +${RETENTION_DAYS} -print -delete | while read -r f; do
    log "Deleted old backup: $f"
    DELETED=$((DELETED + 1))
done

log "Cleanup done. Backup successful."
