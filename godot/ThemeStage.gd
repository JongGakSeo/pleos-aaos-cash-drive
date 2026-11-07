extends Node3D
class_name ThemeStage

## Full-screen main theme scene. The native host gates every transition and owns all reward state.
## Meshes are generated with NC Image to 3D (static, no rig), so motion is whole-model plus effects.
const SKINS := ["horse", "mine", "mining"]
const MODES := ["STATIC", "PREVIEW"]
const THEMES := ["day", "night"]
const MODEL_PATH := "res://assets/nc/%s/model.glb"
const STRIKE_SECONDS := 1.4
## Motion follows speed up to a cap, so going faster than this adds nothing.
const MIN_RATE := 0.6
const MAX_RATE := 1.6
const RATE_CAP_KPH := 60.0
const CAMERA_FOV := 28.0
const CAMERA_LIFT := 0.2
const TINT := {"day": Color(1, 1, 1), "night": Color(0.74, 0.76, 0.86)}
## Effect anchors as fractions of the model's front view: x from left, y from top.
const SPARK_AT := {"horse": Vector2(0.32, 0.92), "mine": Vector2(0.70, 0.42), "mining": Vector2(0.39, 0.25)}
const SPARK_COLOR := {"horse": Color("c9dd8b"), "mine": Color("49d6d0"), "mining": Color("f4c23d")}
const GLOW_AT := {"mine": Vector2(0.27, 0.25), "mining": Vector2(0.75, 0.18)}
const GLOW_COLOR := {"mine": Color("ffc35c"), "mining": Color("fff0b0")}

var skin_id := ""
var mode := "STATIC"
var theme_name := "day"
var reduced_motion := false
var safety_allowed := false
var generation_id := -1
var phase := 0.0
var motion_rate := MIN_RATE
var host_guard := Callable()

var _pivot: Node3D
var _model: Node3D
var _camera: Camera3D
var _backdrop: ThemeBackdrop
var _sparks: CPUParticles3D
var _glow: Sprite3D
var _model_size := Vector3(2, 2, 2)
var _last_strike := 0

func _ready() -> void:
	var layer := CanvasLayer.new()
	layer.layer = -1
	_backdrop = ThemeBackdrop.new()
	layer.add_child(_backdrop)
	add_child(layer)
	var environment := Environment.new()
	environment.background_mode = Environment.BG_CANVAS
	var world := WorldEnvironment.new()
	world.environment = environment
	add_child(world)
	_camera = Camera3D.new()
	_camera.fov = CAMERA_FOV
	add_child(_camera)
	_camera.make_current()
	_pivot = Node3D.new()
	add_child(_pivot)
	_sparks = _make_sparks()
	_pivot.add_child(_sparks)
	_glow = _make_glow()
	_pivot.add_child(_glow)
	get_viewport().size_changed.connect(_layout)
	set_process(false)
	_set_skin("horse")
	_apply_look()

func set_host_guard(guard: Callable) -> void:
	host_guard = guard
	if guard.is_valid() and not bool(guard.call()):
		set_safety_allowed(false)

## Returns false for malformed or stale requests. Invalid content fails safe to STATIC.
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
	_set_skin(request["skinId"])
	mode = request["mode"]
	theme_name = request["theme"]
	reduced_motion = request["reducedMotion"]
	motion_rate = lerpf(MIN_RATE, MAX_RATE, clampf(float(request.get("speedKph", 0.0)) / RATE_CAP_KPH, 0.0, 1.0))
	_apply_look()
	_update_processing()
	return true

## Called by the trusted native host. False immediately stops motion and resets the pose.
func set_safety_allowed(allowed: bool) -> void:
	safety_allowed = allowed
	if allowed:
		_update_processing()
	else:
		_force_static()

func _has_valid_envelope(request: Dictionary) -> bool:
	for key in ["skinId", "mode", "theme", "viewport", "reducedMotion", "generationId"]:
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
	if typeof(request["reducedMotion"]) != TYPE_BOOL or typeof(request["viewport"]) != TYPE_DICTIONARY:
		return false
	var viewport: Dictionary = request["viewport"]
	if not viewport.has("width") or not viewport.has("height"):
		return false
	if request.has("speedKph"):
		var speed: Variant = request["speedKph"]
		if (typeof(speed) != TYPE_INT and typeof(speed) != TYPE_FLOAT) or not is_finite(float(speed)) or float(speed) < 0.0:
			return false
	for dimension in [viewport["width"], viewport["height"]]:
		var dimension_type := typeof(dimension)
		if (dimension_type != TYPE_INT and dimension_type != TYPE_FLOAT) or not is_finite(float(dimension)) or float(dimension) <= 0.0:
			return false
	return true

