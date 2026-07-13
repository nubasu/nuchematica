#version 150
#extension GL_ARB_explicit_attrib_location : enable

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

out vec2 texCoord;

uniform mat4 modelViewProjection;

void main() {
    gl_Position = modelViewProjection * vec4(inPosition, 1.0);
    texCoord = inTexCoord;
}