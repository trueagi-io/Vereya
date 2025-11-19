#version 150

in vec3 Position;
in vec2 UV0;
// Some vertex formats (entities, chunk meshes) include additional attributes
// like overlay UV1/light UV2/Normal that our shader does not use. Declare them
// optionally so the program can link against those formats without falling back
// to a mismatched simpler format that would scramble attributes.
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
    vertexColor = vec4(1.0);
}
