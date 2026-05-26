#version 320 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextTexture;
uniform float uAlpha;

void main() {
    vec4 textColor = texture(uTextTexture, vTexCoord);
    fragColor = vec4(textColor.rgb, textColor.a * uAlpha);
}
