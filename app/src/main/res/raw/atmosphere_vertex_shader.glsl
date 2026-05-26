#version 320 es

uniform mat4 projectionMatrix;
uniform mat4 modelMatrix;
uniform mat4 viewMatrix;

layout(location = 0) in vec3 aPos;
layout(location = 2) in vec3 aNormal;

out vec3 vWorldPos;
out vec3 vNormal;

void main() {
    vec4 worldPos = modelMatrix * vec4(aPos, 1.0);
    vWorldPos = worldPos.xyz;
    vNormal = mat3(transpose(inverse(modelMatrix))) * aNormal;
    gl_Position = projectionMatrix * viewMatrix * worldPos;
}
