extends Node3D
class_name ThemeStage

## Full-screen home scene: a small clay diorama per theme (ThemeBackdrop behind it). The characters are
## rigged and animated models made with VARCO 3D from the user's character art (assets/varco/README.md).
## The native host gates every transition and owns all reward state; this scene draws only what the
## request says. Motion comes from the request (motion, mode, reducedMotion, speedKph, minuteOfDay,
## metersToNext, milestone) and from set_safety_allowed. Malformed requests fall back to STATIC and stand.
## Clips advance only from _process, so every safety stop freezes them and returns the still pose.
const SKINS := ["horse", "mine", "mining"]
const MODES := ["STATIC", "PREVIEW"]
## stand: the still pose and no frames (STATIC), or the idle clip while parked after an arrival (PREVIEW).
## run: the theme works: the pony walks, trots or canters in place while its meadow turns under it, the
## miners swing their pickaxes. reveal: arrive: the work eases out over REVEAL_SECONDS, then stand.
const MOTIONS := ["stand", "run", "reveal"]
const THEMES := ["day", "night"]
const ASSET_PATH := "res://assets/varco/%s.glb"
## How a humanoid holds a pickaxe: its length as a fraction of body height and its rotation (deg) in
## the RightHand bone's space (the handle runs along the bone's X axis, across the palm). Each
## character's "grip" is where the handle sits in that space, as a fraction of body height: on the
## palm side of the hand (-Z), so the hand lies over the handle instead of being skewered by it.
const TOOL_LENGTH := 0.5
const TOOL_ROTATION := Vector3(0, 0, -90)
## Diorama per theme. Parts: asset (under assets/varco), w (width) or h (height) in scene units, at
## (where the part's pivot sits; bases are 2 units wide), pivot (fraction of the part's box, default
## bottom centre), yaw (deg; for a character, where it faces, 0 = the camera).
## anim: clip played while the theme works, or "gait" for the pony's gait by speed. still: clip whose
## first frame is the still pose and which loops as the parked idle (humanoids are rigged in a T-pose);
## the pony's is its Idle gait. tool + grip: model held in the right fist.
## move "rock": small tilt with amp (deg) and speed (cycles per phase second). turn: the base turns
## under the gait character, so it travels without leaving its spot.
## Heights come from the bases' meshes (floor: mine 0.14, coin 0.18; the meadow's track is a flat
## ring at radius 0.64..0.80, y 0.232). The pickaxe lands about 0.45 body heights from the worker's
## feet, 60 deg to the right of where it faces.
const SCENES := {
	"horse": [
		{"asset": "props/meadow-base", "w": 2.0, "at": Vector3(0, 0, 0), "turn": true},
		{"asset": "pony", "h": 0.8, "at": Vector3(0, 0.232, 0.72), "yaw": -90.0, "anim": "gait"},
	],
	"mine": [
		{"asset": "props/mine-base", "w": 2.0, "at": Vector3(0, 0, 0)},
		{"asset": "props/mine-lantern", "h": 0.3, "at": Vector3(0.22, 1.0, -0.55), "pivot": Vector3(0.5, 1.0, 0.5), "move": "rock", "amp": 5.0, "speed": 0.55},
		{"asset": "props/mine-cart", "h": 0.36, "at": Vector3(-0.62, 0.2, 0.5), "yaw": 30.0, "move": "rock", "amp": 1.5, "speed": 0.7},
		{"asset": "miner", "h": 0.8, "at": Vector3(0.14, 0.19, 0.08), "yaw": 55.0, "anim": "Hammer", "still": "Idle", "tool": "props/mine-pickaxe", "grip": Vector3(0, 0.12, -0.05)},
	],
	"mining": [
		{"asset": "props/mining-base", "w": 2.0, "at": Vector3(0, 0, 0)},
		{"asset": "props/mining-wheelbarrow", "h": 0.4, "at": Vector3(0.7, 0.2, 0.62), "yaw": -25.0, "move": "rock", "amp": 1.2, "speed": 0.6},
		{"asset": "kid", "h": 0.62, "at": Vector3(-0.3, 0.19, 0.42), "yaw": 0.0, "anim": "Hammer", "still": "Idle", "tool": "props/mining-pickaxe", "grip": Vector3(0, 0.115, -0.06)},
		{"asset": "kid", "h": 0.62, "at": Vector3(0.36, 0.18, 0.42), "yaw": -50.0, "anim": "Hammer", "still": "Idle", "phase": 0.5, "tool": "props/mining-pickaxe", "grip": Vector3(0, 0.115, -0.06)},
	],
}
## Pony gait by speed: [upper bound kph, clip].
const GAITS := [[3.0, "Idle"], [20.0, "Walk"], [50.0, "Trot"], [INF, "Canter"]]
## Ground speed that matches each clip's stride, in model units per second (measured from the
## hooves' stance phase), so the hooves do not slide over the turning meadow.
const STRIDE := {"Walk": 0.46, "Trot": 0.85, "Canter": 1.47}
## The meadow turns a little slower than the stride asks, so it does not spin at a canter.
const TURN_EASE := 0.7
const BLEND_SECONDS := 0.4
## Degrees a clip turns the body to its right. The character's mount turns the other way while the
## clip plays, so it keeps facing its "yaw".
const CLIP_TURN := {"Hammer": 75.0}
const CLIP_TURN_SPEED := 3.3
## Motion follows speed up to a cap, so going faster than this adds nothing.
const MIN_RATE := 0.95
const MAX_RATE := 1.15
const RATE_CAP_KPH := 60.0
const SPEED_CAP_KPH := 200.0
## Reveal: the work eases out on 1 - (1 - t)^3 over this long, then the stage stands.
const REVEAL_SECONDS := 1.2
## Defaults when an older host sends neither field: noon, a full kilometre to the next ticket.
const DEFAULT_MINUTE := 720
const DEFAULT_METERS := 1000.0
const MAX_METERS := 1000.0
## Framing. The viewport is the host's scene area alone: a column beside the meter on a wide panel (about
## 1010x1122) and a band above it on a narrow one (about 840x702); a scene wider than BAND_ASPECT is the
## band. The diorama stands on the lower part: it grows until it takes MODEL_WIDTH of the width or
## MODEL_HEIGHT of the height, with the base's front foot at BASE_BOTTOM of the height, so the sky above
## stays free for the host's reward cards.
const BAND_ASPECT := 1.08
const MODEL_WIDTH := 0.94
const MODEL_HEIGHT := 0.56
const BASE_BOTTOM := 0.93
const CAMERA_FOV := 28.0
const CAMERA_LIFT := 0.24
## A toy on a table: ambient, a shadowless light from straight above (tops lighter than undersides)
## and one shadow-casting key light; the energies add up to about 1 on the brightest surface, so the
## white clay does not burn out. Dusk warms and night cools every light.
const LIGHT := {
	"day": {"tint": Color.WHITE, "ambient": 0.35, "fill": 0.25, "key": 0.5},
	"dusk": {"tint": Color("FFE4CC"), "ambient": 0.33, "fill": 0.22, "key": 0.48},
	"night": {"tint": Color("C9D2E8"), "ambient": 0.25, "fill": 0.17, "key": 0.35},
}
const AMBIENT_COLOR := Color(1.0, 0.98, 0.95)
const KEY_COLOR := Color(1.0, 0.97, 0.92)
## Lamp glow anchors in scene units (same space as SCENES "at").
const GLOW_AT := {"mine": Vector3(0.22, 0.85, -0.55), "mining": Vector3(0.0, 1.0, -0.2)}
const GLOW_COLOR := {"mine": Color("ffc35c"), "mining": Color("fff0b0")}
## Milestone (meadow only): a post with an amber pill-shaped plate (no text) standing on the inner edge of the
## track, behind the pony, glued to the turning meadow. Ring angles are degrees from the pony's spot,
## positive toward its tail; the ground comes from the negative side. The sign is planted when the
## next ticket is no further than the time the meadow needs to turn SIGN_LEAD, at the angle that
## brings it beside the pony at that moment; it is removed once it has turned SIGN_GONE past it.
## After an arrival it stands STOP_DEG behind the tail.
const SIGN_RADIUS := 0.57
const SIGN_BASE := 0.24
const SIGN_POST := 0.72
## The plate is a pill (a capsule on its side, flattened): its length, half its height and its thickness.
const SIGN_PLATE := Vector3(0.25, 0.07, 0.045)
const SIGN_LEAD := 110.0
const SIGN_GONE := 150.0
const STOP_DEG := 38.0
const SIGN_GROW_SECONDS := 0.4
const SIGN_COLORS := {"day": ["8C929B", "E0A93C"], "dusk": ["6F6A73", "E0A93C"], "night": ["4A4F5A", "C9962F"]}

