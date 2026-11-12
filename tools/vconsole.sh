#!/usr/bin/env bash
# Pleos Connect Virtual Console helper. Emulator-only test tool; never point it at a real vehicle.
#
# How the console works (read on emulator-5556, Pleos emulator 2.1.0, 2026-11-08):
# - The console's HTTP API listens on DEVICE port 8088. This script adb-forwards it to HOST port
#   18088 (override with VCONSOLE_PORT); the adb serial defaults to emulator-5556 (VCONSOLE_SERIAL).
# - HEARTBEAT IS REQUIRED FOR WRITES. The console accepts vehicle PUTs only while a browser session
#   is alive: POST /browser/session?client=... at least every 3 s (GET /vehicle/state reports
#   browser.timeout_ms = 3000). `hb-start` runs that loop in the background (pid file under $TMPDIR)
#   and `hb-stop` ends it. Without a live heartbeat the PUTs are ignored, so run `hb-start` first.
# - READS never need the heartbeat and change nothing: `state` (GET /vehicle/state) and
#   `catalog` (GET /vehicle/catalog) are safe to run at any time.
# - THE LAST GEAR IS KEPT after the heartbeat stops. If a session ends in Drive, the AAOS driving
#   state stays moving/idling and the app keeps the video gate closed. Always finish with
#   `gear Park` and `stop` before `hb-stop`; `hb-stop` warns when the gear is not Park.
# - Write parameters come from GET /vehicle/catalog: PUT <feature.write_endpoint> with the query
#   parameter <control.field>=<value> plus the fixed <control.params>. Energy domain, as read on
#   2026-11-08 (no PUT was sent while writing this script; first real use is the 11/10 run):
#     ev-charge-port    PUT /vehicle/ev-battery?field=charge_port_open&value=true|false
#                       -> state ev_battery.charging_door_opened, car property EV_CHARGE_PORT_OPEN (0x1120030A)
#     ev-charge-switch  PUT /vehicle/ev-battery?field=charge_switch&value=true|false
#                       -> state ev_battery.charging_activated, car property EV_CHARGE_SWITCH (0x11200F42)
#     ev-battery-soc    PUT /vehicle/ev-battery?field=soc&value=<0..100>
#                       -> state ev_battery.state_of_charge, car property EV_BATTERY_SOC (0x21400BA7)
#   The catalog has NO card for EV_CHARGE_PORT_CONNECTED (0x1120030B), which the app subscribes to.
#   Whether the emulator derives it from the two writes above is UNKNOWN until the 11/10 run.
# - `drive`/`stop` write /vehicle/controls (speed, accelerator, brake). There is no speed card in
#   the catalog; these calls come from the earlier session's script (GET /vehicle/state on 2026-11-08
#   showed brake_pct=60 from source "web", matching `stop`) and were not re-run while writing this.
#
# Usage:
#   vconsole.sh hb-start | hb-stop
#   vconsole.sh gear Park|Reverse|Neutral|Drive
#   vconsole.sh drive <kph> | stop
#   vconsole.sh charge on|off        # on = port open + charge switch on (two PUTs); off reverses both
#   vconsole.sh odo <km> | soc <pct>  # odometer card (km) and battery SOC card (%), for realistic demo values
#   vconsole.sh state                # GET only: gear, speed, charge port/switch, SOC, heartbeat, UXR
#   vconsole.sh catalog [domain]     # GET only: write endpoints and parameters (default: energy)
set -euo pipefail

ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
SERIAL="${VCONSOLE_SERIAL:-emulator-5556}"
PORT="${VCONSOLE_PORT:-18088}"
BASE="http://127.0.0.1:$PORT"
PIDFILE="${VCONSOLE_PIDFILE:-${TMPDIR:-/tmp}/vconsole-hb-$SERIAL.pid}"
HB_CLIENT="cli-heartbeat"

usage() { echo "usage: $0 hb-start|hb-stop|gear <Park|Reverse|Neutral|Drive>|drive <kph>|stop|charge <on|off>|odo <km>|soc <pct>|state|catalog [domain]" >&2; exit 2; }

forward() { "$ADB" -s "$SERIAL" forward "tcp:$PORT" tcp:8088 >/dev/null; }

# GET helper: prints the body, fails with a hint when the console is unreachable.
get() {
  curl -s -m 5 "$BASE$1" || { echo "console unreachable at $BASE$1 (emulator $SERIAL running? adb forward ok?)" >&2; return 1; }
}

# Reads one dotted path from GET /vehicle/state, e.g. state_value gear.actual.value
state_value() {
  get /vehicle/state | python3 -I -c '
import sys, json
node = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    node = node.get(key) if isinstance(node, dict) else None
print(node)' "$1" 2>/dev/null || echo unknown
}

