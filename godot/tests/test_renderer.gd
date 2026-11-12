extends SceneTree

## Headless contract tests for the 3D stage: the request contract, the safety gate, the three motions on
## the three themes, the meadow's milestone and the framing.
## Run: Godot --headless --path godot --import   (once, so the models are imported)
##      Godot --headless --path godot --script res://tests/test_renderer.gd
const SCENE := preload("res://main.tscn")
var failures := 0

func _initialize() -> void:
	call_deferred("run")

func _check(condition: bool, message: String) -> void:
	if not condition:
		failures += 1
		push_error("FAIL: " + message)
	else:
		print("PASS: " + message)

func _request(generation: int, overrides: Dictionary = {}) -> Dictionary:
	var value := {
		"skinId": "horse",
		"mode": "STATIC",
		"theme": "day",
		"viewport": {"width": 1010.0, "height": 1122.0},
		"reducedMotion": false,
		"generationId": generation,
	}
	for key in overrides:
		value[key] = overrides[key]
	return value

## PREVIEW request carrying the run motion: the shape the host sends while driving.
func _preview(generation: int, overrides: Dictionary = {}) -> Dictionary:
	var value := _request(generation, {"mode": "PREVIEW", "motion": "run"})
	for key in overrides:
		value[key] = overrides[key]
	return value

func _anims(root) -> Array:
	return root._clips.map(func(clip): return String(clip["player"].current_animation))

## Rest: every clip on the first frame of its still pose, props upright, the camera straight.
func _at_rest(root) -> bool:
	var still := is_zero_approx(root._sway) and is_zero_approx(root._ground_speed)
	for clip in root._clips:
		still = still and clip["player"].current_animation == root._still(clip) and is_zero_approx(clip["player"].current_animation_position)
	for part in root._parts:
		still = still and part["node"].transform.is_equal_approx(part["base"])
	return still

## Ring angle of the meadow's sign in degrees from the pony: negative ahead of it, positive behind the tail.
func _sign_deg(root) -> float:
	return rad_to_deg(root._ring_angle(atan2(root._sign.position.x, root._sign.position.z)))

