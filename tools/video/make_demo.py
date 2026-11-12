#!/usr/bin/env python3 -I
"""Cut the demo take into the submission video.

Usage: python3 -I tools/video/make_demo.py <plan.json> <out.mp4>

plan.json:
{
  "take": "planning/video/take-xxxx/take.webm",
  "size": [1920, 1080],
  "title": {"seconds": 3, "lines": ["킬로모아", "달린 만큼 쌓이고, 주차하면 받아요", "..."]},
  "end":   {"seconds": 4, "lines": ["킬로모아", "..."]},
  "segments": [
    {"start": 12.0, "end": 20.5, "speed": 1.0, "captions": ["한 줄", "두 줄"]},
    {"start": 25.0, "end": 85.0, "speed": 4.0, "captions": ["실시간 60초를 압축"]}
  ]
}
Captions are rendered with tools/video/caption.swift (ffmpeg here has no drawtext). The take has no
useful audio, so the output is silent video (H.264, yuv420p, 24 fps).

"layout": "band" (with "crop_h", the take's height kept from the top, e.g. 1300 of 1440) cuts the AAOS dock off
the bottom of the take and puts the captions in a dark strip in its place, so they never sit on the app: the
briefing theater draws its own captions at the stage's lower edge. Without it the captions float over the app.
"""
import json, os, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SWIFT = os.path.join(ROOT, "tools", "video", "caption.swift")

def run(cmd):
    print("+", " ".join(cmd[:12]) + (" ..." if len(cmd) > 12 else ""), flush=True)
    subprocess.run(cmd, check=True)

def render_caption(lines, width, path):
    run(["swift", SWIFT, "caption", path, str(width)] + [l for l in lines if l][:2])

def render_band(lines, width, height, path):
    run(["swift", SWIFT, "band", path, str(width), str(height)] + [l for l in lines if l][:2])

def render_card(lines, width, height, path, icon=None):
    env = dict(os.environ, CARD_ICON=icon) if icon else None
    print("+ swift caption.swift card", path, flush=True)
    subprocess.run(["swift", SWIFT, "card", path, str(width), str(height)] + lines[:6], check=True, env=env)

def main():
    plan = json.load(open(sys.argv[1], encoding="utf-8"))
    out = sys.argv[2]
    take = os.path.join(ROOT, plan["take"]) if not os.path.isabs(plan["take"]) else plan["take"]
    w, h = plan.get("size", [1920, 1080])
    tmp = tempfile.mkdtemp(prefix="kilomoa-video-")
    parts = []
    enc = ["-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "24", "-an"]
    band = plan.get("layout") == "band"
    if band:
        size = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                               "-of", "csv=p=0", take], capture_output=True, text=True, check=True).stdout.strip()
        src_w, src_h = (int(v) for v in size.split(",")[:2])
        crop_h = int(plan.get("crop_h", src_h))
        screen_h = int(round(crop_h * w / src_w / 2)) * 2
        band_h = h - screen_h

    def card(name, spec):
        png = os.path.join(tmp, f"{name}.png"); mp4 = os.path.join(tmp, f"{name}.mp4")
        icon = plan.get("icon")
        render_card(spec["lines"], w, h, png, os.path.join(ROOT, icon) if icon and not os.path.isabs(icon) else icon)
        run(["ffmpeg", "-loglevel", "error", "-y", "-loop", "1", "-t", str(spec["seconds"]), "-i", png,
             "-vf", f"scale={w}:{h},format=yuv420p"] + enc + [mp4])
        return mp4

    if "title" in plan:
        parts.append(card("title", plan["title"]))
    for i, seg in enumerate(plan["segments"]):
        mp4 = os.path.join(tmp, f"seg{i:02d}.mp4")
        speed = float(seg.get("speed", 1.0))
        vf = f"scale={w}:{h}:flags=lanczos,setpts=PTS/{speed}"
        cmd = ["ffmpeg", "-loglevel", "error", "-y", "-ss", str(seg["start"]), "-to", str(seg["end"]), "-i", take]
        caps = [c for c in seg.get("captions", []) if c]
        if band:
            vf = (f"crop={src_w}:{crop_h}:0:0,scale={w}:{screen_h}:flags=lanczos,"
                  f"pad={w}:{h}:0:0:color=0x0E0F11,setpts=PTS/{speed}")
            if caps:
                png = os.path.join(tmp, f"band{i:02d}.png")
                render_band(caps, w, band_h, png)
                cmd += ["-i", png, "-filter_complex", f"[0:v]{vf}[v];[v][1:v]overlay=0:{screen_h}[o]", "-map", "[o]"]
            else:
                cmd += ["-vf", vf]
        elif caps:
            png = os.path.join(tmp, f"cap{i:02d}.png")
            render_caption(caps, w, png)
            # Centre the pill over the app area (right of the 1/3-wide Driving view) and above the system bar.
            xoff = seg.get("caption_x_offset", plan.get("caption_x_offset", 320))
            bottom = seg.get("caption_bottom", plan.get("caption_bottom", 150))
            cmd += ["-i", png, "-filter_complex", f"[0:v]{vf}[v];[v][1:v]overlay=(W-w)/2+{xoff}:H-h-{bottom}[o]", "-map", "[o]"]
        else:
            cmd += ["-vf", vf]
        run(cmd + enc + [mp4])
        parts.append(mp4)
    if "end" in plan:
        parts.append(card("end", plan["end"]))

    lst = os.path.join(tmp, "list.txt")
    with open(lst, "w") as f:
        for p in parts: f.write(f"file '{p}'\n")
    run(["ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", out])
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out],
                           capture_output=True, text=True).stdout.strip()
    print(f"wrote {out} ({float(probe):.1f} s), work dir {tmp}")

if __name__ == "__main__":
    main()
