extends SceneTree

## Renders stills for review and the static driving fallback. Needs a display, not --headless.
## Usage: Godot --path godot --script res://tools/render_stills.gd -- <output_dir>
const SIZES := {"narrow": Vector2i(630, 888), "wide": Vector2i(1230, 608)}

func _initialize() -> void:
	call_deferred("run")

func _grab(path: String) -> void:
	await RenderingServer.frame_post_draw
	await RenderingServer.frame_post_draw
	get_root().get_texture().get_image().save_png(path)

func run() -> void:
	var out: String = OS.get_cmdline_user_args()[0]
	DirAccess.make_dir_recursive_absolute(out)
	var stage = load("res://main.tscn").instantiate()
	get_root().add_child(stage)
	await process_frame
	var generation := 1
	for size_name in SIZES:
		DisplayServer.window_set_size(SIZES[size_name])
		get_root().size = SIZES[size_name]
		await process_frame
		for skin in ThemeStage.SKINS:
			for theme_name in ThemeStage.THEMES:
				generation += 1
				stage.apply_request({"skinId": skin, "mode": "STATIC", "theme": theme_name, "reducedMotion": false,
					"viewport": {"width": SIZES[size_name].x, "height": SIZES[size_name].y}, "generationId": generation})
				await _grab("%s/%s-%s-%s.png" % [out, skin, theme_name, size_name])
	quit(0)
