#version 320 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D bodyTexture; // Renamed to be generic
uniform vec3 lightColor;
uniform vec3 lightPos;
uniform vec3 viewPos;

// 0: Earth, 1: Moon, 2: Sun
uniform int bodyType; 

in vec3 FragPos;
in vec3 Normal;

void main() {
    vec4 texColor = texture(bodyTexture, vTexCoord);

    if (bodyType == 2) {
        // Sun is self-emissive
        fragColor = vec4(texColor.rgb, texColor.a);
    } else {
        // ambient
        float ambientStrength = (bodyType == 1) ? 0.2 : 0.6; // Moon has less ambient
        vec3 ambient = ambientStrength * lightColor;

        // diffuse
        vec3 norm = normalize(Normal);
        vec3 lightDir = normalize(lightPos - FragPos);
        float diff = max(dot(norm, lightDir), 0.0);
        vec3 diffuse = diff * lightColor;

        // specular
        float specularStrength = (bodyType == 1) ? 0.0 : 0.8; // Moon has no specular highlighting
        vec3 viewDir = normalize(viewPos - FragPos);
        vec3 reflectDir = reflect(-lightDir, norm);
        float spec = pow(max(dot(viewDir, reflectDir), 0.0), 2.0);
        vec3 specular = specularStrength * spec * lightColor;

        vec3 finalColor = texColor.rgb * (ambient + diffuse + specular);
        fragColor = vec4(finalColor, texColor.a);
    }
}