func run() -> void:
	# The scene column of a wide panel (a headless root starts at 64x64).
	get_root().size = Vector2i(1010, 1122)
	var root = SCENE.instantiate()
	get_root().add_child(root)
	await process_frame
	_check(root.skin_id == "horse" and not root.is_processing(), "starts on the horse theme without animating")
	_check(root._clips.size() == 1 and root._turn != null and root._sign != null and root._backdrop != null, "the meadow holds the rigged pony, a turning base and a milestone")
	_check(root.find_children("*", "CPUParticles3D", true, false).is_empty() and root.find_children("*", "GPUParticles3D", true, false).is_empty(), "the scene has no particles")
	_check(root.apply_request(_request(1)), "valid request is accepted")
	root._process(1.0)
	_check(root.phase == 0.0 and not root.is_processing() and _at_rest(root), "static phase stays fixed and does not process frames")
	_check(root.apply_request(_request(2, {"theme": "night"})) and root._backdrop.get("_palette_name") == "night"
		and root._key_light.light_color.is_equal_approx(ThemeStage.KEY_COLOR * Color("C9D2E8")), "night theme draws the night palette and cools the light")
	_check(root.apply_request(_request(3)) and root._backdrop.get("_palette_name") == "day"
		and root._key_light.light_color.is_equal_approx(ThemeStage.KEY_COLOR), "day theme at noon draws the day palette")

	_check(root.apply_request(_request(20, {"viewport": {"width": 840, "height": 702}})), "narrow layout request is accepted")
	_check(not root.apply_request(_request(1, {"theme": "night"})), "stale generation is rejected")
	_check(root.theme_name == "day", "stale request does not replace the current look")
	_check(not root.apply_request(_request(21, {"skinId": "gpu"})), "removed gpu theme is rejected")
	_check(root.mode == "STATIC" and not root.is_processing(), "invalid theme fails safe to static")
	_check(root.skin_id == "horse", "invalid theme keeps the current skin")
	_check(not root.apply_request(_request(24, {"viewport": {"width": NAN, "height": 810}})), "NaN viewport is rejected")

	# Optional fields of the 2026-11-09 overhaul: minuteOfDay (0..1439) and metersToNext (0..1000).
	_check(root.apply_request(_request(25, {"mode": "PREVIEW", "motion": "stand"})) and root.mode == "PREVIEW"
		and root.minute_of_day == 720 and is_equal_approx(root.meters_to_next, 1000.0), "request_without_new_fields_uses_defaults")
	_check(root.apply_request(_request(26, {"minuteOfDay": 1000, "metersToNext": 420})) and root.minute_of_day == 1000
		and is_equal_approx(root.meters_to_next, 420.0), "new fields reach the stage")
	_check(root._backdrop.get("_palette_name") == "dusk" and root._key_light.light_color.is_equal_approx(ThemeStage.KEY_COLOR * Color("FFE4CC")), "day theme at 16:40 draws dusk and warms the light")
	_check(not root.apply_request(_request(27, {"minuteOfDay": 1440})) and root.mode == "STATIC", "bad_minute_of_day_falls_static")
	_check(not root.apply_request(_request(28, {"minuteOfDay": 12.5})), "fractional minute is rejected")
	_check(not root.apply_request(_request(29, {"minuteOfDay": -1})), "negative minute is rejected")
	_check(not root.apply_request(_request(30, {"metersToNext": 1001})), "metersToNext beyond a kilometre is rejected")
	_check(not root.apply_request(_request(31, {"metersToNext": -1})), "negative metersToNext is rejected")
	_check(not root.apply_request(_request(32, {"metersToNext": "far"})), "non-numeric metersToNext is rejected")
	_check(not root.apply_request(_request(34, {"milestone": 1})), "non-boolean milestone is rejected")
	_check(root.apply_request(_request(35, {"minuteOfDay": 720.0, "metersToNext": 300.0})) and root.minute_of_day == 720, "JSON floats are accepted for whole minutes")

	_check(root.apply_request(_preview(40)), "preview request is accepted")
	_check(not root.is_processing(), "preview does not animate before a safety grant")
	root.set_safety_allowed(true)
	_check(root.is_processing(), "preview animates after a safety grant")
	root._process(0.5)
	_check(root.phase > 0.0, "preview phase advances while allowed")
	_check(root.apply_request(_preview(100, {"speedKph": 0})) and is_equal_approx(root.motion_rate, ThemeStage.MIN_RATE), "stopped speed uses the slowest rate")
	_check(root.apply_request(_preview(101, {"speedKph": 30})) and is_equal_approx(root.motion_rate, (ThemeStage.MIN_RATE + ThemeStage.MAX_RATE) * 0.5), "motion follows speed")
	_check(root.apply_request(_preview(102, {"speedKph": 200})) and is_equal_approx(root.motion_rate, ThemeStage.MAX_RATE), "speeds above the cap add no motion")
	_check(not root.apply_request(_preview(103, {"speedKph": NAN})), "NaN speed is rejected")
	_check(root.mode == "STATIC" and not root.is_processing(), "NaN speed fails safe to static")
	_check(not root.apply_request(_preview(104, {"speedKph": -1})), "negative speed is rejected")
	root.set_safety_allowed(true)
	_check(root.apply_request(_preview(105, {"speedKph": 40})) and root.is_processing(), "preview resumes for the next check")
	root._process(0.5)
	root.set_safety_allowed(false)
	_check(root.mode == "STATIC" and not root.is_processing() and root.phase == 0.0, "safety lock stops motion and resets phase")
	_check(_at_rest(root), "safety lock restores the rest pose")

	root.set_safety_allowed(true)
	_check(not root.is_processing(), "safety grant alone does not resume after a lock")
	_check(root.apply_request(_preview(106)) and root.is_processing(), "preview resumes only with a new host request")
	root.set_host_guard(func() -> bool: return false)
	root._process(0.1)
	_check(not root.is_processing() and root.mode == "STATIC", "revoked host guard stops the next frame")
	root.set_host_guard(Callable())
	root.set_safety_allowed(true)
	_check(root.apply_request(_preview(107, {"reducedMotion": true})), "reduced-motion request is accepted")
	_check(not root.is_processing(), "reduced motion disables animation")
	root.apply_request(_preview(108))
	root.visible = false
	_check(not root.is_processing() and root.mode == "STATIC", "hidden stage stops and returns to static")
	root.visible = true
	_check(not root.is_processing(), "becoming visible does not resume without a new host request")

	# Motions: stand | run | reveal. The pony's gait follows the speed while its meadow turns under it.
	root.set_safety_allowed(true)
	var turned: float = root._turn.rotation.y
	_check(root.apply_request(_preview(118, {"speedKph": 40, "metersToNext": 500})) and root.is_processing(), "run request processes")
	for frame in 30:
		root._process(1.0 / 30.0)
	_check(_anims(root) == ["Trot"] and root._turn.rotation.y != turned and root._ground_speed > 0.0, "run at 40 km/h trots while the meadow turns")
	_check(root.apply_request(_preview(119, {"speedKph": 10})), "a slower run is accepted")
	root._process(0.1)
	_check(_anims(root) == ["Walk"], "run at 10 km/h walks")
	_check(root.apply_request(_preview(120, {"speedKph": 80})), "a faster run is accepted")
	root._process(0.1)
	_check(_anims(root) == ["Canter"], "run at 80 km/h canters")
	turned = root._turn.rotation.y

	_check(root.apply_request(_request(121, {"motion": "stand"})) and not root.is_processing() and _at_rest(root), "static stand rests the pose and stops")
	_check(is_equal_approx(root._turn.rotation.y, turned), "stopping keeps the meadow where it turned to")
	root.set_safety_allowed(true)
	_check(root.apply_request(_request(123, {"mode": "PREVIEW"})), "preview without a motion field is accepted")
	_check(root.get("motion") == "stand", "missing motion defaults to stand")
	_check(not root.apply_request(_request(124, {"mode": "PREVIEW", "motion": "gallop"})), "unknown motion is rejected")
	_check(root.mode == "STATIC" and root.get("motion") == "stand" and not root.is_processing(), "unknown motion fails safe")
	_check(not root.apply_request(_request(125, {"mode": "PREVIEW", "motion": 2})), "non-string motion is rejected")

	# Arrival from a run: the gait eases out over the reveal, then the pony idles and the sign stands behind it.
	root.set_safety_allowed(true)
	root.apply_request(_preview(126, {"speedKph": 40}))
	for frame in 30:
		root._process(1.0 / 30.0)
	_check(root.apply_request(_request(127, {"mode": "PREVIEW", "motion": "reveal"})) and root.is_processing() and root.get("motion") == "reveal", "reveal request processes")
	root._process(0.05)
	_check(_anims(root) == ["Trot"] and root._ground_speed > 0.0, "a running pony is still striding at the start of the arrival")
	_check(root.apply_request(_request(128, {"mode": "PREVIEW", "motion": "reveal"})) and root.reveal_time > 0.0, "reveal_latches: a repeated reveal request continues the same reveal")
	for frame in 40:
		root._process(1.0 / 30.0)
	_check(root.get("motion") == "stand" and root.is_processing() and _anims(root) == ["Idle"], "reveal finishes into stand and the parked idle")
	_check(is_zero_approx(root._ground_speed), "the meadow has stopped by the end of the arrival")
	_check(root._sign_state == "stop" and root._sign.visible and absf(_sign_deg(root) - ThemeStage.STOP_DEG) < 1.0, "the milestone stands behind the tail after arriving")
	var idle_before: float = root._clips[0]["player"].current_animation_position
	root._process(0.5)
	_check(root._clips[0]["player"].current_animation_position != idle_before, "the pony idles on the stage's frames after arriving")
	_check(root.apply_request(_request(129, {"mode": "PREVIEW", "motion": "reveal"})) and root.get("motion") == "stand", "finished reveal does not replay on the same host request")
	_check(root.apply_request(_preview(130, {"metersToNext": 990, "speedKph": 40})) and root.get("motion") == "run", "run after a reveal works again")
	for frame in 150:
		root._process(1.0 / 30.0)
	_check(root._sign_state == "", "departing turns the stop sign away and removes it")
	_check(root.apply_request(_request(131, {"motion": "reveal"})) and not root.is_processing() and root._sign_state == "stop" and is_equal_approx(root._sign.scale.x, 1.0), "a reveal that cannot animate shows its end state at once")
	root.set_safety_allowed(false)
	_check(root.get("motion") == "stand" and not root.is_processing() and _at_rest(root), "safety lock rests the pose")
	root.queue_free()

	await _test_milestone()
	await _test_workers()
	await _test_framing()
	print("Renderer tests complete: %d failure(s)" % failures)
	quit(1 if failures else 0)