var skin_id := ""
var mode := "STATIC"
var motion := "stand"
var theme_name := "day"
var reduced_motion := false
var safety_allowed := false
var generation_id := -1
var phase := 0.0
var motion_rate := MIN_RATE
var speed_kph := 0.0
var reveal_time := 0.0
var minute_of_day := DEFAULT_MINUTE
## The host's metres to the next ticket and the metres driven since it arrived.
var meters_to_next := DEFAULT_METERS
## False once today's tickets are all issued: no next sign (the host sends milestone false).
var milestone_shown := true
var host_guard := Callable()

var _pivot: Node3D
var _model: Node3D
var _camera: Camera3D
var _backdrop: ThemeBackdrop
var _glow: Sprite3D
var _environment: Environment
var _key_light: DirectionalLight3D
var _fill_light: DirectionalLight3D
var _model_size := Vector3(2, 2, 2)
var _camera_distance := 6.0
var _palette := "day"
## The turning base, how fast it turns per unit of ground speed (rad per model unit, signed so the
## ground runs backwards under the gait character), and that character's scale inside _model.
var _turn: Node3D
var _turn_rate := 0.0
var _stride_scale := 1.0
var _ground_speed := 0.0
## Ring angle of the gait character's spot on the turning base.
var _runner_angle := 0.0
## True from the first request of a reveal until the host asks for another motion, so the
## one-second status republishes during the reveal neither restart nor replay it.
var _reveal_latched := false
## Speed the arrival slows down from (0 when the theme was not working).
var _reveal_kph := 0.0
## How much of the working sway (rocking props, camera drift) shows: 1 while running, 0 at rest.
var _sway := 0.0
var _meters_run := 0.0
## The milestone on the meadow and its state: "" (none), "next" (coming or passing), "stop".
var _sign: Node3D
var _sign_state := ""
var _sign_age := 0.0
## Moving rig parts: {node, base (Transform3D), amp, speed, phase}
var _parts: Array = []
## Skinned clips: {player (manual AnimationPlayer), anim ("gait" = pony gait by speed), offset (0..1),
## still (still-pose and idle clip, "" = the Idle gait), mount (the character's node), yaw (rad)}
var _clips: Array = []
## Loaded part scenes by path (null while a background load is pending). Kept so switches stay instant.
var _scenes := {}

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
	_key_light.rotation_degrees = Vector3(-52, -30, 0)
	_key_light.shadow_enabled = true
	_key_light.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
	_key_light.directional_shadow_max_distance = 14.0
	_key_light.shadow_blur = 2.0
	add_child(_key_light)
	_fill_light = DirectionalLight3D.new()
	_fill_light.rotation_degrees = Vector3(-90, 0, 0)
	add_child(_fill_light)
	_camera = Camera3D.new()
	_camera.fov = CAMERA_FOV
	add_child(_camera)
	_camera.make_current()
	_pivot = Node3D.new()
	add_child(_pivot)
	_glow = _make_glow()
	_pivot.add_child(_glow)
	get_viewport().size_changed.connect(_layout)
	set_process(false)
	_set_skin("horse")
	_apply_look()
	_preload_skins()

