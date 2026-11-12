#!/usr/bin/env bash
# Real-signal integration scenario on the Pleos Connect emulator (plan Task 11).
# Installs the given APK, drives the vehicle through the Virtual Console and captures screenshots,
# UI text dumps and app logs for each state. Emulator-only; never point it at a real vehicle.
#
# Usage: tools/emu-scenario.sh [apk] [out_dir] [package]
#   apk      default android/app/build/outputs/apk/showcase/debug/KiloMoa-showcase-debug.apk
#   out_dir  default planning/evidence/<YYYY-MM-DD>-emulator
#   package  default ai.cashdrive.app
# Env: VCONSOLE_SERIAL (default emulator-5556), SCENARIO_FAST=1 shortens the waits for a dry run.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/android/app/build/outputs/apk/showcase/debug/KiloMoa-showcase-debug.apk}"
OUT="${2:-$ROOT/planning/evidence/$(date +%Y-%m-%d)-emulator}"
PKG="${3:-ai.kilomoa.app}"
SERIAL="${VCONSOLE_SERIAL:-emulator-5556}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
VC="$ROOT/tools/vconsole.sh"
# The launcher entry (permission gate); the home opens in its own task after it.
LAUNCH_ACTIVITY="$PKG/ai.cashdrive.app.home.PermissionGateActivity"
FAST="${SCENARIO_FAST:-0}"

adb() { "$ADB" -s "$SERIAL" "$@"; }
log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/scenario.txt"; }
wait_s() { if [ "$FAST" = 1 ]; then sleep 2; else sleep "$1"; fi; }
shot() {  # shot <name>: screenshot + visible texts + console state
  adb exec-out screencap -p > "$OUT/$1.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -I -c '
import sys, re
x = sys.stdin.read()
print(" | ".join(t for t in re.findall(r"text=\"([^\"]+)\"", x) if t.strip()))' > "$OUT/$1.texts.txt" || true
  "$VC" state > "$OUT/$1.state.txt" 2>&1 || true
  log "shot $1: $(head -c 300 "$OUT/$1.texts.txt" | tr '\n' ' ')"
}
cleanup() {
  log "restore: Park, speed 0, charge off, heartbeat stop"
  "$VC" gear Park >/dev/null 2>&1 || true
  "$VC" stop >/dev/null 2>&1 || true
  "$VC" charge off >/dev/null 2>&1 || true
  "$VC" hb-stop >/dev/null 2>&1 || true
  adb logcat -d -v time 2>/dev/null | grep -E "CashDrive|AndroidRuntime|FATAL" > "$OUT/logcat.txt" || true
  log "done: $OUT"
}

mkdir -p "$OUT"
: > "$OUT/scenario.txt"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }
adb get-state >/dev/null 2>&1 || { echo "emulator $SERIAL not connected" >&2; exit 2; }
trap cleanup EXIT

log "apk: $APK ($(stat -f%z "$APK") bytes, sha256 $(shasum -a 256 "$APK" | cut -c1-16))"
log "emulator: $(adb shell getprop ro.build.display.id | tr -d '\r')"
adb install -r "$APK" | tail -1 | sed 's/^/install: /' | tee -a "$OUT/scenario.txt"
adb shell am force-stop "$PKG" || true
adb shell pm clear --user current "$PKG" >/dev/null
adb shell pm grant --user current "$PKG" android.car.permission.CAR_SPEED
adb logcat -c || true

"$VC" hb-start >/dev/null
"$VC" gear Park >/dev/null; "$VC" stop >/dev/null; "$VC" charge off >/dev/null
log "step 1: parked, launch home (welcome briefing should auto-play)"
adb shell am start -W -n "$LAUNCH_ACTIVITY" >/dev/null
wait_s 6;  shot 01-parked-playing
wait_s 14; shot 02-parked-credited

log "step 2: Drive, 72 km/h for 60 s (about 1.2 km: one ticket)"
"$VC" gear Drive >/dev/null; wait_s 2; shot 03-drive-0kph
"$VC" drive 72 >/dev/null; wait_s 30; shot 04-driving-30s
wait_s 30; shot 05-driving-60s

log "step 3: stop in Drive (signal wait): no video, horse stands"
"$VC" stop >/dev/null; wait_s 4; shot 06-waiting

log "step 4: Park -> reveal -> briefing auto-play"
"$VC" gear Park >/dev/null; wait_s 1; shot 07-park-reveal
wait_s 5; shot 08-park-briefing

log "step 5: depart while playing -> immediate stop, then Park -> resume"
"$VC" gear Drive >/dev/null; wait_s 2; shot 09-interrupted
"$VC" gear Park >/dev/null; wait_s 3; shot 10-resumed
wait_s 14; shot 11-second-credit

log "step 6: charge port on while parked -> bonus ticket (null means the emulator did not deliver it)"
"$VC" charge on >/dev/null; wait_s 4; shot 12-charging
"$VC" charge off >/dev/null; wait_s 2

log "step 7: process restart keeps credits and position"
adb shell am force-stop "$PKG"; wait_s 1
adb shell am start -W -n "$LAUNCH_ACTIVITY" >/dev/null; wait_s 6; shot 13-restarted
