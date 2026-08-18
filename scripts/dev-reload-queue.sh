#!/usr/bin/env bash
# Enqueue a changed path and debounce a rebuild/relaunch.
# Usage: dev-reload-queue.sh [path]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE="${METROM_RELOAD_STATE:-/tmp/metrom-dev-reload}"
LOG="${METROM_RELOAD_LOG:-/tmp/metrom-reload.log}"
mkdir -p "$STATE"

path="${1:-}"
[[ -n "$path" ]] && printf '%s\n' "$path" >> "$STATE/queue"
date +%s > "$STATE/last"

if [[ -f "$STATE/debounce.pid" ]] && kill -0 "$(cat "$STATE/debounce.pid" 2>/dev/null)" 2>/dev/null; then
  exit 0
fi
if ! mkdir "$STATE/debounce.lock" 2>/dev/null; then
  if [[ -f "$STATE/debounce.pid" ]] && kill -0 "$(cat "$STATE/debounce.pid" 2>/dev/null)" 2>/dev/null; then
    exit 0
  fi
  rm -rf "$STATE/debounce.lock"
  mkdir "$STATE/debounce.lock" || exit 0
fi

python3 - "$ROOT" "$STATE" "$LOG" <<'PY'
import os, sys
root, state, log_path = sys.argv[1], sys.argv[2], sys.argv[3]
script = os.path.join(root, "scripts", "dev-reload-debounce.sh")
if os.fork() > 0:
    sys.exit(0)
os.setsid()
if os.fork() > 0:
    sys.exit(0)
os.chdir(root)
with open(os.path.join(state, "debounce.pid"), "w") as fh:
    fh.write(str(os.getpid()))
devnull = os.open("/dev/null", os.O_RDWR)
os.dup2(devnull, 0)
logfd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
os.dup2(logfd, 1)
os.dup2(logfd, 2)
os.execv(script, [script])
PY
exit 0
