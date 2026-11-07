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
		"previewSpeedKph": 0.0,
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
	_check(root.apply_request(_request(1)), "valid request is accepted")
	_check(root.custom_minimum_size == Vector2(840, 1184), "viewport dimensions are applied")
	root._process(1.0)
	_check(root.phase == 0.0 and not root.is_processing(), "static phase stays fixed and does not process frames")
	_check(root.apply_request(_request(2, {"skinId": "mine", "viewport": {"width": 1640, "height": 810}})), "wide layout request is accepted")
	_check(root.custom_minimum_size == Vector2(1640, 810), "wide resize is applied")
	_check(not root.apply_request(_request(1, {"skinId": "gpu"})), "stale generation is rejected")
	_check(root.skin_id == "mine", "stale request does not replace current skin")

	_check(not root.apply_request(_request(3, {"skinId": "unknown"})), "unknown skin is rejected")
	_check(root.mode == "STATIC" and not root.is_processing(), "invalid skin fails safe to static")
	root.set_safety_allowed(true)
	_check(not root.apply_request(_request(4, {"previewSpeedKph": NAN, "mode": "PREVIEW"})), "NaN speed is rejected")
	_check(root.mode == "STATIC" and not root.is_processing(), "NaN request fails safe to static")

	_check(root.apply_request(_request(5, {"skinId": "gpu", "mode": "PREVIEW", "previewSpeedKph": 400.0})), "finite preview request is accepted")
	_check(root.preview_speed_kph == 120.0, "preview speed is clamped to the supported range")
	_check(root.animation_rate <= 2.0 and root.is_processing(), "preview rate is bounded and processing is active")
	root.set_safety_allowed(false)
	_check(root.mode == "STATIC" and not root.is_processing() and root.phase == 0.0, "safety lock immediately stops and resets motion")

	root.set_safety_allowed(true)
	_check(root.apply_request(_request(6, {"mode": "PREVIEW", "previewSpeedKph": 90.0})), "preview can resume only after explicit safety grant")
	var before: float = root.phase
	root.set_process(false)
	root._process(1.0)
	_check(root.phase > before, "preview phase advances only when explicitly processed")
	_check(root.apply_request(_request(7, {"mode": "PREVIEW", "previewSpeedKph": 90.0, "reducedMotion": true})), "reduced-motion request is accepted")
	_check(not root.is_processing() and root.animation_rate == 0.0, "reduced motion disables animation")
	root.set_safety_allowed(true)
	root.apply_request(_request(8, {"mode": "PREVIEW", "previewSpeedKph": 90.0}))
	root.visible = false
	_check(not root.is_processing() and root.mode == "STATIC", "hidden renderer stops and returns to static")
	root.visible = true
	_check(not root.is_processing(), "becoming visible does not resume without a new host request")
	root.queue_free()
	print("Renderer tests complete: %d failure(s)" % failures)
	quit(1 if failures else 0)
