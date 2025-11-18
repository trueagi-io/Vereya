#version 150

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec3 worldPos = Position + ChunkOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(worldPos, 1.0);
    texCoord0 = UV0;
    vertexColor = vec4(1.0);
}
