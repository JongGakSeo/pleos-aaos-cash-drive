# Cash Drive Godot renderer spike

This is a renderer-only G3 spike. The native Android host owns vehicle signals, UXR policy, lifecycle, and permission checks. `ThemeStage` receives only an explicit request and never reads vehicle speed, gear, odometer, network, or account data. It renders three lightweight vector skins (`horse`, `mine`, `gpu`); only an explicitly enabled designer `PREVIEW` can animate. Static mode and reduced motion do not process frames. This is not a complete Godot app or a claim of AAOS/Pleos approval.

## Request contract

`apply_request({skinId: horse|mine|mining, mode, theme, viewport: {width, height}, reducedMotion, generationId})` returns `true` when applied. Skin is `horse|mine|gpu`, mode is `STATIC|PREVIEW`, theme is `day|night`; speed is finite and clamped to 0–120 kph and viewport dimensions must be positive finite numbers. Generation IDs must increase. Stale requests are ignored; malformed requests and unknown skins fail safely to static. The host calls `set_safety_allowed(false)` on every safety restriction, disconnect, or hidden lifecycle state; that immediately resets motion.

## Run tests

Run the import once on a fresh checkout so the theme models load.

```sh
.tools/godot/Godot.app/Contents/MacOS/Godot --headless --path godot --import
.tools/godot/Godot.app/Contents/MacOS/Godot --headless --path godot --script res://tests/test_renderer.gd
```

The test checks valid requests, narrow/wide resize, stale generation rejection, invalid skin and NaN fail-safe behavior, speed/rate bounds, reduced motion, explicit safety gating, and hidden-view pause.

## Export

Use the matching Godot 4.7.2 editor/export templates to export a local PCK. The Android native host must package and load the PCK, pass only approved designer preview values, and own every safety decision. No PCK is bundled in this source-only spike.
# 재현 가능한 PCK 검사

프로젝트 로컬 Godot 4.7.2.stable을 준비한 뒤 루트에서 `sh scripts/godot-check.sh`를 실행한다. 21개 계약 검사를 수행하고 `android/app/build/generated/renderer/cash_drive.pck`를 내보낸다. 이 경로는 build 출력이며 Android 앱에 자동 포함되지 않는다. PCK 생성 성공을 AAR 임베딩 성공으로 해석하지 않는다.
