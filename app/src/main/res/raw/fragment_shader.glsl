#version 320 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D bodyTexture; // Renamed to be generic
uniform sampler2D nightTexture;
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
        float ambientStrength = (bodyType == 1) ? 0.2 : 0.22; // Keep Earth's night side dark enough for city lights.
        vec3 ambient = ambientStrength * lightColor;

        // diffuse
        vec3 norm = normalize(Normal);
        vec3 lightDir = normalize(lightPos - FragPos);
        float sunAmount = dot(norm, lightDir);
        float diff = max(sunAmount, 0.0);
        vec3 diffuse = diff * lightColor;

        // specular
        float specularStrength = (bodyType == 1) ? 0.0 : 0.8; // Moon has no specular highlighting
        vec3 viewDir = normalize(viewPos - FragPos);
        vec3 reflectDir = reflect(-lightDir, norm);
        float spec = pow(max(dot(viewDir, reflectDir), 0.0), 2.0);
        vec3 specular = specularStrength * spec * lightColor;

        vec3 finalColor = texColor.rgb * (ambient + diffuse + specular);
        if (bodyType == 0) {
            vec3 nightColor = texture(nightTexture, vTexCoord).rgb;
            float nightFactor = 1.0 - smoothstep(-0.22, 0.08, sunAmount);
            float twilightFactor = 1.0 - smoothstep(0.02, 0.28, sunAmount);
            float lightMask = smoothstep(0.04, 0.28, max(max(nightColor.r, nightColor.g), nightColor.b));
            vec3 warmCityLights = nightColor * vec3(1.45, 1.12, 0.62);
            finalColor += warmCityLights * lightMask * nightFactor * twilightFactor * 2.1;
        }
        fragColor = vec4(finalColor, texColor.a);
    }
}
