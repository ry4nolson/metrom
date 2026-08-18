#!/usr/bin/env bash
# Wait for a quiet period, then rebuild affected platforms.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE="${METROM_RELOAD_STATE:-/tmp/metrom-dev-reload}"
LOG="${METROM_RELOAD_LOG:-/tmp/metrom-reload.log}"
DEBOUNCE="${METROM_RELOAD_DEBOUNCE:-3}"

cleanup() {
  rm -f "$STATE/debounce.pid"
  rmdir "$STATE/debounce.lock" 2>/dev/null || rm -rf "$STATE/debounce.lock"
}
trap cleanup EXIT
echo $$ > "$STATE/debounce.pid"

quiet_enough() {
  local last now
  last="$(cat "$STATE/last" 2>/dev/null || echo 0)"
  now="$(date +%s)"
  [[ $((now - last)) -ge "$DEBOUNCE" ]]
}

classify() {
  WANT_ANDROID=0
  WANT_IOS=0
  local p
  while IFS= read -r p; do
    [[ -z "$p" ]] && continue
    case "$p" in
      */build/*|*/.gradle/*|*/DerivedData/*|*.xcuserstate|*.md)
        continue
        ;;
    esac
    case "$p" in
      *androidApp/*|*shared/src/androidMain/*|*shared/src/androidTest/*)
        WANT_ANDROID=1
        ;;
      *iosApp/Metrom/*|*iosApp/Metrom.xcodeproj/project.pbxproj|*shared/src/iosMain/*|*shared/src/iosTest/*)
        WANT_IOS=1
        ;;
      *shared/src/commonMain/*|*shared/src/commonTest/*|*shared/*.gradle.kts|*androidApp/*.gradle.kts|*gradle/libs.versions.toml|*settings.gradle.kts|*build.gradle.kts)
        WANT_ANDROID=1
        WANT_IOS=1
        ;;
    esac
  done
}

while true; do
  while ! quiet_enough; do
    sleep 1
  done
  work="$STATE/queue.work.$$"
  if ! mv "$STATE/queue" "$work" 2>/dev/null; then
    if [[ -s "$STATE/queue" ]]; then
      continue
    fi
    break
  fi
  classify < "$work"
  rm -f "$work"
  if [[ "$WANT_ANDROID" -eq 0 && "$WANT_IOS" -eq 0 ]]; then
    if [[ -s "$STATE/queue" ]]; then
      continue
    fi
    break
  fi
  args=()
  [[ "$WANT_ANDROID" -eq 1 ]] && args+=(--android)
  [[ "$WANT_IOS" -eq 1 ]] && args+=(--ios)
  printf '%s debounce firing %s\n' "$(date '+%H:%M:%S')" "${args[*]}" >>"$LOG"
  "$ROOT/scripts/dev-reload.sh" "${args[@]}" || true
  if [[ -s "$STATE/queue" ]] || ! quiet_enough; then
    continue
  fi
  break
done
