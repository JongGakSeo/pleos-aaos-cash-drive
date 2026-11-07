shader_type spatial;
render_mode cull_back, specular_schlick_ggx;

// Fake rig for the generated (unrigged) theme meshes. Positions are normalized to the mesh box:
// x from left to right in the front view, y from bottom to top, z from back to front.
uniform sampler2D albedo_tex : source_color, filter_linear_mipmap;
uniform sampler2D normal_tex : hint_normal, filter_linear_mipmap;
uniform bool use_normal = false;
uniform vec3 box_min;
uniform vec3 box_size = vec3(1.0);
uniform float t = 0.0;
uniform float amount = 0.0;
uniform int gallop = 0;
// Normalized height of the top of the base; legs swing above it.
uniform float leg_floor = 0.13;
// 0: model faces sideways (legs swing along x); 1: model faces the camera (legs swing along z).
uniform int gallop_axis = 0;
// Up to two swing joints: pivot.xy, radius, angle (radians) | speed, phase, unused, enabled
uniform vec4 swing_a = vec4(0.0);
uniform vec4 swing_a_timing = vec4(0.0);
uniform vec4 swing_b = vec4(0.0);
uniform vec4 swing_b_timing = vec4(0.0);

vec3 swing(vec3 p, vec3 n, vec4 joint, vec4 timing) {
	if (timing.w < 0.5) return p;
	float d = distance(n.xy, joint.xy);
	float w = 1.0 - smoothstep(joint.z * 0.7, joint.z, d);
	if (w <= 0.0) return p;
	// Strike: quick down stroke, slow lift.
	float cycle = fract(t * timing.x + timing.y);
	float stroke = cycle < 0.3 ? cycle / 0.3 : 1.0 - (cycle - 0.3) / 0.7;
	float a = joint.w * (stroke - 0.5) * amount * w;
	vec3 pivot = box_min + vec3(joint.xy, 0.5) * box_size;
	vec2 r = p.xy - pivot.xy;
	float c = cos(a), s = sin(a);
	p.xy = pivot.xy + vec2(c * r.x - s * r.y, s * r.x + c * r.y);
	return p;
}

void vertex() {
	vec3 n = (VERTEX - box_min) / box_size;
	vec3 p = VERTEX;
	if (gallop == 1) {
		// Legs: between the grass base and the belly, swing more toward the hooves.
		float legs = smoothstep(leg_floor, leg_floor + 0.06, n.y) * (1.0 - smoothstep(0.38, 0.46, n.y));
		float reach = clamp((0.46 - n.y) / (0.46 - leg_floor), 0.0, 1.0);
		float front = gallop_axis == 1 ? step(0.5, n.z) : 1.0 - step(0.5, n.x);
		float side = gallop_axis == 1 ? step(0.5, n.x) : step(0.5, n.z);
		float phase = front * 3.1416 + side * 1.2;
		float s = sin(t * 9.0 + phase);
		float swing = s * reach * legs * 0.16 * box_size.y * amount;
		if (gallop_axis == 1) { p.z += swing; } else { p.x += swing; }
		p.y += max(0.0, -s) * reach * legs * 0.05 * box_size.y * amount;
		// Tail and mane ripple.
		float tail = smoothstep(0.68, 0.8, n.x) * smoothstep(0.4, 0.55, n.y);
		p.y += sin(t * 8.0 + n.x * 9.0) * tail * 0.035 * box_size.y * amount;
		float mane = smoothstep(0.55, 0.75, n.y) * (1.0 - smoothstep(0.45, 0.6, n.x));
		p.x += sin(t * 9.0 + n.y * 6.0) * mane * 0.012 * box_size.y * amount;
	}
	p = swing(p, n, swing_a, swing_a_timing);
	p = swing(p, n, swing_b, swing_b_timing);
	VERTEX = p;
}

void fragment() {
	ALBEDO = texture(albedo_tex, UV).rgb;
	ROUGHNESS = 0.82;
	if (use_normal) {
		NORMAL_MAP = texture(normal_tex, UV).rgb;
	}
}
