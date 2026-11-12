#!/usr/bin/env python3 -I
"""Build the make_demo.py plan from a take's events.jsonl (written by tools/record-demo.sh).

Usage: python3 -I tools/video/plan_from_events.py <take_dir> [out_plan.json]
Cuts follow the master repository's submission/video-shotlist.md (A. 제출용). Times are seconds on the recording
timeline (record-start = 0). Speed-ups are applied only to the real-time stretches and are captioned.
"""
import json, os, sys

take_dir = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(take_dir, "plan.json")
events = {}
for line in open(os.path.join(take_dir, "events.jsonl"), encoding="utf-8"):
    line = line.strip()
    if not line: continue
    e = json.loads(line)
    events.setdefault(e["event"], e["t_ms"] / 1000.0)

def t(name, delta=0.0):
    # Event marks may carry a trailing note, e.g. "launch (permission dialog expected)".
    key = name if name in events else next((k for k in events if k.startswith(name)), None)
    if key is None: raise SystemExit(f"missing event: {name}")
    return round(events[key] + delta, 2)

REAL = "실시간 차량 신호 · Pleos Connect"
segments = [
    # The gate's system dialog, cut on the tap. The gate hands over to the home in a fresh task and the
    # launcher shows an empty app area for about a second in between, so the welcome starts 2.2 s after it.
    {"start": t("permission-allow", -4.0), "end": t("permission-allow", 0.3), "speed": 1.0,
     "captions": ["차량 속도 권한이에요. AAOS가 위치 그룹으로 분류해 이렇게 보여요", "앱은 위치를 읽지 않아요"]},
    # Welcome: the home, the "환영 시청권 1장" card and the theater opening (1x), the 15 s clip (3x), then
    # the completion with its count-up and toast (1x).
    {"start": t("permission-allow", 2.2), "end": t("permission-allow", 5.0), "speed": 1.0,
     "captions": ["첫 시청권은 환영 선물이에요", "주차 중에만 자동으로 재생해요"]},
    {"start": t("permission-allow", 5.0), "end": t("permission-allow", 18.6), "speed": 3.0,
     "captions": ["팀이 직접 만든 15초 교통안전 브리핑 · 자막은 앱이 그려요", "3배속"]},
    {"start": t("permission-allow", 18.6), "end": t("permission-allow", 22.0), "speed": 1.0,
     "captions": ["끝까지 보면 10 크레딧이 자동으로 쌓여요"]},
    # Driving: the gallop at 1x, the middle of the kilometre fast, the milestone and the first ticket at 1.5x.
    {"start": t("drive-60", -0.3), "end": t("drive-60", 3.5), "speed": 1.0,
     "captions": ["달린 만큼 쌓여요 · 1 km마다 시청권 1장(하루 10장)", REAL]},
    {"start": t("drive-60", 3.5), "end": t("drive-60", 55.0), "speed": 6.0,
     "captions": ["주행 중엔 영상 없이 말만 달려요", "6배속"]},
    {"start": t("drive-60", 56.0), "end": t("drive-60", 63.0), "speed": 1.5,
     "captions": ["1 km 이정표를 지나면 시청권이 한 장 쌓여요"]},
    {"start": t("stop-waiting", 0.0), "end": t("stop-waiting", 4.0), "speed": 1.0,
     "captions": ["신호 대기에서는 재생하지 않아요", "P단 · 0 km/h · UXR 영상 허용 · 속도 권한, 넷 다 맞아야 열려요"]},
    {"start": t("gear-Park-reveal"), "end": t("gear-Park-reveal", 7.0), "speed": 1.0,
     "captions": ["주차하면 오늘 달린 만큼 보상이 열려요", REAL]},
    {"start": t("depart-while-playing", -1.0), "end": t("park-resume", 4.0), "speed": 1.0,
     "captions": ["출발하면 바로 멈추고 위치를 기억해요", "다시 주차하면 이어서 · 같은 시청권은 한 번만 적립"]},
    # The charge bonus follows the credit mark within a second, so the credit segment ends where the charge
    # segment begins instead of overlapping it.
    {"start": t("briefing-2-credited", -5.0), "end": t("charge-on", -0.3), "speed": 1.0,
     "captions": ["완주하면 10 크레딧이 자동으로 쌓여요"]},
    {"start": t("charge-on", -0.3), "end": t("charge-on", 6.0), "speed": 1.0,
     "captions": ["충전 중이면 시청권 +1 (주차할 때마다 한 번)", REAL]},
    {"start": t("credits-page", -0.5), "end": t("credits-page", 4.5), "speed": 1.0,
     "captions": ["모은 크레딧은 매달 인카 페이먼트·블루멤버스 포인트로, 또는 교통안전 기부로"]},
    {"start": t("settings-page", -0.3), "end": t("night-on", 2.0), "speed": 1.0,
     "captions": ["주행 중 움직임은 설정에서 끌 수 있어요 · 브리핑 편성도 여기서 봐요"]},
    {"start": t("night-dashboard", -1.0), "end": t("night-dashboard", 3.0), "speed": 1.0,
     "captions": ["밤에는 어두운 화면으로 바뀌어요"]},
]
if os.environ.get("PLAN_PT") == "1":
    # 30-second PT opening: drive, wait, reveal, interrupt/resume, credit, end card.
    segments = [
        {"start": t("stop-waiting", -14.0), "end": t("stop-waiting"), "speed": 3.0,
         "captions": ["달린 만큼 쌓여요 · 1 km = 시청권 1장", "실시간 차량 신호 · 3배속"]},
        {"start": t("stop-waiting"), "end": t("stop-waiting", 4.0), "speed": 1.0,
         "captions": ["신호 대기에서는 영상도 적립도 없어요"]},
        {"start": t("gear-Park-reveal"), "end": t("gear-Park-reveal", 6.0), "speed": 1.0,
         "captions": ["P단 · 0 km/h · 영상 허용 · 속도 권한, 넷이 맞을 때만 열려요", "하나라도 UNKNOWN이면 닫혀요"]},
        {"start": t("depart-while-playing", -1.0), "end": t("park-resume", 4.0), "speed": 1.0,
         "captions": ["출발하면 즉시 중단 · 되감기 없음 · 재적립 없음"]},
        {"start": t("briefing-2-credited", -5.0), "end": t("charge-on", -0.3), "speed": 1.0,
         "captions": ["완주 시 자동 적립 1회"]},
    ]

