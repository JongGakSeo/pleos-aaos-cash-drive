extends Node3D
class_name ThemeStage

## Full-screen main theme scene. The native host gates every transition and owns all reward state.
## Meshes are generated with NC Image to 3D (no skeleton). The horse moves with a vertex-shader fake rig;
## mine and mining are assembled from separate part meshes that move as small rigid joints.
const SKINS := ["horse", "mine", "mining"]
const MODES := ["STATIC", "PREVIEW"]
const THEMES := ["day", "night"]
const MODEL_PATH := "res://assets/nc/%s/model.glb"
## Neutral-pose models made from the rig sheets move better with the fake joints.
const MODEL_OVERRIDE := {"horse": "res://assets/nc/parts/horse-rig.glb"}
const PART_PATH := "res://assets/nc/parts/%s.glb"
## Themes assembled from separate rigid parts (rig sheets -> Image to 3D, one call per part).
## w/h: part width or height, at: where the part's pivot sits (base is 2 units wide, ground y=0),
## pivot: pivot as a fraction of the part's box, rest: resting tilt (deg, around the view axis),
## move: bob | strike | sway | rock, amp: motion size (deg, or units for bob), speed: cycles per phase second.
const RIGS := {
	"mine": [
		{"part": "mine-base", "w": 2.0, "at": Vector3(0, 0, 0)},
		{"part": "mine-lantern", "h": 0.3, "at": Vector3(0.22, 0.98, -0.3), "pivot": Vector3(0.5, 1.0, 0.5), "move": "sway", "amp": 5.0, "speed": 0.55},
		{"part": "mine-cart", "h": 0.36, "at": Vector3(0.55, 0.1, 0.78), "yaw": -30.0, "move": "rock", "amp": 1.5, "speed": 0.7},
		{"part": "mine-miner", "h": 0.86, "at": Vector3(-0.22, 0.1, 0.32), "yaw": 10.0, "move": "bob", "amp": 0.012, "speed": 1.4},
		{"part": "mine-pickaxe", "h": 0.42, "at": Vector3(-0.14, 0.71, 0.42), "pivot": Vector3(0.38, 0.02, 0.5), "rest": -20.0, "move": "strike", "amp": 22.0, "speed": 0.7},
	],
	"mining": [
		{"part": "mining-base", "w": 2.0, "at": Vector3(0, 0, 0)},
		{"part": "mining-wheelbarrow", "h": 0.42, "at": Vector3(0.68, 0.1, 0.45), "yaw": -25.0, "move": "rock", "amp": 1.2, "speed": 0.6},
		{"part": "mining-miner-c", "h": 0.62, "at": Vector3(-0.62, 0.12, 0.6), "yaw": 20.0, "move": "bob", "amp": 0.01, "speed": 1.6},
		{"part": "mining-pickaxe", "h": 0.34, "at": Vector3(-0.54, 0.63, 0.7), "pivot": Vector3(0.4, 0.02, 0.5), "rest": -25.0, "move": "strike", "amp": 20.0, "speed": 0.8},
		{"part": "mining-miner-b", "h": 0.5, "at": Vector3(0.2, 0.1, 0.62), "yaw": -10.0, "move": "bob", "amp": 0.008, "speed": 1.6, "phase": 0.5},
	],
}
const STRIKE_SECONDS := 1.4
## Motion follows speed up to a cap, so going faster than this adds nothing.
const MIN_RATE := 0.9
const MAX_RATE := 1.6
const RATE_CAP_KPH := 60.0
const CAMERA_FOV := 28.0
const CAMERA_LIFT := 0.2
## Soft ambient fill plus one key light keeps the clay look bright without real-time shadows.
const LIGHT := {
	"day": {"ambient": Color(1.0, 0.98, 0.95), "ambient_energy": 0.95, "key": Color(1.0, 0.96, 0.9), "key_energy": 1.15},
	"night": {"ambient": Color(0.86, 0.88, 1.0), "ambient_energy": 0.8, "key": Color(1.0, 0.9, 0.78), "key_energy": 0.95},
}
## Effect anchors as fractions of the model's front view: x from left, y from top.
const SPARK_AT := {"horse": Vector2(0.32, 0.92), "mine": Vector2(0.70, 0.42), "mining": Vector2(0.39, 0.25)}
const SPARK_COLOR := {"horse": Color("c9dd8b"), "mine": Color("49d6d0"), "mining": Color("f4c23d")}
const GLOW_AT := {"mine": Vector2(0.27, 0.25), "mining": Vector2(0.75, 0.18)}
const GLOW_COLOR := {"mine": Color("ffc35c"), "mining": Color("fff0b0")}
const MOTION_SHADER := preload("res://ThemeMotion.gdshader")
## Fake joints per theme (see ThemeMotion.gdshader): pivot x/y, radius, angle | speed, phase, -, on.
const JOINTS := {
	"horse": {"gallop": 1, "leg_floor": -0.02, "axis": 1, "yaw": -58.0},
}

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
var _environment: Environment
var _key_light: DirectionalLight3D
var _motion: ShaderMaterial
var _model_size := Vector3(2, 2, 2)
var _last_strike := 0
## Moving rig parts: {node, base (Transform3D), move, amp, speed, phase}
var _parts: Array = []

