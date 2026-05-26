#version 320 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D glowTexture;
uniform float intensity;

void main() {
    vec4 glow = texture(glowTexture, vTexCoord);
    fragColor = vec4(glow.rgb * intensity, glow.a);
}
