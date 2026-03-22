#version 320 es
uniform mat4 projectionMatrix;
uniform mat4 modelMatrix;
uniform mat4 viewMatrix;

layout(location = 0) in vec3 aPos;

void main() {
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(aPos, 1.0);
}