func _ready() -> void:
	var layer := CanvasLayer.new()
	layer.layer = -1
	_backdrop = ThemeBackdrop.new()
	layer.add_child(_backdrop)
	add_child(layer)
	_environment = Environment.new()
	_environment.background_mode = Environment.BG_CANVAS
	_environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	var world := WorldEnvironment.new()
	world.environment = _environment
	add_child(world)
	_key_light = DirectionalLight3D.new()
	_key_light.rotation_degrees = Vector3(-38, -32, 0)
	_key_light.shadow_enabled = false
	add_child(_key_light)
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
	for part in _parts:
		part["node"].transform = part["base"]
	if _motion != null:
		_motion.set_shader_parameter("amount", 0.0)
	_glow.modulate.a = 0.55
	_backdrop.set_drift(0.0)
	_place_camera(0.0)

func _animate() -> void:
	if _motion != null:
		_motion.set_shader_parameter("t", phase)
		_motion.set_shader_parameter("amount", 1.0)
	if skin_id == "horse":
		var stride := phase * 4.4
		var lift := absf(sin(stride))
		_pivot.position.y = lift * 0.045
		_pivot.rotation.z = sin(stride) * 0.02
		_pivot.rotation.x = -cos(stride) * 0.018
		var squash := 1.0 + (lift - 0.5) * 0.015
		_pivot.scale = Vector3(2.0 - squash, squash, 2.0 - squash)
		if int(stride / PI) != _last_strike:
			_last_strike = int(stride / PI)
			_sparks.restart()
	else:
		_pivot.rotation.y = sin(phase * 0.5) * 0.05
		_animate_parts()
		if int(phase / STRIKE_SECONDS) != _last_strike:
			_last_strike = int(phase / STRIKE_SECONDS)
			_sparks.restart()
		_glow.modulate.a = 0.55 + 0.25 * sin(phase * 7.0) * sin(phase * 2.3)
	_backdrop.set_drift(sin(phase * 0.22))
	_place_camera(sin(phase * 0.35) * 0.09)

## Small rigid motions only, so the clay parts never stretch.
func _animate_parts() -> void:
	for part in _parts:
		var node: Node3D = part["node"]
		var base: Transform3D = part["base"]
		var cycle := fposmod(phase * float(part["speed"]) + float(part["phase"]), 1.0)
		var wave := sin(cycle * TAU)
		match part["move"]:
			"bob":
				node.transform = base.translated(Vector3(0, absf(wave) * float(part["amp"]), 0))
			"strike":
				# Quick downward stroke, slow lift back.
				var stroke := cycle / 0.25 if cycle < 0.25 else 1.0 - (cycle - 0.25) / 0.75
				node.transform = base.rotated_local(Vector3.BACK, -deg_to_rad(float(part["amp"])) * smoothstep(0.0, 1.0, stroke))
			"sway", "rock":
				node.transform = base.rotated_local(Vector3.BACK, deg_to_rad(float(part["amp"])) * wave)

