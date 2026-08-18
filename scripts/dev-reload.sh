#!/usr/bin/env bash
# Rebuild and relaunch Metrom on whatever devices are currently booted.
# Usage: dev-reload.sh [--android] [--ios]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ -f "$ROOT/local.properties" ]]; then
  sdk_dir="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" | tail -n1 | tr -d '\r')"
  [[ -n "$sdk_dir" ]] && export ANDROID_HOME="$sdk_dir"
fi
export PATH="$ANDROID_HOME/platform-tools:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:$PATH"

LOG="${METROM_RELOAD_LOG:-/tmp/metrom-reload.log}"
WANT_ANDROID=0
WANT_IOS=0
for arg in "$@"; do
  case "$arg" in
    --android) WANT_ANDROID=1 ;;
    --ios) WANT_IOS=1 ;;
  esac
done
if [[ "$WANT_ANDROID" -eq 0 && "$WANT_IOS" -eq 0 ]]; then
  WANT_ANDROID=1
  WANT_IOS=1
fi

log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$LOG"; }

android_serials() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}'
}

ios_udid() {
  xcrun simctl list devices booted -j 2>/dev/null | python3 -c '
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data.get("devices", {}).items():
    for d in devices:
        if d.get("state") == "Booted":
            print(d["udid"])
            raise SystemExit
' 2>/dev/null
}

reload_android() {
  local serials
  serials="$(android_serials)"
  if [[ -z "$serials" ]]; then
    log "android: skip (no device)"
    return 0
  fi
  log "android: installDebug"
  if ! ./gradlew --console=plain :androidApp:installDebug; then
    log "android: BUILD FAILED"
    return 1
  fi
  local serial
  for serial in $serials; do
    log "android: relaunch $serial"
    adb -s "$serial" shell am start -S -n com.metrom.app/.MainActivity >/dev/null
  done
  log "android: launched"
}

reload_ios() {
  local udid
  udid="$(ios_udid)"
  if [[ -z "$udid" ]]; then
    log "ios: skip (no booted simulator)"
    return 0
  fi
  log "ios: xcodebuild $udid"
  if ! (
    cd "$ROOT/iosApp"
    xcodebuild -project Metrom.xcodeproj -scheme Metrom -configuration Debug \
      -destination "id=$udid" \
      -derivedDataPath "$PWD/build/DerivedData" \
      CODE_SIGN_IDENTITY="-" build
  ); then
    log "ios: BUILD FAILED"
    return 1
  fi
  local app="$ROOT/iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/Metrom.app"
  if [[ ! -d "$app" ]]; then
    log "ios: missing $app"
    return 1
  fi
  log "ios: relaunch"
  xcrun simctl terminate "$udid" com.metrom.app >/dev/null 2>&1 || true
  xcrun simctl install "$udid" "$app"
  xcrun simctl launch "$udid" com.metrom.app >/dev/null
  log "ios: launched"
}

status=0
log "reload start android=$WANT_ANDROID ios=$WANT_IOS"
if [[ "$WANT_ANDROID" -eq 1 ]]; then
  reload_android || status=1
fi
if [[ "$WANT_IOS" -eq 1 ]]; then
  reload_ios || status=1
fi
log "reload done status=$status"
exit "$status"
