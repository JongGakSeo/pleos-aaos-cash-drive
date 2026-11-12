extends SceneTree

## Renders the scene stills shown while the engine surface is hidden or starting, and behind the lock and
## the permission gate: every theme, day and night, in three sizes. The scene stills show the theme at
## rest; the panorama shows an arrival's end (on the meadow, the milestone behind the pony's tail).
## Renders off screen and forces the draw, so the app can be launched hidden. No window appears, but macOS
## still makes Godot the active app for a fraction of a second at launch, hence one launch for all stills:
##   open -g -j -n -W -a <Godot.app> --args --path godot --script res://tools/render_stills.gd -- <output_dir>
## Writes <skin>-{day,night}-{wide,narrow,panorama,thumb}.png; convert with cwebp -q 90 into
## android/app/src/home/res/drawable-nodpi/scene_<skin>_{day,night}_{wide,narrow,panorama,thumb}.webp.
## Sizes are the host's frames on emulator-5556 (a 1682x1210 window): the scene column beside the meter on a
## wide panel and the band above it on a narrow one (the still fills the same frame the engine draws into,
## fitted to its width and standing on its bottom edge), and the panorama the lock and the gate stand on
## their bottom edge on a landscape window, carrying its sky up. The thumb is the theme's picture on the
## settings page (16:10).
const SIZES := {"narrow": Vector2i(840, 702), "wide": Vector2i(1010, 1122), "panorama": Vector2i(1682, 800), "thumb": Vector2i(640, 400)}

func _initialize() -> void:
	call_deferred("run")

func run() -> void:
	var out: String = OS.get_cmdline_user_args()[0]
	DirAccess.make_dir_recursive_absolute(out)
	root.set_flag(Window.FLAG_NO_FOCUS, true)
	for size_name in SIZES:
		var view := SubViewport.new()
		view.size = SIZES[size_name]
		view.own_world_3d = true
		view.msaa_3d = Viewport.MSAA_2X
		view.render_target_update_mode = SubViewport.UPDATE_ALWAYS
		root.add_child(view)
		var stage: ThemeStage = load("res://main.tscn").instantiate()
		view.add_child(stage)
		await process_frame
		var generation := 0
		for skin in ThemeStage.SKINS:
			for theme_name in ThemeStage.THEMES:
				generation += 1
				# A static reveal shows the arrival's end; a stand shows the theme at rest.
				stage.apply_request({"skinId": skin, "mode": "STATIC", "motion": "reveal" if size_name == "panorama" else "stand",
					"theme": theme_name, "reducedMotion": false, "minuteOfDay": ThemeStage.DEFAULT_MINUTE, "metersToNext": ThemeStage.MAX_METERS,
					"viewport": {"width": view.size.x, "height": view.size.y}, "generationId": generation})
				for frame in 4:
					await process_frame
				RenderingServer.force_draw(false)
				view.get_texture().get_image().save_png("%s/%s-%s-%s.png" % [out, skin, theme_name, size_name])
				print("%s-%s-%s %dx%d" % [skin, theme_name, size_name, view.size.x, view.size.y])
		view.queue_free()
	quit(0)