## Part meshes of every theme, loaded on a worker thread after the first frame so a later theme
## switch does not block the render thread (several seconds of synchronous load on the emulator).
func _preload_skins() -> void:
	for id in SKINS:
		for path in _skin_paths(id):
			if not _scenes.has(path) and ResourceLoader.load_threaded_request(path) == OK:
				_scenes[path] = null

func _skin_paths(id: String) -> Array:
	var paths := []
	for entry in SCENES[id]:
		for key in ["asset", "tool"]:
			if entry.has(key) and not paths.has(ASSET_PATH % entry[key]):
				paths.append(ASSET_PATH % entry[key])
	return paths

## Cached scene; waits for the background load if it is still running.
func _scene(path: String) -> PackedScene:
	if _scenes.get(path) == null:
		var pending := _scenes.has(path) and ResourceLoader.load_threaded_get_status(path) != ResourceLoader.THREAD_LOAD_INVALID_RESOURCE
		var scene: PackedScene = ResourceLoader.load_threaded_get(path) if pending else null
		_scenes[path] = scene if scene != null else load(path)
	return _scenes[path]

func set_host_guard(guard: Callable) -> void:
	host_guard = guard
	if guard.is_valid() and not bool(guard.call()):
		set_safety_allowed(false)

## Returns false for malformed or stale requests. Invalid content fails safe to STATIC and stand.
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
	# How the scene was moving before this request: an arrival slows down from it.
	var run_kph := speed_kph if motion == "run" and is_processing() else 0.0
	_set_skin(request["skinId"])
	mode = request["mode"]
	theme_name = request["theme"]
	reduced_motion = request["reducedMotion"]
	speed_kph = clampf(float(request.get("speedKph", 0.0)), 0.0, SPEED_CAP_KPH)
	motion_rate = lerpf(MIN_RATE, MAX_RATE, clampf(speed_kph / RATE_CAP_KPH, 0.0, 1.0))
	minute_of_day = int(request.get("minuteOfDay", DEFAULT_MINUTE))
	if request.has("metersToNext"):
		meters_to_next = float(request["metersToNext"])
		_meters_run = 0.0
	milestone_shown = bool(request.get("milestone", true))
	_set_motion(request.get("motion", "stand"), run_kph)
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
	if request.has("motion") and (typeof(request["motion"]) != TYPE_STRING or not MOTIONS.has(request["motion"])):
		return false
	if typeof(request["reducedMotion"]) != TYPE_BOOL or typeof(request["viewport"]) != TYPE_DICTIONARY:
		return false
	var viewport: Dictionary = request["viewport"]
	if not viewport.has("width") or not viewport.has("height"):
		return false
	if request.has("speedKph") and not _is_number(request["speedKph"], 0.0, INF):
		return false
	# JSON hands integers over as floats, so a whole-valued float is a valid minute.
	if request.has("minuteOfDay") and not (_is_number(request["minuteOfDay"], 0.0, 1439.0) and floorf(float(request["minuteOfDay"])) == float(request["minuteOfDay"])):
		return false
	if request.has("metersToNext") and not _is_number(request["metersToNext"], 0.0, MAX_METERS):
		return false
	if request.has("milestone") and typeof(request["milestone"]) != TYPE_BOOL:
		return false
	for dimension in [viewport["width"], viewport["height"]]:
		if not _is_number(dimension, 0.0, INF) or float(dimension) <= 0.0:
			return false
	return true

