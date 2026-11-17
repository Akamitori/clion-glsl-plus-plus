#version 460 core
#include "common/lighting.glsl"

layout(location = 0) in vec3 v_normal;
layout(location = 0) out vec4 frag_color;
layout(set = 0, binding = 0) uniform Light { vec3 u_light_dir; };

void main() {
    vec3 n = normalize(v_normal);
    float lit = lambert(n, u_light_dir);
    frag_color = vec4(vec3(lit), 1.0);
    undefined_thing = 3; // expected error (line 12): undeclared identifier
}
