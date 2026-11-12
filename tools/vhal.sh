#!/usr/bin/env bash
# Vehicle signals on a Pleos emulator that has no Virtual Console (Connect 2.1.5): the same sub-commands as
# tools/vconsole.sh, written straight into the emulator's VHAL through its debug interface, so the app's
# property reads and callbacks both see them. Needs a userdebug image (su). Emulator-only test tool.
# Usage: vhal.sh gear Park|Reverse|Neutral|Drive | drive <kph> | stop | charge on|off | odo <km> | state
#        (hb-start, hb-stop and soc are accepted and do nothing: there is no session to keep alive here)
# With tools/record-demo.sh: DEMO_VC=tools/vhal.sh.
set -euo pipefail
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
SERIAL="${VCONSOLE_SERIAL:-emulator-5556}"
GEAR=0x11400400; SPEED=0x11600207; SPEED_DISPLAY=0x11600208; ODOMETER=0x11600204
PORT_OPEN=0x1120030A; PORT_CONNECTED=0x1120030B
vhal() { "$ADB" -s "$SERIAL" shell "su 0 dumpsys android.hardware.automotive.vehicle.IVehicle/default $*"; }
put() { vhal --set "$@" | grep -q "Set property" || { echo "VHAL refused: $*" >&2; exit 1; }; }
speed() { local mps; mps=$(python3 -c "print(round($1 / 3.6, 3))"); put $SPEED -f "$mps"; put $SPEED_DISPLAY -f "$mps"; }
case "${1:-}" in
  hb-start|hb-stop|soc) ;;
  gear) case "${2:-}" in Park) v=4;; Reverse) v=2;; Neutral) v=1;; Drive) v=8;; *) echo "gear Park|Reverse|Neutral|Drive" >&2; exit 2;; esac
        put $GEAR -i $v ;;
  drive) speed "${2:?kph}" ;;
  stop) speed 0 ;;
  charge) case "${2:-}" in on) v=1;; off) v=0;; *) echo "charge on|off" >&2; exit 2;; esac
          put $PORT_OPEN -i $v; put $PORT_CONNECTED -i $v ;;
  odo) put $ODOMETER -f "${2:?km}" ;;
  state) vhal --get $GEAR $SPEED $PORT_OPEN $PORT_CONNECTED $ODOMETER | sed -n 's/.*prop: \([0-9]*\),.*int32Values: \[\([^]]*\)\], floatValues: \[\([^]]*\)\].*/\1 \2\3/p' ;;
  *) sed -n 2,7p "$0" >&2; exit 2 ;;
esac
