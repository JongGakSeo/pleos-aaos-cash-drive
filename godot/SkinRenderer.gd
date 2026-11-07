extends Control
class_name SkinRenderer

## Lightweight vector renderer. Native host must gate every transition/action.
const SKINS := ["horse", "mine", "gpu"]
const MODES := ["STATIC", "PREVIEW"]
const THEMES := ["day", "night"]
const MAX_RATE := 2.0

var skin_id := "horse"
var mode := "STATIC"
var preview_speed_kph := 0.0
var theme_name := "day"
var reduced_motion := false
var safety_allowed := false
var generation_id := -1
var phase := 0.0
var animation_rate := 0.0
var host_guard := Callable()

func set_host_guard(guard: Callable) -> void:
	host_guard = guard
	if guard.is_valid() and not bool(guard.call()):
		set_safety_allowed(false)

func _ready() -> void:
	custom_minimum_size = Vector2(320, 240)
	set_process(false)
	queue_redraw()

## Returns false for malformed/stale requests. Invalid content fails safe to STATIC.
func apply_request(request: Dictionary) -> bool:
	if not _has_valid_envelope(request):
		_force_static()
		return false
	var incoming_generation: int = request["generationId"]
	if incoming_generation <= generation_id:
		return false
	generation_id = incoming_generation
	if not _valid_content(request):
		_force_static()
		return false

	skin_id = request["skinId"]
	mode = request["mode"]
	preview_speed_kph = clampf(float(request["previewSpeedKph"]), 0.0, 120.0)
	theme_name = request["theme"]
	reduced_motion = request["reducedMotion"]
	custom_minimum_size = Vector2(float(request["viewport"]["width"]), float(request["viewport"]["height"]))
	_update_processing()
	queue_redraw()
	return true

## Called by the trusted native host. False immediately drops animation and resets phase.
func set_safety_allowed(allowed: bool) -> void:
	safety_allowed = allowed
	if not allowed:
		_force_static()
	else:
		_update_processing()
		queue_redraw()

func _has_valid_envelope(request: Dictionary) -> bool:
	for key in ["skinId", "mode", "previewSpeedKph", "theme", "viewport", "reducedMotion", "generationId"]:
		if not request.has(key):
			return false
	return typeof(request["generationId"]) == TYPE_INT and request["generationId"] >= 0

func _valid_content(request: Dictionary) -> bool:
	if typeof(request["skinId"]) != TYPE_STRING or not SKINS.has(request["skinId"]):
		return false
	if typeof(request["mode"]) != TYPE_STRING or not MODES.has(request["mode"]):
		return false
	if typeof(request["theme"]) != TYPE_STRING or not THEMES.has(request["theme"]):
		return false
	if typeof(request["reducedMotion"]) != TYPE_BOOL:
		return false
	var speed_type := typeof(request["previewSpeedKph"])
	if speed_type != TYPE_INT and speed_type != TYPE_FLOAT:
		return false
	if not is_finite(float(request["previewSpeedKph"])):
		return false
	if typeof(request["viewport"]) != TYPE_DICTIONARY:
		return false
	var viewport: Dictionary = request["viewport"]
	if not viewport.has("width") or not viewport.has("height"):
		return false
	for dimension in [viewport["width"], viewport["height"]]:
		var dimension_type := typeof(dimension)
		if (dimension_type != TYPE_INT and dimension_type != TYPE_FLOAT) or not is_finite(float(dimension)) or float(dimension) <= 0.0:
			return false
	return true

func _update_processing() -> void:
	var active := mode == "PREVIEW" and safety_allowed and not reduced_motion and is_visible_in_tree() and preview_speed_kph > 0.0
	animation_rate = minf(MAX_RATE, preview_speed_kph / 45.0 * 0.75) if active else 0.0
	set_process(active)

func _notification(what: int) -> void:
	if what == NOTIFICATION_VISIBILITY_CHANGED and is_inside_tree():
		if not is_visible_in_tree():
			_force_static()
		else:
			_update_processing()

func _process(delta: float) -> void:
	if mode != "PREVIEW" or not safety_allowed or reduced_motion or not is_visible_in_tree() or (host_guard.is_valid() and not bool(host_guard.call())):
		_force_static()
		return
	phase = fposmod(phase + delta * animation_rate, TAU)
	queue_redraw()

func _force_static() -> void:
	mode = "STATIC"
	phase = 0.0
	animation_rate = 0.0
	set_process(false)
	queue_redraw()

func _draw() -> void:
	var bounds := Rect2(Vector2.ZERO, size)
	var is_night := theme_name == "night"
	var paper := Color("20251f") if is_night else Color("f2f1ea")
	var ink := Color("e9eddf") if is_night else Color("18372d")
	var accent := Color("c4d88c") if is_night else Color("aec36e")
	var soft := Color("354137") if is_night else Color("e2e6d6")
	draw_rect(bounds, paper)
	draw_circle(Vector2(size.x * 0.5, size.y * 0.49), minf(size.x, size.y) * 0.31, soft)
	var center := Vector2(size.x * 0.5, size.y * 0.49)
	var scale_factor := minf(size.x, size.y) * 0.003
	var bob := sin(phase) * scale_factor * 2.0 if is_processing() else 0.0
	match skin_id:
		"horse": _draw_horse(center + Vector2(0, bob), scale_factor, ink, accent)
		"mine": _draw_mine(center + Vector2(0, bob), scale_factor, ink, accent)
		"gpu": _draw_gpu(center + Vector2(0, bob), scale_factor, ink, accent)
	draw_string(ThemeDB.fallback_font, Vector2(20, size.y - 24), skin_id.to_upper(), HORIZONTAL_ALIGNMENT_LEFT, -1, 18, ink)