static func _is_number(value: Variant, low: float, high: float) -> bool:
	if typeof(value) != TYPE_INT and typeof(value) != TYPE_FLOAT:
		return false
	var number := float(value)
	return is_finite(number) and number >= low and number <= high

## A reveal starts only when the previous host request asked for something else. Republishing
## "reveal" keeps a running reveal going and leaves a finished one standing.
func _set_motion(requested: String, run_kph: float) -> void:
	if requested != "reveal":
		_reveal_latched = false
		motion = requested
		return
	if _reveal_latched:
		return
	_reveal_latched = true
	reveal_time = 0.0
	_reveal_kph = run_kph
	motion = "reveal"

## Frames run while the scene is shown in PREVIEW: the work (run), the arrival (reveal) and the parked
## idle after it (the host sends PREVIEW with stand in no other state; every explicit stand is STATIC).
func _update_processing() -> void:
	if _pivot == null:
		return
	var shown := mode == "PREVIEW" and safety_allowed and not reduced_motion and is_visible_in_tree()
	if not shown:
		_rest_pose()
	set_process(shown)

func _notification(what: int) -> void:
	# Visibility also changes on tree entry, before _ready has built the scene.
	if what == NOTIFICATION_VISIBILITY_CHANGED and is_inside_tree() and _pivot != null:
		if is_visible_in_tree():
			_update_processing()
		else:
			_force_static()

