// Include library: no #version, no main(). Checked through shader.frag, which includes it.
float lambert(vec3 n, vec3 l) {
    return max(dot(n, l), 0.0);
}
