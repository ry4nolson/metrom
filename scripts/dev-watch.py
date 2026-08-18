#!/usr/bin/env python3
"""Poll Metrom app sources and queue a rebuild when files change."""
from __future__ import annotations

import os
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
STATE = os.environ.get("METROM_RELOAD_STATE", "/tmp/metrom-dev-reload")
LOG = os.environ.get("METROM_RELOAD_LOG", "/tmp/metrom-reload.log")
QUEUE = os.path.join(ROOT, "scripts", "dev-reload-queue.sh")
INTERVAL = float(os.environ.get("METROM_WATCH_INTERVAL", "1.0"))

WATCH_ROOTS = (
    os.path.join(ROOT, "androidApp", "src"),
    os.path.join(ROOT, "shared", "src"),
    os.path.join(ROOT, "iosApp", "Metrom"),
)
WATCH_FILES = (
    os.path.join(ROOT, "build.gradle.kts"),
    os.path.join(ROOT, "settings.gradle.kts"),
    os.path.join(ROOT, "gradle", "libs.versions.toml"),
    os.path.join(ROOT, "androidApp", "build.gradle.kts"),
    os.path.join(ROOT, "shared", "build.gradle.kts"),
    os.path.join(ROOT, "iosApp", "Metrom.xcodeproj", "project.pbxproj"),
)
SKIP_DIRS = {"build", ".gradle", "DerivedData", ".git"}
EXTS = {".kt", ".kts", ".xml", ".swift", ".plist", ".toml", ".gradle"}


def log(msg: str) -> None:
    line = time.strftime("%H:%M:%S") + " " + msg + "\n"
    with open(LOG, "a") as fh:
        fh.write(line)


def snapshot() -> dict[str, float]:
    out: dict[str, float] = {}
    for path in WATCH_FILES:
        try:
            out[path] = os.path.getmtime(path)
        except OSError:
            pass
    for root in WATCH_ROOTS:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for name in filenames:
                ext = os.path.splitext(name)[1]
                if ext not in EXTS and name != "Info.plist":
                    continue
                path = os.path.join(dirpath, name)
                try:
                    out[path] = os.path.getmtime(path)
                except OSError:
                    pass
    return out


def enqueue(path: str) -> None:
    subprocess.Popen(
        [QUEUE, path],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )


def main() -> int:
    os.makedirs(STATE, exist_ok=True)
    pid_path = os.path.join(STATE, "watch.pid")
    if os.path.exists(pid_path):
        try:
            old = int(open(pid_path).read().strip())
            os.kill(old, 0)
            log(f"watch: already running pid={old}")
            return 0
        except (ValueError, OSError, ProcessLookupError):
            pass
    with open(pid_path, "w") as fh:
        fh.write(str(os.getpid()))
    prev = snapshot()
    log(f"watch: started pid={os.getpid()} files={len(prev)}")
    try:
        while True:
            time.sleep(INTERVAL)
            cur = snapshot()
            changed = [p for p, m in cur.items() if prev.get(p) != m]
            changed += [p for p in prev if p not in cur]
            if changed:
                enqueue(changed[0] if len(changed) == 1 else changed[0])
                for extra in changed[1:]:
                    enqueue(extra)
            prev = cur
    except KeyboardInterrupt:
        return 0
    finally:
        try:
            if os.path.exists(pid_path) and open(pid_path).read().strip() == str(os.getpid()):
                os.remove(pid_path)
        except OSError:
            pass


if __name__ == "__main__":
    sys.exit(main())
