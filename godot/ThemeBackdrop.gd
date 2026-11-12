extends Control
class_name ThemeBackdrop

## Plain studio backdrop behind a clay diorama: a soft vertical gradient in the theme's colours, a glow
## behind the model and the base's shadow on the floor. The top row of the gradient is the sky the native
## stills continue upward. Redraws only when its inputs change, never per frame. Nothing here reads vehicle
## state: the stage feeds every value from the host request.
const PALETTES := {
	"horse": {
		"day": {"top": "bfe3ef", "bottom": "f1f7df", "glow": "ffffff", "shadow": "6f8f5f"},
		"dusk": {"top": "f2b880", "bottom": "fae3cc", "glow": "ffe9cf", "shadow": "7a6a4a"},
		"night": {"top": "10202f", "bottom": "21404a", "glow": "8fb9c4", "shadow": "08131b"},
	},
	"mine": {
		"day": {"top": "f0d9b8", "bottom": "faefdc", "glow": "fff6e0", "shadow": "9a7350"},
		"dusk": {"top": "eab48a", "bottom": "f8dcc4", "glow": "ffe4c4", "shadow": "8a5f44"},
		"night": {"top": "21160f", "bottom": "44301f", "glow": "f2a83b", "shadow": "0f0a06"},
	},
	"mining": {
		"day": {"top": "f6dcc0", "bottom": "fcf1df", "glow": "fff3cf", "shadow": "a8704a"},
		"dusk": {"top": "eeb490", "bottom": "f9dcc8", "glow": "ffe2c0", "shadow": "915c44"},
		"night": {"top": "231417", "bottom": "4a2a23", "glow": "f4c23d", "shadow": "120a0b"},
	},
}
## Dusk window of the day theme in minutes of the day: 16:30 to 18:30.
const DUSK_START := 990
const DUSK_END := 1110
const GLOW_RINGS := 36

var _palette: Dictionary = PALETTES["horse"]["day"]
var _palette_name := "day"
var _ground := 0.0
var _focus := Vector2.ZERO
var _reach := 200.0

static func palette_for(theme: String, minute_of_day: int) -> String:
	if theme == "night":
		return "night"
	return "dusk" if minute_of_day >= DUSK_START and minute_of_day <= DUSK_END else "day"

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	mouse_filter = Control.MOUSE_FILTER_IGNORE

func configure(skin: String, palette_name: String) -> void:
	_palette_name = palette_name
	_palette = PALETTES[skin][palette_name]
	queue_redraw()

## Screen y of the diorama base's floor contact.
func set_ground(y: float) -> void:
	if not is_equal_approx(y, _ground):
		_ground = y
		queue_redraw()

func set_focus(point: Vector2, reach: float) -> void:
	if not point.is_equal_approx(_focus) or not is_equal_approx(reach, _reach):
		_focus = point
		_reach = reach
		queue_redraw()

func _color(key: String) -> Color:
	return Color(_palette[key])

func _draw() -> void:
	draw_polygon(
		PackedVector2Array([Vector2.ZERO, Vector2(size.x, 0), size, Vector2(0, size.y)]),
		PackedColorArray([_color("top"), _color("top"), _color("bottom"), _color("bottom")]))
	# Stacked translucent discs fade out smoothly: a glow behind the model, a shadow under the base.
	for ring in GLOW_RINGS:
		draw_circle(_focus, _reach * (0.5 + 0.035 * ring), Color(_color("glow"), 0.014), true, -1.0, true)
	draw_set_transform(Vector2(_focus.x, _ground), 0.0, Vector2(1.0, 0.22))
	for ring in GLOW_RINGS:
		draw_circle(Vector2.ZERO, _reach * (0.62 + 0.012 * ring), Color(_color("shadow"), 0.016), true, -1.0, true)
	draw_set_transform(Vector2.ZERO)
