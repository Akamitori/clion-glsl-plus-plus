#version 460
layout(location = 0) out vec4 c;
layout(push_constant) uniform PC { float exposure; } pc;

float tonemap(float x) { return x * pc.exposure; }

// Expected: five errors, all reported at once, on lines 9 to 13.
void main() {
    int a = 1.5;
    float x = first_missing * 2.0;
    second_missing = 3;
    float y = third_missing(1.0);
    bool b = vec3(1.0);
    c = vec4(tonemap(x));
}
