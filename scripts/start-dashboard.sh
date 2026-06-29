#!/bin/zsh
source ~/.zshenv 2>/dev/null || true
PROJECT_ROOT="${0:A:h:h}"
cd "$PROJECT_ROOT/dashboard"
exec npm run dev