plan = {
    "take": os.path.join(take_dir, "take.webm"),
    "size": [1920, 1080],
    # The AAOS dock (the bottom 140 px of the 1440 px display) gives way to the caption strip.
    "layout": "band", "crop_h": 1300,
    "icon": "tools/video/kilomoa-icon.png",
    "title": {"seconds": 2 if os.environ.get("PLAN_PT") == "1" else 3, "lines": ["킬로모아", "달린 만큼 쌓이고, 주차하면 받아요", "Pleos Connect · 차량 신호 실시간 연동"]},
    "end": {"seconds": 3 if os.environ.get("PLAN_PT") == "1" else 4, "lines": ["킬로모아", "달린 만큼 쌓이고, 주차하면 받아요", "Pleos Vehicle SDK · NaviHelper · Gleo · AAOS UXR", "교통안전 브리핑 근거: 도로교통법(law.go.kr)"]
        # The PSA is credited only when the take shows it (a -Pkilomoa.includePsa=true build, see record-demo.sh).
        + (["영상 발췌: 경기북부경찰청 · 올바른 우회전 방법은?"] if "psa-included" in events else [])},
    "segments": segments,
}
json.dump(plan, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
total = plan["title"]["seconds"] + plan["end"]["seconds"] + sum((s["end"] - s["start"]) / s["speed"] for s in segments)
print(f"plan written: {out} (estimated {total:.1f} s)")