func _process(delta: float) -> void:
	if mode != "PREVIEW" or not safety_allowed or reduced_motion or not is_visible_in_tree() or (host_guard.is_valid() and not bool(host_guard.call())):
		_force_static()
		return
	# kph the theme works at this frame: the host's while running, easing to zero through an arrival.
	var kph := 0.0
	if motion == "run":
		kph = speed_kph
		phase += delta * motion_rate
		_meters_run += delta * speed_kph / 3.6
	elif motion == "reveal":
		reveal_time += delta
		var t := clampf(reveal_time / REVEAL_SECONDS, 0.0, 1.0)
		kph = _reveal_kph * pow(1.0 - t, 3.0)
		if t >= 1.0:
			motion = "stand"
			_stand_sign()
	_sway = move_toward(_sway, 1.0 if motion == "run" else 0.0, delta / BLEND_SECONDS)
	_animate(delta * (motion_rate if motion == "run" else 1.0), kph)
	_update_sign(delta)

func _force_static() -> void:
	# A reveal stopped half way (a safety lock) lands where it was going.
	if motion == "reveal":
		_stand_sign()
	mode = "STATIC"
	motion = "stand"
	if _pivot != null:
		_update_processing()
	else:
		set_process(false)

## Rest: every clip on its still pose, props upright, the camera straight. The meadow keeps its angle,
## so stopping at a light does not jump the ground. A reveal that cannot animate (STATIC, reduced
## motion, no safety grant) shows its end state at once.
func _rest_pose() -> void:
	if motion == "reveal":
		_stand_sign()
	phase = 0.0
	reveal_time = 0.0
	_sway = 0.0
	_ground_speed = 0.0
	_pivot.rotation = Vector3.ZERO
	for part in _parts:
		part["node"].transform = part["base"]
	for clip in _clips:
		var player: AnimationPlayer = clip["player"]
		player.stop()
		for skeleton in player.get_parent().find_children("*", "Skeleton3D", true, false):
			skeleton.reset_bone_poses()
		player.play(_still(clip))
		player.seek(0.0, true)
		clip["mount"].rotation.y = clip["yaw"] + deg_to_rad(CLIP_TURN.get(_still(clip), 0.0))
	_glow.modulate.a = 0.55
	if _sign != null and _sign_state == "next":
		_plant_sign("")
	elif _sign != null and _sign_state == "stop":
		_sign_age = SIGN_GROW_SECONDS
		_face_sign()
	_place_camera(0.0)

func _still(clip: Dictionary) -> String:
	return clip["still"] if clip["still"] != "" else GAITS[0][1]

