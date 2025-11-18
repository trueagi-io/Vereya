#version 150

uniform sampler2D Sampler0;
uniform int entityColourR;
uniform int entityColourG;
uniform int entityColourB;
uniform int debugMode;
uniform int atlasGrid;

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

    FragColor = colourFromAtlas(texCoord0, atlasGrid);
}