func _update_processing() -> void:
	var active := mode == "PREVIEW" and safety_allowed and not reduced_motion and is_visible_in_tree()
	if not active:
		_rest_pose()
	set_process(active)
	_sparks.visible = active

func _notification(what: int) -> void:
	if what == NOTIFICATION_VISIBILITY_CHANGED and is_inside_tree():
		if is_visible_in_tree():
			_update_processing()
		else:
			_force_static()

func _process(delta: float) -> void:
	if mode != "PREVIEW" or not safety_allowed or reduced_motion or not is_visible_in_tree() or (host_guard.is_valid() and not bool(host_guard.call())):
		_force_static()
		return
	phase += delta * motion_rate
	_animate()

func _force_static() -> void:
	mode = "STATIC"
	if _pivot != null:
		_update_processing()
	else:
		set_process(false)

func _rest_pose() -> void:
	phase = 0.0
	_last_strike = 0
	_pivot.position = Vector3.ZERO
	_pivot.rotation = Vector3.ZERO
	_pivot.scale = Vector3.ONE
	_sparks.emitting = false
	_glow.modulate.a = 0.55
	_backdrop.set_drift(0.0)
	_place_camera(0.0)

func _animate() -> void:
	if skin_id == "horse":
		var stride := phase * 4.4
		var lift := absf(sin(stride))
		_pivot.position.y = lift * 0.07
		_pivot.rotation.z = sin(stride) * 0.035
		_pivot.rotation.x = -cos(stride) * 0.018
		var squash := 1.0 + (lift - 0.5) * 0.03
		_pivot.scale = Vector3(2.0 - squash, squash, 2.0 - squash)
		if int(stride / PI) != _last_strike:
			_last_strike = int(stride / PI)
			_sparks.restart()
	else:
		var beat := fposmod(phase, STRIKE_SECONDS) / STRIKE_SECONDS
		var thump := exp(-beat * 9.0)
		_pivot.rotation.y = sin(phase * 0.5) * 0.07
		_pivot.scale = Vector3(1.0 + thump * 0.006, 1.0 - thump * 0.014, 1.0 + thump * 0.006)
		if int(phase / STRIKE_SECONDS) != _last_strike:
			_last_strike = int(phase / STRIKE_SECONDS)
			_sparks.restart()
		_glow.modulate.a = 0.55 + 0.25 * sin(phase * 7.0) * sin(phase * 2.3)
	_backdrop.set_drift(sin(phase * 0.22))
	_place_camera(sin(phase * 0.35) * 0.09)

func _set_skin(next: String) -> void:
	if next == skin_id:
		return
	skin_id = next
	if _model != null:
		_model.queue_free()
		_model = null
	var scene: PackedScene = load(MODEL_PATH % skin_id)
	_model = scene.instantiate()
	_pivot.add_child(_model)
	_normalize_model()
	_place_effects()
	_layout()

## Scale so the longest side is 2 units, centered on x/z with the base on y=0.
func _normalize_model() -> void:
	var mesh_node := _first_mesh(_model)
	var box := mesh_node.global_transform * mesh_node.mesh.get_aabb()
	var factor := 2.0 / maxf(box.size.x, maxf(box.size.y, box.size.z))
	_model.scale = Vector3.ONE * factor
	box = mesh_node.global_transform * mesh_node.mesh.get_aabb()
	var center := box.get_center()
	_model.position -= Vector3(center.x, box.position.y, center.z)
	_model_size = box.size

func _first_mesh(node: Node) -> MeshInstance3D:
	if node is MeshInstance3D:
		return node
	for child in node.get_children():
		var found := _first_mesh(child)
		if found != null:
			return found
	return null

func _front_point(fraction: Vector2) -> Vector3:
	return Vector3((fraction.x - 0.5) * _model_size.x, (1.0 - fraction.y) * _model_size.y, _model_size.z * 0.3)

