extends Node

## Records a theme in motion for presentation videos (Godot Movie Maker).
## Usage: Godot --path godot res://tools/movie.tscn --write-movie out/f.png --fixed-fps 30
##        --quit-after N --resolution WxH -- <skin> <day|night> <speedKph>
func _ready() -> void:
	var args := OS.get_cmdline_user_args()
	var skin: String = args[0] if args.size() > 0 else "horse"
	var theme_name: String = args[1] if args.size() > 1 else "day"
	var speed := float(args[2]) if args.size() > 2 else 0.0
	if args.size() > 4:
		var wanted := Vector2i(int(args[3]), int(args[4]))
		DisplayServer.window_set_size(wanted)
		get_window().size = wanted
		get_window().content_scale_size = wanted
	var stage = load("res://main.tscn").instantiate()
	add_child(stage)
	await get_tree().process_frame
	var size := get_viewport().get_visible_rect().size
	stage.set_safety_allowed(true)
	stage.apply_request({"skinId": skin, "mode": "PREVIEW", "theme": theme_name, "reducedMotion": false,
		"speedKph": speed, "viewport": {"width": size.x, "height": size.y}, "generationId": 1})
