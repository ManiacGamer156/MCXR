#version 100

attribute vec3 Position;
attribute vec4 Color;
attribute vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 TextureMat;

varying float vertexDistance;
varying vec4 vertexColor;
varying vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = length((ModelViewMat * vec4(Position, 1.0)).xyz);
    vertexColor = Color;
    texCoord0 = (TextureMat * vec4(UV0, 0, 1.0)).xy;
}