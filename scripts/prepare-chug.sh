#!/usr/bin/env bash
# Convert a recorded palm-mute guitar hit into Metrom chug WAVs.
#
# Usage:
#   ./scripts/prepare-chug.sh /path/to/your-chug.wav
#   ./scripts/prepare-chug.sh /path/to/normal.wav /path/to/accent.wav
#
# Tips for recording:
#   - One short palm-mute power chord (50–150 ms of attack, then silence)
#   - Close-mic or DI + amp sim is fine; leave a few ms of pre-roll silence
#   - Record accent slightly harder/brighter if you have a second take
#
# Output:
#   app/src/main/assets/chug/chug.wav
#   app/src/main/assets/chug/chug_accent.wav
#
# Quick test without rebuilding (device/emulator with app installed):
#   adb shell mkdir -p /data/local/tmp/chug
#   adb push app/src/main/assets/chug/chug.wav /data/local/tmp/chug/
#   adb shell run-as com.metrom.app mkdir -p files/chug
#   adb shell run-as com.metrom.app cp /data/local/tmp/chug/chug.wav files/chug/chug.wav
#   (same for chug_accent.wav) then force-stop + relaunch the app

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/chug"
mkdir -p "$OUT"

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg required (brew install ffmpeg)" >&2
  exit 1
fi

SRC_NORMAL="${1:-}"
SRC_ACCENT="${2:-}"

if [[ -z "$SRC_NORMAL" ]]; then
  echo "Usage: $0 <chug.wav|mp3|aiff> [accent.wav]" >&2
  exit 1
fi

convert() {
  local src="$1"
  local dst="$2"
  local gain="${3:-0}"
  # Mono 44.1k 16-bit PCM, trim leading silence, keep ~120ms, soft fade
  ffmpeg -y -hide_banner -loglevel error -i "$src" \
    -af "silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.002,atrim=0:0.12,afade=t=out:st=0.08:d=0.04,volume=${gain}dB" \
    -ac 1 -ar 44100 -c:a pcm_s16le "$dst"
  echo "Wrote $dst ($(wc -c <"$dst") bytes)"
}

convert "$SRC_NORMAL" "$OUT/chug.wav" 0
if [[ -n "$SRC_ACCENT" ]]; then
  convert "$SRC_ACCENT" "$OUT/chug_accent.wav" 0
else
  # Slightly hotter copy as accent if no second take
  convert "$SRC_NORMAL" "$OUT/chug_accent.wav" 2.5
fi

echo
echo "Next: rebuild so assets ship in the APK:"
echo "  export JAVA_HOME=\$(/usr/libexec/java_home -v 19)"
echo "  ./gradlew installDebug"
