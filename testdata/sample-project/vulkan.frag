#version 460
#include "common/lighting.glsl"

layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform PC { vec4 tint; vec3 light_dir; } pc;
layout(location = 0) in vec2 uv;
layout(location = 1) in vec3 normal;
layout(location = 0) out vec4 color;

// SCALE comes from the project setting "Defines: SCALE=2.0". Expected: no errors.
void main() {
    float lit = lambert(normalize(normal), pc.light_dir) * SCALE;
    color = texture(tex, uv) * pc.tint * lit;
}
