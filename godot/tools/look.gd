extends SceneTree

## Look-dev stills of a theme in motion, from any camera. Renders off screen into a SubViewport and
## forces the draw, so it also works with the app launched hidden. No window appears, but macOS still
## makes Godot the active app for a fraction of a second at launch, so batch the frames per launch:
##   open -g -j -n -W -a <Godot.app> --args --path godot --fixed-fps 60 --script res://tools/look.gd
##        -- <skin> <out_dir> [key=value ...]
## Keys: size (WxH, default 1010x1122), speed (kph), theme (day|night), minute (of day), meters (to the next
##       ticket), still (1 = the STATIC pose), reveal (frame number at which the host asks for the arrival),
##       frames (comma list of frame numbers to save, 60 per second),
##       at (x,y,z in diorama units), dist, yaw, lift (camera around `at`; default = the app camera),
##       clipN (clip name for the N-th animated character), nonormal (1 = normal maps off),
##       alone (1 = only the animated characters, on a transparent ground: frames for compositing, e.g. the
##       pony of the briefing videos' closing beat with --fixed-fps 24 and speed=0),
##       env.<property>, key.<property>, fill.<property> (set on the stage's Environment, key light
##       and fill light; values in str_to_var syntax, e.g. key.light_energy=1.1).
func _initialize() -> void:
	_run()

func _run() -> void:
	var args := OS.get_cmdline_user_args()
	var opt := {}
	for arg in args.slice(2):
		opt[arg.get_slice("=", 0)] = arg.get_slice("=", 1)
	root.set_flag(Window.FLAG_NO_FOCUS, true)
	root.size = Vector2i(64, 64)
	var view := SubViewport.new()
	var pixels: PackedStringArray = opt.get("size", "1010x1122").split("x")
	view.size = Vector2i(int(pixels[0]), int(pixels[1]))
	view.own_world_3d = true
	view.msaa_3d = Viewport.MSAA_2X
	view.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	root.add_child(view)
	var stage: ThemeStage = load("res://main.tscn").instantiate()
	view.add_child(stage)
	await process_frame
	stage.set_safety_allowed(true)
	var still: bool = opt.get("still", "0") == "1"
	var request := {"skinId": args[0], "mode": "STATIC" if still else "PREVIEW", "motion": "stand" if still else "run",
		"theme": opt.get("theme", "day"), "reducedMotion": false, "speedKph": float(opt.get("speed", "40")),
		"minuteOfDay": int(opt.get("minute", "720")), "metersToNext": float(opt.get("meters", "1000")),
		"viewport": {"width": view.size.x, "height": view.size.y}, "generationId": 1}
	stage.apply_request(request)
	for i in stage._clips.size():
		if opt.has("clip%d" % i):
			stage._clips[i]["anim"] = opt["clip%d" % i]
	if opt.get("nonormal", "0") == "1":
		for mesh in stage.find_children("*", "MeshInstance3D", true, false):
			for surface in mesh.mesh.get_surface_count():
				var material: BaseMaterial3D = mesh.mesh.surface_get_material(surface).duplicate()
				material.normal_enabled = false
				mesh.set_surface_override_material(surface, material)
	if opt.get("alone", "0") == "1":
		view.transparent_bg = true
		stage._environment.background_mode = Environment.BG_CLEAR_COLOR
		stage._backdrop.visible = false
		stage._glow.visible = false
		for mount in stage._model.get_children():
			mount.visible = stage._clips.any(func(clip): return clip["mount"] == mount)
	for key in opt:
		var node: Object = {"env": stage._environment, "key": stage._key_light, "fill": stage._fill_light}.get(key.get_slice(".", 0))
		if node != null:
			node.set(key.get_slice(".", 1), str_to_var(opt[key]))
	if opt.has("at"):
		var p: PackedStringArray = opt["at"].split(",")
		var target: Vector3 = stage._model.transform * Vector3(float(p[0]), float(p[1]), float(p[2]))
		var dist := float(opt.get("dist", "2"))
		var yaw := deg_to_rad(float(opt.get("yaw", "0")))
		var camera := Camera3D.new()
		camera.fov = 28.0
		stage.add_child(camera)
		camera.position = target + Vector3(sin(yaw) * dist, dist * float(opt.get("lift", "0.24")), cos(yaw) * dist)
		camera.look_at(target)
		camera.make_current()
	var wanted: Array = Array(opt.get("frames", "120").split(",")).map(func(text): return int(text))
	for frame in wanted.max() + 1:
		await process_frame
		if frame == int(opt.get("reveal", "-1")):
			request.merge({"generationId": 2, "motion": "reveal", "speedKph": 0.0}, true)
			stage.apply_request(request)
		if wanted.has(frame):
			RenderingServer.force_draw(false)
			for held in stage.find_children("RightHandTool", "BoneAttachment3D", true, false):
				var tool: Node3D = held.get_child(0)
				var box: AABB = stage._bounds(tool)
				var head: Vector3 = tool.global_transform * Vector3(box.get_center().x, box.end.y, box.get_center().z)
				var hand: Transform3D = stage._model.global_transform.affine_inverse() * held.global_transform
				print("frame %d tool head at %s, hand at %s, hand axes x %s y %s z %s (diorama space)" % [frame,
					stage._model.global_transform.affine_inverse() * head, hand.origin,
					hand.basis.x.normalized(), hand.basis.y.normalized(), hand.basis.z.normalized()])
			view.get_texture().get_image().save_png("%s/%s-%04d.png" % [args[1], args[0], frame])
	quit()
