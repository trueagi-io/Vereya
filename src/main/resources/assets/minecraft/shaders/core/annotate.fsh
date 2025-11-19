#version 150

uniform sampler2D Sampler0;
uniform int entityColourR;
uniform int entityColourG;
uniform int entityColourB;
uniform int debugMode;
uniform int atlasGrid;
uniform int atlasLod; // MIP level used to derive per-sprite colour
uniform int respectAlpha; // 1 = discard transparent texels (cutouts)

in vec4 vertexColor;
in vec2 texCoord0;

// Use the same output name Minecraft core shaders expect
out vec4 FragColor;

vec4 colourFromAtlas(vec2 uv, int gridSize) {
    if (gridSize <= 0) {
        gridSize = 16;
    }
    float gx = floor(uv.x * float(gridSize));
    float gy = floor(uv.y * float(gridSize));
    float cell = gx + gy * float(gridSize);
    float base = 11.0;
    float r = mod(cell, base);
    cell = floor((cell - r) / base);
    float g = mod(cell, base);
    cell = floor((cell - g) / base);
    float b = mod(cell, base);
    return vec4(r / base, g / base, b / base, 1.0);
}

void main() {
    // Optionally discard fully transparent fragments for cutout geometry.
    if (respectAlpha != 0) {
        float a = texture(Sampler0, texCoord0).a;
        if (a < 0.1) {
            discard;
        }
    }
    if (debugMode == 1) {
        FragColor = vec4(1.0, 0.0, 1.0, 1.0);
        return;
    }
    if (debugMode == 2) {
        FragColor = vec4(fract(texCoord0 * 16.0), 0.0, 1.0);
        return;
    }
    if (debugMode == 3) {
        FragColor = vec4(fract(gl_FragCoord.xy / 32.0), 0.0, 1.0);
        return;
    }

    if (entityColourR >= 0) {
        FragColor = vec4(float(entityColourR) / 255.0,
                         float(entityColourG) / 255.0,
                         float(entityColourB) / 255.0,
                         1.0);
        return;
    }

    // Derive a stable per-sprite colour by sampling a higher MIP level
    // and hashing the averaged texel colour. This keeps a uniform colour
    // across each sprite and avoids per-fragment striping while reducing
    // collisions compared to a coarse grid.
    float lod = float(atlasLod);
    vec3 base = textureLod(Sampler0, texCoord0, lod).rgb;
    float h1 = fract(sin(dot(base, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
    float h2 = fract(sin(dot(base, vec3(93.9898, 67.345, 24.113))) * 24634.6345);
    float h3 = fract(sin(dot(base, vec3(19.123, 12.345, 98.765))) * 35791.0123);
    vec3 col = vec3(h1, h2, h3) * 0.7 + 0.3;
    FragColor = vec4(col, 1.0);
}