func _build_rig(spec: Array) -> Node3D:
	var root := Node3D.new()
	_parts.clear()
	for entry in spec:
		var mount := Node3D.new()
		var model: Node3D = (load(PART_PATH % entry["part"]) as PackedScene).instantiate()
		mount.add_child(model)
		var box := _bounds(model)
		var factor := float(entry["w"]) / box.size.x if entry.has("w") else float(entry["h"]) / box.size.y
		model.scale = Vector3.ONE * factor
		var pivot: Vector3 = entry.get("pivot", Vector3(0.5, 0.0, 0.5))
		model.position = -(box.position + box.size * pivot) * factor
		mount.position = entry["at"]
		mount.rotation_degrees = Vector3(0, float(entry.get("yaw", 0.0)), float(entry.get("rest", 0.0)))
		root.add_child(mount)
		if entry.has("move"):
			_parts.append({"node": mount, "base": mount.transform, "move": entry["move"], "amp": entry["amp"],
				"speed": entry["speed"], "phase": float(entry.get("phase", 0.0))})
	return root

## Bounds of every mesh under node, in node's own space.
func _bounds(node: Node3D) -> AABB:
	var result := AABB()
	var first := true
	var stack: Array = [[node, Transform3D.IDENTITY]]
	while not stack.is_empty():
		var item: Array = stack.pop_back()
		var current: Node = item[0]
		var xform: Transform3D = item[1]
		if current is MeshInstance3D and current.mesh != null:
			var box: AABB = xform * current.mesh.get_aabb()
			result = box if first else result.merge(box)
			first = false
		for child in current.get_children():
			if child is Node3D:
				stack.append([child, xform * child.transform])
	return result

func _set_skin(next: String) -> void:
	if next == skin_id:
		return
	skin_id = next
	if _model != null:
		_model.queue_free()
		_model = null
	_parts.clear()
	if RIGS.has(skin_id):
		_model = _build_rig(RIGS[skin_id])
	else:
		var scene: PackedScene = load(MODEL_OVERRIDE.get(skin_id, MODEL_PATH % skin_id))
		_model = scene.instantiate()
	_model.rotation_degrees.y = float(JOINTS.get(skin_id, {}).get("yaw", 0.0))
	_pivot.add_child(_model)
	_normalize_model()
	_place_effects()
	_layout()

## Scale so the longest side is 2 units, centered on x/z with the base on y=0.
func _normalize_model() -> void:
	var box := _model.transform * _bounds(_model)
	var factor := 2.0 / maxf(box.size.x, maxf(box.size.y, box.size.z))
	_model.scale *= factor
	box = _model.transform * _bounds(_model)
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
	_apply_light()
	if RIGS.has(skin_id):
		_motion = null
		return
	var mesh_node := _first_mesh(_model)
	var source := mesh_node.mesh.surface_get_material(0)
	var base := source as BaseMaterial3D
	_motion = ShaderMaterial.new()
	_motion.shader = MOTION_SHADER
	_motion.set_shader_parameter("albedo_tex", base.albedo_texture)
	_motion.set_shader_parameter("normal_tex", base.normal_texture)
	_motion.set_shader_parameter("use_normal", base.normal_texture != null)
	var box := mesh_node.mesh.get_aabb()
	_motion.set_shader_parameter("box_min", box.position)
	_motion.set_shader_parameter("box_size", box.size)
	var joints: Dictionary = JOINTS[skin_id]
	_motion.set_shader_parameter("gallop", int(joints.get("gallop", 0)))
	_motion.set_shader_parameter("leg_floor", float(joints.get("leg_floor", 0.13)))
	_motion.set_shader_parameter("gallop_axis", int(joints.get("axis", 0)))
	for key in ["a", "b"]:
		var joint: Array = joints.get(key, [Vector4.ZERO, Vector4.ZERO])
		_motion.set_shader_parameter("swing_" + key, joint[0])
		_motion.set_shader_parameter("swing_" + key + "_timing", joint[1])
	_motion.set_shader_parameter("amount", 1.0 if is_processing() else 0.0)
	mesh_node.material_override = _motion

func _apply_light() -> void:
	var light: Dictionary = LIGHT[theme_name]
	_environment.ambient_light_color = light["ambient"]
	_environment.ambient_light_energy = light["ambient_energy"]
	_key_light.light_color = light["key"]
	_key_light.light_energy = light["key_energy"]
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
	var is_wide := aspect >= 1.0
	# Matches the Pleos page navigation (112dp), bottom figures (176dp) and wide side panel (440dp).
	var top := 0.14 if is_wide else 0.095
	var bottom := 0.03 if is_wide else 0.15
	var right := 0.27 if is_wide else 0.0
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
