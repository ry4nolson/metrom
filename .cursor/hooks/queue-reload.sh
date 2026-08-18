#!/usr/bin/env bash
# Cursor afterFileEdit / afterTabFileEdit: queue a debounced app reload.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:$HOME/.pyenv/shims:$PATH"

path="$(python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    raise SystemExit(0)
print(data.get("file_path") or "")
' 2>/dev/null || true)"

if [[ -n "${path:-}" ]]; then
  "$ROOT/scripts/dev-reload-queue.sh" "$path" || true
fi
echo '{}'
exit 0
