#!/usr/bin/env bash
# Build MetromShared for the active Xcode destination.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
./gradlew :shared:embedAndSignAppleFrameworkForXcode "$@"
