extends Control
class_name ThemeBackdrop

## Flat 2D scenery behind the 3D model. Redraws only when its inputs change, never per frame.
const PALETTES := {
	"horse": {
		"day": {"sky1": "a8dbe6", "sky2": "f1f6d8", "far": "a6cf85", "near": "79b468", "ground": "5f9c55", "sun": "fff2bf", "cloud": "ffffff"},
		"night": {"sky1": "12324a", "sky2": "3c6d6a", "far": "1f4a3f", "near": "163a31", "ground": "0f2c25", "sun": "f3e3a8", "cloud": "5f8a8a"},
	},
	"mine": {
		"day": {"sky1": "8a5a36", "sky2": "f2c98b", "far": "a8744a", "near": "8a5a36", "ground": "b89467", "sun": "fff0c0", "cloud": "0b7f7c"},
		"night": {"sky1": "2c1a0f", "sky2": "6a3f1c", "far": "241409", "near": "33200f", "ground": "1d120b", "sun": "f2a83b", "cloud": "49d6d0"},
	},
	"mining": {
		"day": {"sky1": "f5d9b0", "sky2": "f9ecd4", "far": "d99a63", "near": "c47b4b", "ground": "b3683f", "sun": "fff0b8", "cloud": "fff0b8"},
		"night": {"sky1": "1a0f12", "sky2": "3a1d18", "far": "2f1712", "near": "43211a", "ground": "4f2a1f", "sun": "f4c23d", "cloud": "f4c23d"},
	},
}

var _skin := "horse"
var _palette: Dictionary = PALETTES["horse"]["day"]
var _ground := 0.0
var _focus := Vector2.ZERO
var _reach := 200.0
var _clouds: Control

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	_clouds = Control.new()
	_clouds.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_clouds.draw.connect(_draw_clouds)
	add_child(_clouds)

func configure(skin: String, theme_name: String) -> void:
	_skin = skin
	_palette = PALETTES[skin][theme_name]
	_refresh()

func set_ground(y: float) -> void:
	if not is_equal_approx(y, _ground):
		_ground = y
		_refresh()

func set_focus(point: Vector2, reach: float) -> void:
	if not point.is_equal_approx(_focus) or not is_equal_approx(reach, _reach):
		_focus = point
		_reach = reach
		_refresh()

func set_drift(amount: float) -> void:
	if _clouds != null:
		_clouds.position.x = amount * _reach * 0.12

func _refresh() -> void:
	queue_redraw()
	if _clouds != null:
		_clouds.queue_redraw()

func _color(key: String) -> Color:
	return Color(_palette[key])

func _draw() -> void:
	draw_polygon(
		PackedVector2Array([Vector2.ZERO, Vector2(size.x, 0), size, Vector2(0, size.y)]),
		PackedColorArray([_color("sky1"), _color("sky1"), _color("sky2"), _color("sky2")]))
	match _skin:
		"horse":
			draw_circle(_focus + Vector2(_reach * 0.42, -_reach * 0.34), _reach * 0.62, _color("sun"), true, -1.0, true)
			_draw_hill(0.34, 0.5, 0.0, _color("far"))
			_draw_hill(0.15, 0.32, 1.7, _color("near"))
		"mine":
			_draw_cave()
		"mining":
			draw_circle(_focus + Vector2(0, -_reach * 0.1), _reach * 1.05, Color(_color("sun"), 0.22), true, -1.0, true)
			_draw_hill(0.5, 0.6, 0.6, _color("far"))
			_draw_hill(0.2, 0.3, 2.2, _color("near"))
	draw_rect(Rect2(0, _ground, size.x, maxf(0.0, size.y - _ground)), _color("ground"))

func _draw_hill(height: float, waves: float, shift: float, color: Color) -> void:
	var points := PackedVector2Array()
	var steps := 48
	for i in range(steps + 1):
		var x := size.x * float(i) / steps
		var wave := sin(x / maxf(1.0, size.x) * TAU * waves + shift) * 0.5 + 0.5
		points.append(Vector2(x, _ground - _reach * height * (0.55 + 0.9 * wave)))
	points.append(Vector2(size.x, _ground))
	points.append(Vector2(0, _ground))
	draw_colored_polygon(points, color)

func _draw_cave() -> void:
	draw_rect(Rect2(Vector2.ZERO, size), _color("far"))
	var half := _reach * 1.25
	var arch := PackedVector2Array()
	var colors := PackedColorArray()
	var crown := _focus.y - _reach * 1.05
	for i in range(33):
		var angle := PI * float(i) / 32.0
		var point := Vector2(_focus.x - cos(angle) * half, _ground - sin(angle) * (_ground - crown))
		arch.append(point)
		colors.append(_color("sky2").lerp(_color("sky1"), sin(angle)))
	draw_polygon(arch, colors)
	draw_circle(_focus, _reach * 0.85, Color(_color("sun"), 0.14), true, -1.0, true)
	for side in [-1.0, 1.0]:
		var base := Vector2(_focus.x + side * half * 1.12, _ground)
		_draw_crystal(base, _reach * 0.3)
		_draw_crystal(base + Vector2(-side * _reach * 0.16, 0), _reach * 0.18)

func _draw_crystal(base: Vector2, height: float) -> void:
	var color := _color("cloud")
	draw_colored_polygon(PackedVector2Array([
		base + Vector2(-height * 0.28, 0), base + Vector2(-height * 0.2, -height * 0.7),
		base + Vector2(0, -height), base + Vector2(height * 0.2, -height * 0.7), base + Vector2(height * 0.28, 0),
	]), color)
	draw_colored_polygon(PackedVector2Array([
		base + Vector2(0, -height), base + Vector2(height * 0.2, -height * 0.7), base + Vector2(height * 0.28, 0), base,
	]), color.darkened(0.25))

func _draw_clouds() -> void:
	if _skin != "horse":
		return
	var color := _color("cloud")
	for spec in [Vector3(-0.85, -0.82, 1.0), Vector3(0.95, -0.25, 0.7), Vector3(-1.15, 0.05, 0.55)]:
		var center: Vector2 = _focus + Vector2(spec.x, spec.y) * _reach
		var width: float = _reach * 0.32 * spec.z
		for puff in [Vector3(-0.7, 0.0, 0.5), Vector3(0.0, -0.22, 0.72), Vector3(0.75, 0.02, 0.48)]:
			_clouds.draw_circle(center + Vector2(puff.x, puff.y) * width, width * puff.z, color, true, -1.0, true)