func _animate(step: float, kph: float) -> void:
	for clip in _clips:
		var player: AnimationPlayer = clip["player"]
		var anim: String = _gait(kph) if clip["anim"] == "gait" else (clip["anim"] if motion == "run" else _still(clip))
		var mount: Node3D = clip["mount"]
		var turn: float = clip["yaw"] + deg_to_rad(CLIP_TURN.get(anim, 0.0))
		if player.current_animation != anim:
			player.play(anim, BLEND_SECONDS)
			# Workers start their clip at different points, so they do not swing in unison.
			if clip["offset"] > 0.0 and motion == "run" and phase <= step:
				player.seek(player.current_animation_length * clip["offset"], true)
		mount.rotation.y = rotate_toward(mount.rotation.y, turn, step * CLIP_TURN_SPEED)
		player.advance(step)
	if _turn != null:
		var target: float = STRIDE.get(_gait(kph), 0.0) * _stride_scale
		_ground_speed = move_toward(_ground_speed, target, step * 2.0)
		_turn.rotation.y += _turn_speed() * step
	else:
		_pivot.rotation.y = sin(phase * 0.5) * 0.05 * _sway
	for part in _parts:
		var cycle := fposmod(phase * float(part["speed"]) + float(part["phase"]), 1.0)
		var base: Transform3D = part["base"]
		part["node"].transform = base.rotated_local(Vector3.BACK, deg_to_rad(float(part["amp"])) * sin(cycle * TAU) * _sway)
	_glow.modulate.a = 0.55 + 0.25 * sin(phase * 7.0) * sin(phase * 2.3) * _sway
	_place_camera(sin(phase * 0.35) * 0.09 * _sway)

## Radians per second the meadow turns at the current ground speed.
func _turn_speed() -> float:
	return _ground_speed * _turn_rate * TURN_EASE

func _gait(kph: float) -> String:
	for gait in GAITS:
		if kph < gait[0]:
			return gait[1]
	return GAITS[-1][1]

## Ring angle (rad) of a point glued to the meadow at local angle `local`, measured from the runner's
## spot and growing toward its tail (the way the ground moves).
func _ring_angle(local: float) -> float:
	return wrapf((local + _turn.rotation.y - _runner_angle) * signf(_turn_rate), -PI, PI)

## Plants the sign `ahead` radians before the runner (negative = behind), or removes it ("").
func _plant_sign(state: String, ahead: float = 0.0) -> void:
	_sign_state = state
	_sign.visible = state != ""
	if state == "":
		return
	var local := _runner_angle - ahead * signf(_turn_rate) - _turn.rotation.y
	_sign.position = Vector3(sin(local) * SIGN_RADIUS, SIGN_BASE, cos(local) * SIGN_RADIUS)
	_sign_age = 0.0
	_face_sign()

## The plate keeps facing the camera while the meadow turns under it.
func _face_sign() -> void:
	_sign.rotation.y = -_turn.rotation.y
	var grown := clampf(_sign_age / SIGN_GROW_SECONDS, 0.0, 1.0)
	_sign.scale = Vector3.ONE * (1.0 - pow(1.0 - grown, 3.0))

## The end of an arrival: the sign stands behind the tail.
func _stand_sign() -> void:
	if _sign == null:
		return
	if _sign_state != "next" or absf(_ring_angle(atan2(_sign.position.x, _sign.position.z)) - deg_to_rad(STOP_DEG)) > deg_to_rad(STOP_DEG):
		_plant_sign("stop", -deg_to_rad(STOP_DEG))
	else:
		_sign_state = "stop"
	_face_sign()

func _update_sign(delta: float) -> void:
	if _sign == null:
		return
	if _sign_state == "" and motion == "run" and milestone_shown and speed_kph > 1.0 and _turn_speed() * signf(_turn_rate) > 0.05:
		var left := meters_to_next - _meters_run
		if left < 0.0:
			left = fposmod(left, MAX_METERS)
		var lead: float = left / (speed_kph / 3.6) * absf(_turn_speed())
		if lead <= deg_to_rad(SIGN_LEAD):
			_plant_sign("next", lead)
	if _sign_state != "":
		_sign_age += delta
		_face_sign()
		if _sign_state == "next" and _ring_angle(atan2(_sign.position.x, _sign.position.z)) > deg_to_rad(SIGN_GONE):
			_plant_sign("")
		elif _sign_state == "stop" and motion == "run":
			_sign_state = "next"

