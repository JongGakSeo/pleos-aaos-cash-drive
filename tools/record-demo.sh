#!/usr/bin/env bash
# Record the demo take for the submission video (shot list A in submission/video-shotlist.md in the master repository).
# Drives the Pleos emulator through the Virtual Console while the emulator console records the full
# 2560x1440 display to a WebM, and writes the wall-clock time of every action so the edit can cut on them.
#
# Usage: tools/record-demo.sh <apk> [out_dir]
#   apk      the showcase build to install (release or debug); the package is ai.kilomoa.app
#   out_dir  default planning/video/take-<HHMMSS> (git-ignored by size policy; copy the final mp4 later)
# Env: VCONSOLE_SERIAL (default emulator-5556), DEMO_ODO_KM (default 48213), DEMO_SOC (default 72)
#      DEMO_VC (default tools/vconsole.sh): the signal tool; tools/vhal.sh on an emulator without the Virtual Console
#      DEMO_TAP_OFFSET ("dx dy", default "0 0"): added to node positions where uiautomator reports them relative to
#      the app's pane instead of the display (Connect 2.1.5: the pane's left top, e.g. "840 96")
# An emulator on another machine (adb through ADB_SERVER_SOCKET): `adb emu` reaches the console only from the
# emulator's own host, so set DEMO_EMU to a command that runs `adb -s <serial> emu <args>` there and
# DEMO_REC_PATH to the recording's path on that machine; copy that file to <out_dir>/take.webm afterwards.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:?showcase apk}"
OUT="${2:-$ROOT/planning/video/take-$(date +%H%M%S)}"
# The emulator console resolves the recording path against the emulator process cwd, so use an absolute path.
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac
PKG="ai.kilomoa.app"
SERIAL="${VCONSOLE_SERIAL:-emulator-5556}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
VC="${DEMO_VC:-$ROOT/tools/vconsole.sh}"
# The launcher entry (permission gate), as the app icon would start it; the home opens in its own task after it.
LAUNCH_ACTIVITY="$PKG/ai.cashdrive.app.home.PermissionGateActivity"
adb() { "$ADB" -s "$SERIAL" "$@"; }
emu() { if [ -n "${DEMO_EMU:-}" ]; then $DEMO_EMU "$@"; else adb emu "$@"; fi; }
T0=0
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
mark() { printf '{"t_ms": %d, "event": "%s"}\n' "$(( $(now_ms) - T0 ))" "$1" >> "$OUT/events.jsonl"; echo "$(date +%T) $1"; }
# Taps the node whose content description (or, with "text:", whose text) matches exactly, found in a fresh
# uiautomator dump; falls back to the given coordinates when the dump has no such node.
tap_node() {
  local want="$1" fx="$2" fy="$3" xy=""
  adb shell uiautomator dump /sdcard/kilomoa-ui.xml >/dev/null 2>&1 || true
  xy=$(adb shell cat /sdcard/kilomoa-ui.xml 2>/dev/null | python3 -I -c '
import re, sys
want = sys.argv[1]
attr = "text" if want.startswith("text:") else "content-desc"
want = want[5:] if want.startswith("text:") else want
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    value = re.search(r" " + attr + r"=\"([^\"]*)\"", node)
    if value and value.group(1) == want:
        b = [int(v) for v in re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", node).group(1))]
        print((b[0] + b[2]) // 2 + int(sys.argv[2]), (b[1] + b[3]) // 2 + int(sys.argv[3])); break
' "$want" ${DEMO_TAP_OFFSET:-0 0})
  [ -n "$xy" ] || xy="$fx $fy"
  adb shell input tap $xy
}
cleanup() {
  emu screenrecord stop >/dev/null 2>&1 || true
  "$VC" charge off >/dev/null 2>&1 || true; "$VC" gear Park >/dev/null 2>&1 || true; "$VC" stop >/dev/null 2>&1 || true
  "$VC" hb-stop >/dev/null 2>&1 || true
  adb shell cmd uimode night no >/dev/null 2>&1 || true
  echo "take saved under $OUT"
}
mkdir -p "$OUT"; : > "$OUT/events.jsonl"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }
trap cleanup EXIT

# Fresh install: welcome ticket, zero credits, permission dialog on first launch. A debug-signed copy of
# the same package cannot be updated by the release signature, so fall back to uninstall + install.
install_out=$(adb install -r "$APK" 2>&1 || true)
echo "$install_out" | tail -1
if ! echo "$install_out" | grep -q "Success"; then
  echo "install failed, retrying after uninstall"; adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install "$APK" 2>&1 | tail -1 | grep -q "Success" || { echo "install failed" >&2; exit 2; }
fi
adb shell am force-stop "$PKG" || true
adb shell pm clear --user current "$PKG" >/dev/null
adb shell cmd uimode night no >/dev/null 2>&1 || true
"$VC" hb-start >/dev/null
"$VC" gear Park >/dev/null; "$VC" stop >/dev/null; "$VC" charge off >/dev/null
"$VC" odo "${DEMO_ODO_KM:-48213}" >/dev/null; "$VC" soc "${DEMO_SOC:-72}" >/dev/null
sleep 2

emu screenrecord start --time-limit 300 "${DEMO_REC_PATH:-$OUT/take.webm}" >/dev/null
T0=$(now_ms); mark "record-start"
# Which first briefing this build plays: the PSA excerpt only in a -Pkilomoa.includePsa=true build. The video's
# end card credits the PSA only when this take shows it.
if unzip -l "$APK" | grep -q "assets/creatives/psa-right-turn.mp4"; then mark "psa-included"; else mark "psa-excluded"; fi
sleep 2
adb shell am start -n "$LAUNCH_ACTIVITY" >/dev/null; mark "launch (permission dialog expected)"
sleep 4
# A loaded emulator needs longer to show the dialog: wait for its button (up to a minute).
for _ in $(seq 20); do
  adb shell uiautomator dump /sdcard/kilomoa-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/kilomoa-ui.xml 2>/dev/null | grep -q 'text="허용"' && break; sleep 2
done
# Allow the speed permission (the dialog's 허용 button). The gate then opens the home in a fresh task (about
# 4 s with the scene's first frame), the welcome card shows for 0.8 s and the first briefing plays for 15 s:
# the credit lands about 20 s after the tap, and its count-up and toast need 3 s more before the car moves.
tap_node "text:허용" 1706 643; mark "permission-allow"
sleep 5; mark "welcome-briefing-start"
sleep 18; mark "welcome-credited"
sleep 2
"$VC" gear Drive >/dev/null; mark "gear-Drive"
sleep 2
"$VC" drive 60 >/dev/null; mark "drive-60"
sleep 66; mark "drive-1km-expected"
"$VC" stop >/dev/null; mark "stop-waiting"
sleep 6
# Park: the horse settles, the two reward cards (2.6 s), then the second briefing in the theater; 9 s after
# the gear change the clip is about 6 s in.
"$VC" gear Park >/dev/null; mark "gear-Park-reveal"
sleep 9; mark "briefing-2-6s"
"$VC" gear Drive >/dev/null; mark "depart-while-playing"
sleep 3
"$VC" gear Park >/dev/null; mark "park-resume"
sleep 12; mark "briefing-2-credited"
"$VC" charge on >/dev/null; mark "charge-on"
sleep 22; mark "briefing-3-credited"
# Credits page from the top bar chip, then the page's back arrow (a BACK key event is not always delivered to
# the app window after the gate -> home task handover; the tap is). Nodes by description, coordinates as fallback.
tap_node "크레딧 보기" 2348 134; mark "credits-page"
sleep 5
tap_node "뒤로" 921 134; sleep 1
tap_node "설정" 2467 134; mark "settings-page"
sleep 4
adb shell cmd uimode night yes >/dev/null 2>&1 || true; mark "night-on"
sleep 4
tap_node "뒤로" 921 134; sleep 2; mark "night-dashboard"
sleep 3
adb shell cmd uimode night no >/dev/null 2>&1 || true; mark "night-off"
sleep 2
mark "record-stop"
emu screenrecord stop >/dev/null
sleep 2
ls -la "$OUT"
