#version 320 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D imageTexture;
uniform vec2 texelSize;
uniform vec2 direction;

void main() {
    vec2 step = direction * texelSize;
    vec4 color = texture(imageTexture, vTexCoord) * 0.22702703;
    color += texture(imageTexture, vTexCoord + step * 1.38461538) * 0.31621622;
    color += texture(imageTexture, vTexCoord - step * 1.38461538) * 0.31621622;
    color += texture(imageTexture, vTexCoord + step * 3.23076923) * 0.07027027;
    color += texture(imageTexture, vTexCoord - step * 3.23076923) * 0.07027027;
    fragColor = color;
}