## The sign is planted shortly before the ticket and passes the pony when the distance runs out.
func _test_milestone() -> void:
	var root = SCENE.instantiate()
	get_root().add_child(root)
	await process_frame
	root.set_safety_allowed(true)
	root.apply_request(_preview(1, {"speedKph": 40, "metersToNext": 900}))
	for frame in 45:
		root._process(1.0 / 30.0)
	_check(root._sign_state == "" and not root._sign.visible, "no sign while the next ticket is far")
	# 60 m at 40 km/h is 5.4 s away: further than the meadow turns in SIGN_LEAD, so the sign appears later.
	root.apply_request(_preview(2, {"speedKph": 40, "metersToNext": 60}))
	var planted_at := -1.0
	var passed_at := -1.0
	var elapsed := 0.0
	for frame in 400:
		root._process(1.0 / 60.0)
		elapsed += 1.0 / 60.0
		if planted_at < 0.0 and root._sign_state == "next":
			planted_at = elapsed
			_check(_sign_deg(root) < 0.0 and _sign_deg(root) >= -ThemeStage.SIGN_LEAD - 1.0, "the next sign is planted ahead of the pony")
		if planted_at >= 0.0 and passed_at < 0.0 and _sign_deg(root) >= 0.0:
			passed_at = elapsed
	_check(planted_at > 0.5 and absf(passed_at - 5.4) < 0.3, "the sign passes the pony when the ticket is due (%.2f s, due 5.40 s)" % passed_at)
	_check(root._sign_state == "next" and root._sign.visible, "a sign that just passed is still on the meadow, behind the pony")
	for frame in 300:
		root._process(1.0 / 60.0)
	_check(root._sign_state == "", "a passed sign turns away with the meadow and is removed")
	root.apply_request(_preview(3, {"speedKph": 40, "metersToNext": 20, "milestone": false}))
	for frame in 60:
		root._process(1.0 / 30.0)
	_check(root._sign_state == "", "no next sign once today's tickets are all issued")
	root.queue_free()

