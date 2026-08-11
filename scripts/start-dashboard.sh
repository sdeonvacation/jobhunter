#!/bin/zsh
source ~/.zshenv 2>/dev/null || true
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
PROJECT_ROOT="${0:A:h:h}"
cd "$PROJECT_ROOT/dashboard"
if [ ! -d node_modules ]; then
  npm install
fi
exec npm run dev -- --host 0.0.0.0