# PUT helper: warns when no heartbeat is alive (the console then ignores the write), prints the reply.
put() {
  local hb; hb="$(state_value browser.active)"
  [ "$hb" = "True" ] || echo "warning: heartbeat not active (browser.active=$hb); the console will ignore this write. Run: $0 hb-start" >&2
  curl -s -m 5 -X PUT "$BASE$1"; echo
}

case "${1:-state}" in
  hb-start)
    forward
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then echo "heartbeat already running (pid $(cat "$PIDFILE"))"; exit 0; fi
    ( while true; do curl -s -m 2 -X POST "$BASE/browser/session?client=$HB_CLIENT" >/dev/null || true; sleep 1; done ) &
    echo $! > "$PIDFILE"; sleep 2
    echo "heartbeat started (pid $(cat "$PIDFILE"), browser.active=$(state_value browser.active))"
    echo "reminder: finish the session with '$0 gear Park' and '$0 stop' before '$0 hb-stop'" ;;
  hb-stop)
    forward
    gear_now="$(state_value gear.actual.value)"
    if [ -f "$PIDFILE" ]; then kill "$(cat "$PIDFILE")" 2>/dev/null || true; rm -f "$PIDFILE"; fi
    curl -s -m 2 -X POST "$BASE/browser/session?client=$HB_CLIENT&closing=1" >/dev/null || true
    echo "heartbeat stopped (gear left at $gear_now)"
    [ "$gear_now" = "Park" ] || echo "warning: the console keeps the last gear ($gear_now). Restore with: $0 hb-start && $0 gear Park && $0 stop && $0 hb-stop" >&2 ;;
  gear)
    forward; put "/vehicle/gear?gear=${2:?Park|Reverse|Neutral|Drive}" ;;
  drive)
    forward; put "/vehicle/controls?speed=${2:?kph}&accelerator=20&brake=0" >/dev/null; echo "speed ${2} km/h" ;;
  stop)
    forward; put "/vehicle/controls?speed=0&accelerator=0&brake=60" >/dev/null; echo "speed 0 km/h (brake)" ;;
  charge)
    forward
    case "${2:-}" in
      on)
        put "/vehicle/ev-battery?field=charge_port_open&value=true"
        put "/vehicle/ev-battery?field=charge_switch&value=true"
        echo "charge on (charge port open, charge switch on)" ;;
      off)
        put "/vehicle/ev-battery?field=charge_switch&value=false"
        put "/vehicle/ev-battery?field=charge_port_open&value=false"
        echo "charge off (charge switch off, charge port closed)" ;;
      *) usage ;;
    esac ;;
  odo)      # odo <km>: PERF_ODOMETER card (shown by the app as 차량 누적 km through the Pleos Vehicle SDK)
    forward; curl -s -m 5 -X PUT "$BASE/vehicle/odometer?traveled_distance_km=${2:?km}" >/dev/null; echo "odometer ${2} km" ;;
  soc)      # soc <pct>: EV battery state of charge card
    forward; curl -s -m 5 -X PUT "$BASE/vehicle/ev-battery?field=soc&value=${2:?pct}" >/dev/null; echo "soc ${2}%" ;;
  state)
    forward
    get /vehicle/state | python3 -I -c '
import sys, json
s = json.load(sys.stdin)
g = s.get("gear", {}); c = s.get("controls", {}); e = s.get("ev_battery", {}); b = s.get("browser", {})
print("gear=%s speed=%s active=%s bridge=%s" % (g.get("actual", {}).get("value"), c.get("speed_kph"), c.get("emulator_active"), g.get("bridge_ready")))
print("ev_battery: charging_door_opened=%s charging_activated=%s state_of_charge=%s ready_to_charge=%s" % (e.get("charging_door_opened"), e.get("charging_activated"), e.get("state_of_charge"), e.get("ready_to_charge")))
print("heartbeat: browser.active=%s clients=%s timeout_ms=%s" % (b.get("active"), b.get("clients"), b.get("timeout_ms")))'
    "$ADB" -s "$SERIAL" shell dumpsys car_service --services CarDrivingStateService | grep -E "Current Driving State" || true
    "$ADB" -s "$SERIAL" shell dumpsys car_service --services CarUxRestrictionsManagerService | grep -m1 "UXR:" || true ;;
  catalog)
    forward
    get /vehicle/catalog | python3 -I -c '
import sys, json
domain = sys.argv[1]
for f in json.load(sys.stdin).get("features", []):
    if domain != "all" and f.get("domain") != domain:
        continue
    ctl = f.get("control") or {}; cp = f.get("car_property") or {}
    params = "&".join("%s=%s" % kv for kv in (ctl.get("params") or {}).items())
    query = "&".join(p for p in (params, "%s=<value>" % ctl.get("field") if ctl.get("field") else "") if p)
    print("%-28s PUT %s?%s  -> %s.%s  [%s %s]" % (f.get("id"), f.get("write_endpoint"), query, f.get("state_key"), f.get("state_path"), cp.get("name"), cp.get("hex_id")))' "${2:-energy}" ;;
  *) usage ;;
esac
