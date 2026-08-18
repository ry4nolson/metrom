#!/usr/bin/env bash
# Start the Metrom source watcher if it is not already running.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STATE="${METROM_RELOAD_STATE:-/tmp/metrom-dev-reload}"
LOG="${METROM_RELOAD_LOG:-/tmp/metrom-reload.log}"
export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:$HOME/.pyenv/shims:$PATH"
mkdir -p "$STATE"

if [[ -f "$STATE/watch.pid" ]] && kill -0 "$(cat "$STATE/watch.pid" 2>/dev/null)" 2>/dev/null; then
  echo '{}'
  exit 0
fi

python3 - "$ROOT" "$LOG" <<'PY'
import os, sys
root, log_path = sys.argv[1], sys.argv[2]
script = os.path.join(root, "scripts", "dev-watch.py")
if os.fork() > 0:
    sys.exit(0)
os.setsid()
if os.fork() > 0:
    sys.exit(0)
os.chdir(root)
devnull = os.open("/dev/null", os.O_RDWR)
os.dup2(devnull, 0)
logfd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
os.dup2(logfd, 1)
os.dup2(logfd, 2)
os.execv(sys.executable, [sys.executable, script])
PY

echo '{}'
exit 0
