extends SceneTree

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
		"viewport": {"width": 840.0, "height": 1184.0},
		"reducedMotion": false,
		"generationId": generation,
	}
	for key in overrides:
		value[key] = overrides[key]
	return value

func run() -> void:
	var root = SCENE.instantiate()
	get_root().add_child(root)
	await process_frame
	_check(root.skin_id == "horse" and not root.is_processing(), "starts on the horse theme without animating")
	_check(root.apply_request(_request(1)), "valid request is accepted")
	root._process(1.0)
	_check(root.phase == 0.0 and not root.is_processing(), "static phase stays fixed and does not process frames")

	for skin in ["mine", "mining", "horse"]:
		_check(root.apply_request(_request(root.generation_id + 1, {"skinId": skin, "theme": "night"})), "theme loads: " + skin)
		_check(root.skin_id == skin and root._model != null, "model is present: " + skin)
		var extent: float = maxf(root._model_size.x, maxf(root._model_size.y, root._model_size.z))
		_check(absf(extent - 2.0) < 0.01, "model is normalized: " + skin)
		await process_frame

	_check(root.apply_request(_request(20, {"skinId": "mine", "viewport": {"width": 1640, "height": 810}})), "wide layout request is accepted")
	_check(not root.apply_request(_request(1, {"skinId": "mining"})), "stale generation is rejected")
	_check(root.skin_id == "mine", "stale request does not replace current theme")
	_check(not root.apply_request(_request(21, {"skinId": "gpu"})), "removed gpu theme is rejected")
	_check(root.mode == "STATIC" and not root.is_processing(), "invalid theme fails safe to static")
	_check(root.skin_id == "mine", "invalid theme keeps the current model")
	_check(not root.apply_request(_request(22, {"viewport": {"width": NAN, "height": 810}})), "NaN viewport is rejected")

	_check(root.apply_request(_request(23, {"mode": "PREVIEW"})), "preview request is accepted")
	_check(not root.is_processing(), "preview does not animate before a safety grant")
	root.set_safety_allowed(true)
	_check(root.is_processing(), "preview animates after a safety grant")
	root._process(0.5)
	_check(root.phase > 0.0, "preview phase advances while allowed")
	_check(root.apply_request(_request(100, {"mode": "PREVIEW", "speedKph": 0})) and is_equal_approx(root.motion_rate, 0.6), "stopped speed uses the slowest rate")
	_check(root.apply_request(_request(101, {"mode": "PREVIEW", "speedKph": 30})) and is_equal_approx(root.motion_rate, 1.1), "motion follows speed")
	_check(root.apply_request(_request(102, {"mode": "PREVIEW", "speedKph": 200})) and is_equal_approx(root.motion_rate, 1.6), "speeds above the cap add no motion")
	_check(not root.apply_request(_request(103, {"mode": "PREVIEW", "speedKph": NAN})), "NaN speed is rejected")
	_check(root.mode == "STATIC" and not root.is_processing(), "NaN speed fails safe to static")
	_check(not root.apply_request(_request(104, {"mode": "PREVIEW", "speedKph": -1})), "negative speed is rejected")
	root.set_safety_allowed(true)
	_check(root.apply_request(_request(105, {"mode": "PREVIEW"})) and root.is_processing(), "preview resumes for the next check")
	root._process(0.5)
	root.set_safety_allowed(false)
	_check(root.mode == "STATIC" and not root.is_processing() and root.phase == 0.0, "safety lock stops motion and resets phase")
	_check(root._pivot.position == Vector3.ZERO and root._pivot.scale == Vector3.ONE, "safety lock restores the rest pose")
	_check(not root._sparks.emitting and not root._sparks.visible, "safety lock stops particles")

	root.set_safety_allowed(true)
	_check(not root.is_processing(), "safety grant alone does not resume after a lock")
	_check(root.apply_request(_request(106, {"mode": "PREVIEW"})) and root.is_processing(), "preview resumes only with a new host request")
	root.set_host_guard(func() -> bool: return false)
	root._process(0.1)
	_check(not root.is_processing() and root.mode == "STATIC", "revoked host guard stops the next frame")
	root.set_host_guard(Callable())
	root.set_safety_allowed(true)
	_check(root.apply_request(_request(107, {"mode": "PREVIEW", "reducedMotion": true})), "reduced-motion request is accepted")
	_check(not root.is_processing(), "reduced motion disables animation")
	root.apply_request(_request(108, {"mode": "PREVIEW"}))
	root.visible = false
	_check(not root.is_processing() and root.mode == "STATIC", "hidden stage stops and returns to static")
	root.visible = true
	_check(not root.is_processing(), "becoming visible does not resume without a new host request")
	root.queue_free()
	print("Renderer tests complete: %d failure(s)" % failures)
	quit(1 if failures else 0)
