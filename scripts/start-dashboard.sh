#!/bin/zsh
source ~/.zshenv 2>/dev/null || true
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
PROJECT_ROOT="${0:A:h:h}"
cd "$PROJECT_ROOT/dashboard"
exec npm run dev