func _place_effects() -> void:
	_sparks.position = _front_point(SPARK_AT[skin_id])
	var material: StandardMaterial3D = _sparks.mesh.material
	material.albedo_color = SPARK_COLOR[skin_id]
	_sparks.direction = Vector3(0, 1, 0.2)
	_glow.visible = GLOW_AT.has(skin_id)
	if _glow.visible:
		_glow.position = _front_point(GLOW_AT[skin_id])
		_glow.modulate = GLOW_COLOR[skin_id]
		_glow.modulate.a = 0.55

func _apply_look() -> void:
	var mesh_node := _first_mesh(_model)
	var source := mesh_node.mesh.surface_get_material(0)
	var material := StandardMaterial3D.new()
	material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	material.albedo_texture = (source as BaseMaterial3D).albedo_texture
	material.albedo_color = TINT[theme_name]
	mesh_node.material_override = material
	_backdrop.configure(skin_id, theme_name)

func _layout() -> void:
	_place_camera(0.0 if not is_processing() else sin(phase * 0.35) * 0.09)

## Fits the model into the area left by the native controls: top buttons on every panel,
## a bottom dock on narrow panels, and a right-hand panel on wide ones.
func _place_camera(orbit: float) -> void:
	var view := get_viewport().get_visible_rect().size
	if view.x <= 0.0 or view.y <= 0.0:
		return
	var aspect := view.x / view.y
	var is_wide := aspect >= 1.4
	var top := 0.11
	var bottom := 0.04 if is_wide else 0.19
	var right := 0.285 if is_wide else 0.0
	var usable := 1.0 - top - bottom
	var half_tan := tan(deg_to_rad(CAMERA_FOV) * 0.5)
	var by_height := _model_size.y * 1.08 / (usable * 0.92) / (2.0 * half_tan)
	var by_width := _model_size.x / (0.94 * (1.0 - right)) / (2.0 * half_tan * aspect)
	var distance := maxf(by_height, by_width) + _model_size.z * 0.5
	var target := Vector3(0, _model_size.y * 0.5, 0)
	_camera.position = target + Vector3(sin(orbit) * distance, distance * CAMERA_LIFT, cos(orbit) * distance)
	_camera.look_at(target)
	var visible_height := 2.0 * distance * half_tan
	_camera.v_offset = -(0.5 - (top + usable * 0.5)) * visible_height
	_camera.h_offset = right * 0.5 * visible_height * aspect
	_backdrop.set_ground(_camera.unproject_position(Vector3(0, _model_size.y * 0.04, -_model_size.z * 0.25)).y)
	_backdrop.set_focus(_camera.unproject_position(target), view.y * usable * 0.5)

func _make_sparks() -> CPUParticles3D:
	var particles := CPUParticles3D.new()
	var mesh := SphereMesh.new()
	mesh.radius = 0.022
	mesh.height = 0.044
	mesh.radial_segments = 6
	mesh.rings = 3
	var material := StandardMaterial3D.new()
	material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mesh.material = material
	particles.mesh = mesh
	particles.amount = 10
	particles.lifetime = 0.8
	particles.one_shot = true
	particles.explosiveness = 0.95
	particles.emitting = false
	particles.spread = 55.0
	particles.initial_velocity_min = 0.45
	particles.initial_velocity_max = 0.9
	particles.gravity = Vector3(0, -2.2, 0)
	particles.scale_amount_min = 0.6
	particles.scale_amount_max = 1.3
	particles.local_coords = false
	particles.visible = false
	return particles

func _make_glow() -> Sprite3D:
	var gradient := Gradient.new()
	gradient.colors = PackedColorArray([Color(1, 1, 1, 1), Color(1, 1, 1, 0)])
	var texture := GradientTexture2D.new()
	texture.gradient = gradient
	texture.fill = GradientTexture2D.FILL_RADIAL
	texture.fill_from = Vector2(0.5, 0.5)
	texture.fill_to = Vector2(1.0, 0.5)
	texture.width = 64
	texture.height = 64
	var sprite := Sprite3D.new()
	sprite.texture = texture
	sprite.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	sprite.shaded = false
	sprite.pixel_size = 0.009
	sprite.no_depth_test = true
	return sprite
