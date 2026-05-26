#version 320 es
precision highp float;

in vec3 vWorldPos;
in vec3 vNormal;
out vec4 fragColor;

uniform vec3 lightPos;
uniform vec3 viewPos;
uniform float haloStrength;
uniform float layerStrength;
uniform float layerIndex;
uniform float time;

vec3 hueToRgb(float hue) {
    vec3 p = abs(fract(hue + vec3(0.0, 0.6666667, 0.3333333)) * 6.0 - 3.0);
    return clamp(p - 1.0, 0.0, 1.0);
}

vec3 softSpectrum(float hue) {
    vec3 rgb = hueToRgb(hue);
    rgb = pow(rgb, vec3(1.18));
    return mix(rgb, vec3(1.0), 0.08);
}

void main() {
    vec3 normal = normalize(vNormal);
    vec3 viewDir = normalize(viewPos - vWorldPos);
    vec3 sunDir = normalize(lightPos);
    float viewFacing = dot(normal, viewDir);
    if (viewFacing <= -0.02) {
        discard;
    }

    float rim = clamp(1.0 - viewFacing, 0.0, 1.0);
    float softRim = smoothstep(0.12, 0.98, rim);
    softRim = pow(softRim, 2.15);

    float lightFacing = dot(normal, sunDir);
    float viewSunOpposition = -dot(normalize(viewPos), sunDir);
    float backlight = smoothstep(-0.10, 1.0, viewSunOpposition);
    float sunEdge = smoothstep(-0.38, 0.76, lightFacing);
    float terminator = pow(1.0 - abs(lightFacing), 0.72);
    float layer = clamp(layerIndex / 5.0, 0.0, 1.0);

    float noiseA = sin(normal.x * 17.0 + normal.y * 29.0 + normal.z * 11.0 + time * 5.7);
    float noiseB = sin(normal.x * 37.0 - normal.y * 19.0 + layer * 8.0 + time * 3.1);
    float mist = 0.90 + 0.07 * noiseA + 0.03 * noiseB;

    float bandWave = 0.55 + 0.45 * sin(
        rim * 16.0 +
        terminator * 6.0 +
        lightFacing * 3.5 +
        layer * 9.0 +
        time * 0.8
    );
    float chromaMask = softRim *
        smoothstep(0.12, 0.98, terminator) *
        smoothstep(0.18, 0.92, bandWave) *
        mix(0.30, 1.0, backlight);

    float hue = fract(
        0.02 +
        layer * 0.18 +
        rim * 0.22 +
        terminator * 0.34 +
        lightFacing * 0.08 +
        noiseA * 0.018
    );
    vec3 spectrum = softSpectrum(hue);

    vec3 rayleighBlue = mix(vec3(0.05, 0.42, 0.72), vec3(0.12, 0.86, 0.92), softRim);
    vec3 highAltitudeCyan = vec3(0.22, 1.0, 0.82) * smoothstep(0.45, 1.0, layer);
    vec3 sunriseGold = vec3(1.0, 0.54, 0.13) * sunEdge * backlight;
    vec3 redFringe = vec3(1.0, 0.12, 0.035) *
        smoothstep(0.62, 1.0, lightFacing) *
        smoothstep(0.15, 1.0, backlight);
    vec3 greenCyanFringe = vec3(0.44, 1.0, 0.36) *
        smoothstep(0.44, 0.92, terminator) *
        smoothstep(0.35, 1.0, layer);

    vec3 haloColor = rayleighBlue * (0.40 + 0.18 * (1.0 - layer));
    haloColor += highAltitudeCyan * 0.24;
    haloColor += sunriseGold * 0.58;
    haloColor += redFringe * 0.34;
    haloColor += greenCyanFringe * 0.26;
    haloColor = mix(haloColor, spectrum, clamp(chromaMask * (0.60 + 0.34 * layer), 0.0, 0.76));

    float glow = softRim * mist * mix(0.42, 1.05, backlight) * mix(0.72, 1.18, sunEdge);
    float alpha = clamp(glow * haloStrength * layerStrength * 0.52, 0.0, 0.38);

    fragColor = vec4(haloColor * (0.76 + 0.34 * backlight + 0.18 * chromaMask), alpha);
}
