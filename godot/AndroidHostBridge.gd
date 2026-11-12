extends Node

var host: Object
var renderer: ThemeStage

func _ready() -> void:
	set_process(false)
	renderer = get_parent() as ThemeStage
	# Children become ready before their parent. Wait until the stage and the first frame exist
	# before touching the Android plugin, so no host frame reaches a half-built stage.
	await get_tree().process_frame
	if not is_inside_tree() or not Engine.has_singleton("CashDrivePreview"):
		return
	host = Engine.get_singleton("CashDrivePreview")
	renderer.set_host_guard(Callable(host, "is_allowed"))
	host.connect("frame_changed", _refresh)
	_refresh()

func _refresh() -> void:
	# Read the latest atomic frame instead of trusting an older queued signal payload.
	if host == null:
		return
	apply_host_frame(host.get_request_json(), bool(host.is_allowed()))

func apply_host_frame(json: String, allowed: bool) -> bool:
	var parsed: Variant = JSON.parse_string(json)
	if typeof(parsed) != TYPE_DICTIONARY:
		renderer.set_safety_allowed(false)
		return false
	var request: Dictionary = parsed
	# JSON may decode integer fields as float. Convert only exact safe integers.
	var generation: Variant = request.get("generationId")
	if typeof(generation) != TYPE_INT and typeof(generation) != TYPE_FLOAT:
		renderer.set_safety_allowed(false)
		return false
	var number := float(generation)
	if not is_finite(number) or number < 0 or number > 9007199254740991.0 or floor(number) != number:
		renderer.set_safety_allowed(false)
		return false
	request["generationId"] = int(number)
	renderer.set_safety_allowed(allowed)
	return renderer.apply_request(request)