## Mine and coin themes: the miners hold their pickaxes, swing them while running and idle after arriving.
func _test_workers() -> void:
	var root = SCENE.instantiate()
	get_root().add_child(root)
	await process_frame
	root.set_safety_allowed(true)
	for spec in [["mine", 1], ["mining", 2]]:
		var skin: String = spec[0]
		_check(root.apply_request(_preview(root.generation_id + 1, {"skinId": skin, "speedKph": 40, "metersToNext": 10})) and root.skin_id == skin, "%s request is accepted" % skin)
		var holders: Array = root._model.find_children("RightHandTool", "BoneAttachment3D", true, false)
		_check(holders.size() == spec[1] and holders.all(func(h): return h.bone_name == "RightHand" and h.get_child_count() == 1), "%s: every worker holds a pickaxe in the right hand" % skin)
		_check(root._turn == null and root._sign == null, "%s has no turning base and no milestone" % skin)
		for frame in 30:
			root._process(1.0 / 30.0)
		_check(_anims(root).all(func(anim): return anim == "Hammer") and root._sway > 0.9, "%s: the workers swing while running" % skin)
		if spec[1] == 2:
			_check(not is_equal_approx(root._clips[0]["player"].current_animation_position, root._clips[1]["player"].current_animation_position), "the two workers do not swing in unison")
		root.apply_request(_request(root.generation_id + 1, {"skinId": skin, "mode": "PREVIEW", "motion": "reveal"}))
		for frame in 45:
			root._process(1.0 / 30.0)
		_check(root.get("motion") == "stand" and _anims(root).all(func(anim): return anim == "Idle") and is_zero_approx(root._sway), "%s: the workers idle after arriving" % skin)
		root.apply_request(_request(root.generation_id + 1, {"skinId": skin}))
		_check(not root.is_processing() and _at_rest(root), "%s: static stand rests the pose" % skin)
	root.queue_free()

## Both scene sizes: the base stands at BASE_BOTTOM of the height and the model keeps to the lower part.
func _test_framing() -> void:
	for frame in [Vector2i(1010, 1122), Vector2i(840, 702)]:
		get_root().size = frame
		var root = SCENE.instantiate()
		get_root().add_child(root)
		await process_frame
		var view := Vector2(frame)
		_check(ThemeStage.layout_for(view) == ("narrow" if frame.x == 840 else "wide"), "%dx%d is the %s layout" % [frame.x, frame.y, ThemeStage.layout_for(view)])
		for skin in ThemeStage.SKINS:
			root.apply_request(_request(root.generation_id + 1, {"skinId": skin, "viewport": {"width": frame.x, "height": frame.y}}))
			var size: Vector3 = root._model_size
			var foot: Vector2 = root._camera.unproject_position(Vector3(0, 0, size.z * 0.5))
			var top: Vector2 = root._camera.unproject_position(Vector3(0, size.y, 0))
			var left: Vector2 = root._camera.unproject_position(Vector3(-size.x * 0.5, 0, 0))
			var right: Vector2 = root._camera.unproject_position(Vector3(size.x * 0.5, 0, 0))
			_check(absf(foot.y / view.y - ThemeStage.BASE_BOTTOM) < 0.005, "%s at %dx%d: the base stands at %.2f of the height" % [skin, frame.x, frame.y, ThemeStage.BASE_BOTTOM])
			_check((foot.y - top.y) / view.y < ThemeStage.MODEL_HEIGHT + 0.1 and top.y / view.y > 0.25, "%s at %dx%d: the sky above the model stays free" % [skin, frame.x, frame.y])
			_check(left.x >= 0.0 and right.x <= view.x and (right.x - left.x) / view.x <= ThemeStage.MODEL_WIDTH + 0.01, "%s at %dx%d: the base fits the width" % [skin, frame.x, frame.y])
		root.queue_free()
		await process_frame