func _make_sign() -> Node3D:
	var root := Node3D.new()
	root.name = "Milestone"
	var post := MeshInstance3D.new()
	var post_mesh := CylinderMesh.new()
	post_mesh.top_radius = 0.018
	post_mesh.bottom_radius = 0.024
	post_mesh.height = SIGN_POST
	post_mesh.radial_segments = 10
	post_mesh.material = StandardMaterial3D.new()
	post.mesh = post_mesh
	post.position.y = SIGN_POST * 0.5
	root.add_child(post)
	var plate := MeshInstance3D.new()
	var plate_mesh := CapsuleMesh.new()
	plate_mesh.radius = SIGN_PLATE.y
	plate_mesh.height = SIGN_PLATE.x
	plate_mesh.radial_segments = 16
	plate_mesh.rings = 4
	plate_mesh.material = StandardMaterial3D.new()
	plate.mesh = plate_mesh
	plate.rotation_degrees.z = 90.0
	plate.scale = Vector3(1.0, 1.0, SIGN_PLATE.z / (2.0 * SIGN_PLATE.y))
	plate.position.y = SIGN_POST
	root.add_child(plate)
	root.visible = false
	return root

func _build_scene(spec: Array) -> Node3D:
	var root := Node3D.new()
	var runner := Vector3.ZERO
	var facing := Vector3.BACK
	for entry in spec:
		var mount := Node3D.new()
		var model: Node3D = _scene(ASSET_PATH % entry["asset"]).instantiate()
		mount.add_child(model)
		var box := _bounds(model)
		var factor := float(entry["h"]) / box.size.y if entry.has("h") else float(entry["w"]) / box.size.x
		var pivot: Vector3 = entry.get("pivot", Vector3(0.5, 0.0, 0.5))
		model.scale = Vector3.ONE * factor
		model.position = -(box.position + box.size * pivot) * factor
		mount.position = entry["at"]
		mount.rotation_degrees.y = float(entry.get("yaw", 0.0))
		root.add_child(mount)
		if entry.has("tool"):
			var tool: Node3D = _scene(ASSET_PATH % entry["tool"]).instantiate()
			var tool_box := _bounds(tool)
			var length: float = box.size.y * TOOL_LENGTH / tool_box.size.y
			# The grip is a little above the handle's lower end.
			var hold := Vector3(tool_box.get_center().x, tool_box.position.y + tool_box.size.y * 0.18, tool_box.get_center().z)
			var basis := Basis.from_euler(TOOL_ROTATION * (PI / 180.0)).scaled(Vector3.ONE * length)
			tool.transform = Transform3D(basis, entry["grip"] * box.size.y - basis * hold)
			_hold(model, tool)
		if entry.has("anim"):
			for player in mount.find_children("*", "AnimationPlayer", true, false):
				player.callback_mode_process = AnimationMixer.ANIMATION_CALLBACK_MODE_PROCESS_MANUAL
				for clip_name in player.get_animation_list():
					player.get_animation(clip_name).loop_mode = Animation.LOOP_LINEAR
				_clips.append({"player": player, "anim": entry["anim"], "offset": float(entry.get("phase", 0.0)),
					"still": entry.get("still", ""), "mount": mount, "yaw": mount.rotation.y})
			if entry["anim"] == "gait":
				runner = mount.position
				facing = mount.basis * Vector3.BACK
				_stride_scale = factor
		if entry.has("move"):
			_parts.append({"node": mount, "base": mount.transform, "amp": entry["amp"],
				"speed": entry["speed"], "phase": float(entry.get("phase", 0.0))})
		if entry.get("turn", false):
			_turn = mount
	if _turn != null:
		# Turning the base by a moves the ground under the runner by a * radius along UP x radial.
		var radial := Vector3(runner.x - _turn.position.x, 0, runner.z - _turn.position.z)
		var tangent := Vector3.UP.cross(radial).normalized()
		_turn_rate = -facing.dot(tangent) / maxf(radial.length(), 0.01)
		_runner_angle = atan2(radial.x, radial.z)
		_sign = _make_sign()
		_turn.add_child(_sign)
	return root