func _draw_horse(c: Vector2, s: float, ink: Color, accent: Color) -> void:
	# Side-profile horse: barrel, neck, raised head, mane and four legs.
	_draw_ellipse(c + Vector2(0, 12 * s), Vector2(43 * s, 27 * s), ink)
	draw_colored_polygon(PackedVector2Array([
		c + Vector2(22*s, 4*s), c + Vector2(43*s, -44*s), c + Vector2(58*s, -48*s),
		c + Vector2(48*s, -13*s), c + Vector2(36*s, 23*s),
	]), ink)
	draw_colored_polygon(PackedVector2Array([
		c + Vector2(45*s, -46*s), c + Vector2(61*s, -70*s), c + Vector2(84*s, -67*s),
		c + Vector2(94*s, -54*s), c + Vector2(80*s, -42*s), c + Vector2(57*s, -40*s),
	]), ink)
	draw_colored_polygon(PackedVector2Array([c + Vector2(59*s, -65*s), c + Vector2(56*s, -85*s), c + Vector2(70*s, -70*s)]), ink)
	draw_colored_polygon(PackedVector2Array([c + Vector2(76*s, -66*s), c + Vector2(85*s, -84*s), c + Vector2(86*s, -62*s)]), ink)
	draw_colored_polygon(PackedVector2Array([c + Vector2(39*s, -42*s), c + Vector2(10*s, -30*s), c + Vector2(24*s, -4*s), c + Vector2(47*s, -24*s)]), accent)
	for leg_x in [-27.0, -8.0, 18.0, 34.0]:
		draw_line(c + Vector2(leg_x*s, 25*s), c + Vector2((leg_x + (4.0 if leg_x < 0 else -2.0))*s, 68*s), ink, 8*s, true)
	draw_line(c + Vector2(-39*s, 2*s), c + Vector2(-58*s, 25*s), ink, 7*s, true)
	draw_circle(c + Vector2(78*s, -54*s), 2.4*s, accent)

func _draw_mine(c: Vector2, s: float, ink: Color, accent: Color) -> void:
	# Mine entrance with a framed tunnel and a rail cart carrying ore.
	draw_arc(c + Vector2(0, 5*s), 66*s, PI, TAU, 32, ink, 12*s, true)
	draw_line(c + Vector2(-66*s, 6*s), c + Vector2(-66*s, 48*s), ink, 12*s, true)
	draw_line(c + Vector2(66*s, 6*s), c + Vector2(66*s, 48*s), ink, 12*s, true)
	draw_rect(Rect2(c + Vector2(-53*s, 24*s), Vector2(106*s, 37*s)), accent)
	draw_line(c + Vector2(-73*s, 67*s), c + Vector2(73*s, 67*s), ink, 5*s, true)
	draw_circle(c + Vector2(-36*s, 65*s), 7*s, ink)
	draw_circle(c + Vector2(36*s, 65*s), 7*s, ink)
	draw_colored_polygon(PackedVector2Array([
		c + Vector2(-34*s, 20*s), c + Vector2(-22*s, -1*s), c + Vector2(-8*s, 17*s),
		c + Vector2(5*s, -7*s), c + Vector2(24*s, 20*s),
	]), ink)
	draw_line(c + Vector2(-44*s, -37*s), c + Vector2(43*s, -71*s), ink, 7*s, true)
	draw_line(c + Vector2(5*s, -56*s), c + Vector2(43*s, -27*s), ink, 7*s, true)

func _draw_gpu(c: Vector2, s: float, ink: Color, accent: Color) -> void:
	# Three quiet compute/server units; no coins or mining animation.
	for row in range(3):
		var y := -53.0*s + row * 43.0*s
		draw_rect(Rect2(c + Vector2(-57*s, y), Vector2(114*s, 34*s)), ink)
		draw_rect(Rect2(c + Vector2(-47*s, y + 9*s), Vector2(57*s, 16*s)), Color("536054") if theme_name == "night" else Color("d5dcc7"))
		draw_circle(c + Vector2(39*s, y + 17*s), 5*s, accent)
		draw_line(c + Vector2(-39*s, y + 16*s), c + Vector2(-4*s, y + 16*s), ink, 2*s, true)
	# Single static status line; PREVIEW only moves a restrained highlight.
	var marker_x := sin(phase) * 17*s if is_processing() else 0.0
	draw_line(c + Vector2(-24*s + marker_x, 83*s), c + Vector2(24*s + marker_x, 83*s), accent, 4*s, true)

func _draw_ellipse(center: Vector2, radii: Vector2, color: Color) -> void:
	var points := PackedVector2Array()
	for i in range(33):
		var angle := TAU * float(i) / 32.0
		points.append(center + Vector2(cos(angle) * radii.x, sin(angle) * radii.y))
	draw_colored_polygon(points, color)
