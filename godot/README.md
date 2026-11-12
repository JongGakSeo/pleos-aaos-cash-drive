# KiloMoa Godot renderer

Renderer-only 3D scene for the KiloMoa home screen. The native Android host owns vehicle signals, UXR policy, lifecycle, reward state and permission checks. `ThemeStage` receives only an explicit request and never reads vehicle speed, gear, odometer, network or account data. It draws one small clay diorama per theme (`SCENES`: a round base with rigged, animated characters and static props from `assets/varco/`) over a plain studio backdrop (`ThemeBackdrop`: a vertical gradient in the theme's colours, a glow behind the model, the base's shadow). There are no particles and no text in the scene. This is not a complete Godot app or a claim of AAOS/Pleos approval.

Design: `planning/specs/2026-11-11-3d-themes-design.md` in the team's working repository (it replaces the 2D vector horse of `2026-11-09-visual-overhaul-design.md` §2-4; the request contract below is the same one). How the models were made: `assets/varco/README.md`.

## Request contract

`apply_request({skinId, mode, motion, theme, viewport: {width, height}, reducedMotion, speedKph, minuteOfDay, metersToNext, milestone, generationId})` returns `true` when applied.

- `skinId` is `horse` (a pony on a meadow), `mine` (a miner at a crystal rock) or `mining` (two young miners at a coin), the session's theme (core `ThemeIds`). `mode` is `STATIC|PREVIEW`, `theme` is `day|night`. Viewport dimensions must be positive finite numbers. Generation IDs must increase; stale requests are ignored. A theme's models load on the first request that asks for it (the others load on a worker thread after the first frame).
- `motion` is optional and defaults to `stand`.
  - `stand`: with `STATIC`, the still pose (the pony standing, the miners on the first frame of their idle) and no frames. With `PREVIEW`, which the stage reaches only after a reveal, the characters play their idle clip.
  - `run`: the theme works. The pony walks, trots or canters in place (below 20, below 50, from 50 km/h; it stands below 3) while the meadow turns under it at the stride's ground speed times 0.7, so the hooves hardly slide. The miners swing their pickaxes; lantern, cart and wheelbarrow rock a little and the camera drifts 5 deg. Clips play 0.95 to 1.15 times as fast for 0 to 60 km/h.
  - `reveal`: the arrival, 1.2 s. The speed the theme was working at falls on `(1 - t)^3`, so the pony drops through its gaits to a stand and the meadow stops; the miners blend into their idle. Then the motion becomes `stand` and the idle plays. A reveal that cannot animate (`STATIC`, reduced motion, no safety grant, a safety lock half way) shows that end state at once. Republishing `reveal` during or after that window continues or keeps the finished pose; any other motion arms the next reveal.
- `speedKph` is optional, must be finite and non-negative, and is clamped to 0-200; clip speed saturates at 60.
- `minuteOfDay` is optional (whole number 0-1439, default 720). With `theme` `day`, 990-1110 (16:30-18:30) draws the dusk palette and warms the lights (`FFE4CC`); `night` is night at any minute and cools them (`C9D2E8`).
- `metersToNext` is optional (0-1000, default 1000): metres to the next ticket. On the meadow a milestone (a post with an amber pill-shaped plate, no text) stands on the inner edge of the track, glued to the turning meadow. The meadow's turn follows the hooves, not the distance, so the sign is planted only shortly before the ticket: when the time left (`metersToNext` run down with the speed since the request, divided by the speed) is no more than the meadow needs to turn 110 deg, at the angle that brings it beside the pony at that moment. It turns on with the meadow and is removed 150 deg past the pony. After an arrival it stands 38 deg behind the tail. The mine and coin themes have no milestone.
- `milestone` is optional (boolean, default `true`). The host sends `false` once today's tickets are all issued: no next sign is planted, while a sign already passing turns away as usual.
- Frames are processed only when `mode` is `PREVIEW`, the host granted `set_safety_allowed(true)`, `reducedMotion` is false and the stage is visible. Malformed requests, unknown skins, unknown motions and out-of-range fields fail safely to `STATIC` and `stand`. The host calls `set_safety_allowed(false)` on every safety restriction, disconnect or hidden lifecycle state; that immediately resets the pose and stops the idle. The meadow keeps the angle it turned to, so a stop at a light does not jump the ground.

## Layout

The viewport is the host's scene area alone; the navigation bar and the meter sit beside it and nothing native covers it. On a wide panel it is a column beside the meter (about 1010x1122 on the emulator), on a narrow panel a band above it (about 840x702); a viewport wider than 1.08 times its height is the band (`layout_for`). The diorama stands on the lower part of either: it grows until it takes 94% of the width or 56% of the height, and the camera frustum is shifted so the front foot of the base lands on 93% of the height. The sky above stays free for the host's reward cards, and the native stills continue the backdrop's top colour upward.

Lighting is an ambient term, a shadowless light from straight above and one shadow-casting key light (0.35 + 0.25 + 0.5 by day), so the white clay does not burn out.

## Run tests

Run the import once on a fresh checkout so the models are imported.

```sh
.tools/godot/Godot.app/Contents/MacOS/Godot --headless --path godot --import
.tools/godot/Godot.app/Contents/MacOS/Godot --headless --path godot --script res://tests/test_renderer.gd
```

The test checks valid requests, the narrow viewport, stale generation rejection, invalid skin, NaN, unknown-motion and out-of-range field fail-safe behaviour, the optional-field defaults, the day, dusk and night looks, speed/rate bounds, explicit safety gating, the host guard, reduced motion and the hidden-view pause, then the three motions on the meadow (gaits by speed, the turning base, a static stand that keeps the meadow's angle, an arrival that eases out into the idle and latches, the end state for a reveal that cannot animate), the milestone (planted ahead of the pony, passing it when the ticket is due, removed after it turned away, absent at the daily cap, standing behind the tail after an arrival), the mine and coin themes (every worker holding a pickaxe, swinging out of step, idling after an arrival) and the framing of all three themes at both scene sizes.

## Tools

`tools/render_stills.gd` renders the stills for every theme, day and night: `wide` (1010x1122, the scene column) and `narrow` (840x702, the scene band) at rest, shown over the engine surface while it is hidden or starting; `panorama` (1682x800), an arrival's end, that the lock and the permission gate stand on their bottom edge on a landscape window; and `thumb` (640x400), the theme's picture on the settings page. It renders off screen in one launch, so the app can be started hidden:

```sh
open -g -j -n -W -a .tools/godot/Godot.app --args --path "$PWD/godot" --script res://tools/render_stills.gd -- /tmp/stills
for f in /tmp/stills/*.png; do cwebp -q 90 "$f" -o "android/app/src/home/res/drawable-nodpi/scene_$(basename "$f" .png | tr - _).webp"; done
```

`tools/look.gd` saves frames of a theme in motion from any camera (close-ups for checking poses and grips), the same way. `tools/movie.tscn` records the run motion with Movie Maker (it opens a window).

## Export

`android/app/build.gradle.kts` exports the pack with the `Android PCK` preset (`--export-pack`) after an import, into `app/build/generated/godotAssets/cash_drive.pck`, for the debug and showcase variants. The same command by hand:

```sh
.tools/godot/Godot.app/Contents/MacOS/Godot --headless --path godot --export-pack "Android PCK" /tmp/cash_drive.pck
```

The pack is about 31 MB (three characters and eight props with 1024 px textures). The native host must package and load the PCK, pass only approved values, and own every safety decision.