## Puts a tool in the character's right hand.
func _hold(character: Node3D, tool: Node3D) -> void:
	var skeleton: Skeleton3D = character.find_children("*", "Skeleton3D", true, false)[0]
	var attachment := BoneAttachment3D.new()
	attachment.name = "RightHandTool"
	skeleton.add_child(attachment)
	attachment.bone_name = "RightHand"
	attachment.transform = skeleton.get_bone_global_rest(skeleton.find_bone("RightHand"))
	attachment.add_child(tool)

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
	_turn = null
	_sign = null
	_sign_state = ""
	_ground_speed = 0.0
	_parts.clear()
	_clips.clear()
	_model = _build_scene(SCENES[skin_id])
	_pivot.add_child(_model)
	_normalize_model()
	_glow.visible = GLOW_AT.has(skin_id)
	if _glow.visible:
		_glow.position = _model.transform * GLOW_AT[skin_id]
		_glow.modulate = GLOW_COLOR[skin_id]
		_glow.modulate.a = 0.55
	_layout()
	_rest_pose()

## Scale so the longest side is 2 units, centered on x/z with the base on y=0.
func _normalize_model() -> void:
	var box := _model.transform * _bounds(_model)
	var factor := 2.0 / maxf(box.size.x, maxf(box.size.y, box.size.z))
	_model.scale *= factor
	box = _model.transform * _bounds(_model)
	var center := box.get_center()
	_model.position -= Vector3(center.x, box.position.y, center.z)
	_model_size = box.size

func _apply_look() -> void:
	_palette = ThemeBackdrop.palette_for(theme_name, minute_of_day)
	var light: Dictionary = LIGHT[_palette]
	_environment.ambient_light_color = AMBIENT_COLOR * light["tint"]
	_environment.ambient_light_energy = light["ambient"]
	_key_light.light_color = KEY_COLOR * light["tint"]
	_key_light.light_energy = light["key"]
	_fill_light.light_color = AMBIENT_COLOR * light["tint"]
	_fill_light.light_energy = light["fill"]
	_backdrop.configure(skin_id, _palette)
	if _sign != null:
		for index in 2:
			var mesh: MeshInstance3D = _sign.get_child(index)
			var material: StandardMaterial3D = mesh.mesh.material
			material.albedo_color = Color(SIGN_COLORS[_palette][index])

## "narrow" for the band above the meter, "wide" for the column beside it.
static func layout_for(view: Vector2) -> String:
	return "narrow" if view.x >= view.y * BAND_ASPECT else "wide"

## Framing for the viewport and the model: how far the camera stands, the frustum shift that stands the
## base's front foot on BASE_BOTTOM of the height, and the backdrop under it. Computed once per layout or
## theme, never per frame: the backdrop redraws only here.
func _layout() -> void:
	var view := get_viewport().get_visible_rect().size
	if view.x <= 0.0 or view.y <= 0.0 or _model == null:
		return
	var half_tan := tan(deg_to_rad(CAMERA_FOV) * 0.5)
	var by_width := _model_size.x / MODEL_WIDTH / (2.0 * half_tan * view.x / view.y)
	var by_height := _model_size.y * 1.08 / MODEL_HEIGHT / (2.0 * half_tan)
	_camera_distance = maxf(by_height, by_width) + _model_size.z * 0.2
	_camera.v_offset = 0.0
	_place_camera(0.0)
	var foot := Vector3(0, 0, _model_size.z * 0.5)
	var depth := -(_camera.global_transform.affine_inverse() * foot).z
	_camera.v_offset = (view.y * BASE_BOTTOM - _camera.unproject_position(foot).y) / view.y * 2.0 * depth * half_tan
	_backdrop.set_ground(_camera.unproject_position(Vector3.ZERO).y)
	_backdrop.set_focus(_camera.unproject_position(Vector3(0, _model_size.y * 0.5, 0)), view.y * MODEL_HEIGHT * 0.5)

## Moves the camera around the model's centre by `orbit` radians (the working drift); 0 is the framing's view.
func _place_camera(orbit: float) -> void:
	var target := Vector3(0, _model_size.y * 0.5, 0)
	_camera.position = target + Vector3(sin(orbit) * _camera_distance, _camera_distance * CAMERA_LIFT, cos(orbit) * _camera_distance)
	_camera.look_at(target)

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
