#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
// Accept overlays/light/normal even if unused, to match common entity/chunk formats
in vec2 UV1;
in vec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec3 worldPos = Position + ChunkOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(worldPos, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
