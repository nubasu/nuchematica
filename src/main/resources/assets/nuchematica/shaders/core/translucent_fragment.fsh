#version 150

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D textureSampler;
uniform float alpha;

void main() {
    vec4 texColor = texture(textureSampler, texCoord);
    fragColor = vec4(texColor.rgb, texColor.a * alpha); // Apply transparency by modifying alpha
}